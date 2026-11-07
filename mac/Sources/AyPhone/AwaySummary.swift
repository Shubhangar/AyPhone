import AppKit
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "away")

/// "While you were away": what reached the phone while this Mac was locked or asleep, summed up
/// on the Mac by Apple's on-device model when you come back. Off by default (Notifications →
/// switch), and only offered where the model is available. Nothing leaves the Mac.
@MainActor
final class AwaySummary {
    enum Kind { case missedCall, message, reminder }

    struct Item {
        var time: Date
        var pkg: String
        var app: String
        var title: String
        var text: String
        var kind: Kind
    }

    var enabled: () -> Bool = { false }
    var showAll: () -> Void = {}

    private var awaySince: Date?
    private var items: [Item] = []
    private var panel: NSPanel?

    /// Whether this Mac can do it (Apple Intelligence on, macOS 26 or later).
    static var isAvailable: Bool {
        #if canImport(FoundationModels)
        if #available(macOS 26, *), case .available = SystemLanguageModel.default.availability { return true }
        #endif
        return false
    }

    func start() {
        // Locked, or the screens went dark: away. Unlocked, or awake without a lock: back.
        let distributed = DistributedNotificationCenter.default()
        distributed.addObserver(forName: .init("com.apple.screenIsLocked"), object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.left() }
        }
        distributed.addObserver(forName: .init("com.apple.screenIsUnlocked"), object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.returned() }
        }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.screensDidSleepNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.left() }
        }
    }

    /// A phone notification; kept only while away, and only if it's something a person would act on.
    func record(_ n: PhoneNotification, category: String?) {
        guard awaySince != nil, enabled() else { return }
        // Codes are for typing in, not for a summary.
        guard OneTimeCode.find(in: n.title + " " + n.text) == nil else { return }
        guard let kind = Self.kind(of: n, category: category) else {
            log.info("left out \(n.pkg, privacy: .public) category=\(category ?? "none", privacy: .public)")
            return
        }
        log.info("kept \(n.pkg, privacy: .public) category=\(category ?? "none", privacy: .public) as \(String(describing: kind), privacy: .public)")
        items.append(Item(time: Date(), pkg: n.pkg, app: n.app, title: n.title, text: String(n.text.prefix(400)), kind: kind))
        if items.count > 80 { items.removeFirst() }
    }

    /// Sorted by what the app says the notification is, never by guessing from its words.
    /// Promotions, deliveries, progress, system and other automatic notices are left out.
    static func kind(of n: PhoneNotification, category: String?) -> Kind? {
        switch category {
        case "missed_call": return .missedCall
        case "msg", "email", "social": return .message
        case "event", "reminder", "alarm": return .reminder
        case .some: return nil
        case nil: break
        }
        // No category (some apps set none, and older phone apps don't send it).
        let dialers: Set = ["com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer",
                            "com.android.server.telecom", "com.samsung.android.incallui"]
        if dialers.contains(n.pkg) {
            return (n.title + " " + n.text).lowercased().contains("missed") ? .missedCall : nil
        }
        if n.pkg.contains("calendar") { return .reminder }
        return n.canReply ? .message : nil
    }

    private func left() {
        guard awaySince == nil else { return }
        awaySince = Date()
        items = []
        log.info("away")
    }

    private func returned() {
        guard let since = awaySince else { return }
        awaySince = nil
        let missed = items
        items = []
        // Only things a person would act on are kept, so even one is worth showing after a while away.
        let minutes = Date().timeIntervalSince(since) / 60
        log.info("back after \(minutes, format: .fixed(precision: 1), privacy: .public) min, \(missed.count, privacy: .public) kept, enabled=\(self.enabled(), privacy: .public) model=\(Self.isAvailable, privacy: .public)")
        guard enabled(), Self.isAvailable, !missed.isEmpty, minutes > 2 else { return }
        Task { await summarize(missed) }
    }

    /// Calls and reminders are listed straight from the notifications. Only a long thread from one
    /// person goes to the model, alone, to be shortened, so it can't mix people up or invent calls.
    private func summarize(_ missed: [Item]) async {
        var lines: [String] = []

        // Missed calls, by caller.
        var callers: [(name: String, count: Int)] = []
        for item in missed where item.kind == .missedCall {
            let name = Self.caller(item)
            if let i = callers.firstIndex(where: { $0.name == name }) { callers[i].count += 1 } else { callers.append((name, 1)) }
        }
        for c in callers {
            let calls = c.count == 1 ? "Missed call" : "\(c.count) missed calls"
            lines.append(c.name.isEmpty ? "• \(calls)" : "• \(calls) from \(c.name)")
        }

        // Messages, one point per conversation, latest first.
        var threads: [(key: String, items: [Item])] = []
        for item in missed where item.kind == .message && !Self.isRollup(item) {
            let key = item.pkg + "|" + item.title
            if let i = threads.firstIndex(where: { $0.key == key }) { threads[i].items.append(item) } else { threads.append((key, [item])) }
        }
        threads.sort { ($0.items.last?.time ?? .distantPast) > ($1.items.last?.time ?? .distantPast) }
        for thread in threads.prefix(4) {
            guard let first = thread.items.first else { continue }
            let who = first.title.isEmpty ? first.app : "\(first.title) (\(first.app))"
            lines.append("• \(who): \(await gist(of: thread.items))")
        }
        if threads.count > 4 { lines.append("• And \(threads.count - 4) more conversations") }

        // Reminders and events, as the app wrote them.
        var seen = Set<String>()
        for item in missed.reversed() where item.kind == .reminder && seen.insert(item.title).inserted {
            guard seen.count <= 2 else { break }
            let detail = item.text.isEmpty || item.text == item.title ? "" : " · " + Self.clip(item.text, 60)
            lines.append("• \(item.app): \(item.title)\(detail)")
        }

        guard !lines.isEmpty else { return }
        show(lines.joined(separator: "\n"), count: missed.count)
    }

    /// One conversation in a line: a single short message is quoted as is; longer ones are
    /// shortened by the on-device model, from that conversation's text alone.
    private func gist(of thread: [Item]) async -> String {
        let texts = thread.map(\.text).filter { !$0.isEmpty }
        let fallback = Self.clip(texts.last ?? "", 90) + (texts.count > 1 ? " (+\(texts.count - 1) more)" : "")
        if texts.count == 1, texts[0].count <= 90 { return "“\(texts[0])”" }
        #if canImport(FoundationModels)
        if #available(macOS 26, *) {
            let session = LanguageModelSession(instructions: """
                Shorten the messages into one sentence of at most 15 words that says what they ask \
                or tell. Use only what the messages say. Never add names, times, places, calls or \
                anything else that isn't in them. Reply with the sentence only.
                """)
            let options = GenerationOptions(sampling: .greedy, maximumResponseTokens: 60)
            if let reply = try? await session.respond(to: texts.joined(separator: "\n"), options: options) {
                let line = reply.content.split(separator: "\n").first.map(String.init)?
                    .trimmingCharacters(in: CharacterSet(charactersIn: "•-*\"“” ").union(.whitespaces)) ?? ""
                if !line.isEmpty, line.count <= 140 { return line }
            }
        }
        #endif
        return fallback
    }

    /// The other side of a missed-call notification: the name or number, not "Missed call".
    private static func caller(_ item: Item) -> String {
        [item.title, item.text].map { $0.trimmingCharacters(in: .whitespaces) }
            .first { !$0.isEmpty && !$0.lowercased().contains("missed") } ?? ""
    }

    /// "5 new messages" style summaries some apps post on top of the real ones.
    private static func isRollup(_ item: Item) -> Bool {
        item.text.range(of: #"^\d+ (new )?messages?( from \d+ chats?)?$"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    private static func clip(_ s: String, _ n: Int) -> String {
        s.count <= n ? s : String(s.prefix(n - 1)).trimmingCharacters(in: .whitespaces) + "…"
    }

    private func show(_ summary: String, count: Int) {
        guard !summary.isEmpty, let screen = NSScreen.main else { return }
        let panel = self.panel ?? {
            let p = NSPanel(contentRect: .zero, styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
            p.level = .floating
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
            p.backgroundColor = .clear
            p.isOpaque = false
            p.hasShadow = false
            self.panel = p
            return p
        }()
        let card = AwayCard(summary: summary, count: count,
                            showAll: { [weak self] in self?.hide(); self?.showAll() },
                            dismiss: { [weak self] in self?.hide() })
        let host = NSHostingView(rootView: card)
        let size = host.fittingSize
        panel.contentView = host
        let visible = screen.visibleFrame
        panel.setFrame(NSRect(x: visible.maxX - size.width - 12, y: visible.maxY - size.height - 12, width: size.width, height: size.height),
                       display: true)
        panel.alphaValue = 0
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { $0.duration = 0.3; panel.animator().alphaValue = 1 }
    }

    private func hide() {
        guard let panel else { return }
        NSAnimationContext.runAnimationGroup({ $0.duration = 0.2; panel.animator().alphaValue = 0 }) {
            MainActor.assumeIsolated { panel.orderOut(nil) }
        }
    }
}

private struct AwayCard: View {
    let summary: String
    let count: Int
    let showAll: () -> Void
    let dismiss: () -> Void

    var body: some View {
        Glass(padding: 16, radius: 18) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    IconBadge(symbol: "sparkles", colors: [Theme.indigo, Theme.pink], size: 30)
                    VStack(alignment: .leading, spacing: 1) {
                        Text("While you were away").font(.headline)
                        Text("\(count) things on your phone · summed up on this Mac").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    Button { dismiss() } label: { Image(systemName: "xmark").font(.caption.weight(.bold)) }
                        .buttonStyle(.plain).foregroundStyle(.secondary)
                }
                Text(summary).font(.callout).fixedSize(horizontal: false, vertical: true)
                HStack {
                    Spacer()
                    Button("Show all") { showAll() }.buttonStyle(.glass)
                }
            }
        }
        .frame(width: 360)
        .padding(14)
        .environment(\.colorScheme, .dark)
    }
}
