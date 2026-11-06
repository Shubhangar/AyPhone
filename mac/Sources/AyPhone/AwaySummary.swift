import AppKit
import SwiftUI
#if canImport(FoundationModels)
import FoundationModels
#endif

/// "While you were away": what reached the phone while this Mac was locked or asleep, summed up
/// on the Mac by Apple's on-device model when you come back. Off by default (Notifications →
/// switch), and only offered where the model is available. Nothing leaves the Mac.
@MainActor
final class AwaySummary {
    struct Item {
        var time: Date
        var app: String
        var title: String
        var text: String
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

    /// A phone notification or missed call; kept only while away.
    func record(app: String, title: String, text: String) {
        guard awaySince != nil, enabled() else { return }
        // Codes are for typing in, not for a summary.
        guard OneTimeCode.find(in: title + " " + text) == nil else { return }
        items.append(Item(time: Date(), app: app, title: title, text: String(text.prefix(300))))
        if items.count > 80 { items.removeFirst() }
    }

    private func left() {
        guard awaySince == nil else { return }
        awaySince = Date()
        items = []
    }

    private func returned() {
        guard let since = awaySince else { return }
        awaySince = nil
        let missed = items
        items = []
        // A couple of minutes away, or one thing: not worth a summary.
        guard enabled(), Self.isAvailable, missed.count >= 2, Date().timeIntervalSince(since) > 120 else { return }
        Task { await summarize(missed) }
    }

    private func summarize(_ missed: [Item]) async {
        #if canImport(FoundationModels)
        guard #available(macOS 26, *) else { return }
        let time = DateFormatter()
        time.timeStyle = .short
        let list = missed.map { "\(time.string(from: $0.time)) · \($0.app) · \($0.title): \($0.text)" }.joined(separator: "\n")
        let session = LanguageModelSession(instructions: """
            You sum up what someone missed on their phone while away from their Mac, so they know \
            what to deal with first. Write 2 to 5 short bullet points, each starting with "• ". Put \
            people who wrote or called and may need a reply first, and say who and what about. Group \
            several messages from the same person into one point. Mention missed calls. Do not mention \
            offers, promotions, deliveries, payments, bank alerts, renewals, sign-in codes or other \
            automatic notices at all. Only when no person wrote or called, write the single point \
            "• Nothing that needs you." No headings, no greeting, nothing else.
            """)
        do {
            let reply = try await session.respond(to: "Here is what arrived:\n\(list)")
            // The model sometimes adds "nothing needs you" after real points; keep it only alone.
            var lines = reply.content.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            if lines.count > 1 { lines.removeAll { $0.lowercased().contains("nothing") && $0.lowercased().contains("needs you") } }
            show(lines.joined(separator: "\n"), count: missed.count)
        } catch {
            // The model can refuse or be busy; the notifications are all in AyPhone anyway.
        }
        #endif
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
