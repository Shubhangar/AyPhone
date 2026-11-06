import AppKit
import SwiftUI

/// "What's new" after an update: a few cards, each a small looping animation with a line or two.
/// Once per version, and not on a fresh install (pairing covers that).
@MainActor
enum WhatsNew {
    private static let seenKey = "whatsNewSeen"
    private static let launchedKey = "lastLaunchedVersion"
    private static var window: NSWindow?

    /// On launch: shows the tour if this version is new to an existing install.
    static func showIfDue(state: AppState) {
        let version = Updater.currentVersion
        let defaults = UserDefaults.standard
        let previous = defaults.string(forKey: launchedKey)
        defaults.set(version, forKey: launchedKey)
        // (Installs from before this existed have a pairing key.)
        let updated = previous != nil || defaults.string(forKey: "pairingKey") != nil
        guard defaults.string(forKey: seenKey) != version, updated, previous != version else { return }
        show(state: state)
    }

    static func show(state: AppState) {
        UserDefaults.standard.set(Updater.currentVersion, forKey: seenKey)
        if let window {
            window.makeKeyAndOrderFront(nil)
            return
        }
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 560), styleMask: [.titled, .closable, .fullSizeContentView],
                         backing: .buffered, defer: false)
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.isReleasedWhenClosed = false
        let host = NSHostingView(rootView: WhatsNewView(close: { w.close() }).environmentObject(state))
        host.sizingOptions = []
        w.contentView = host
        w.center()
        window = w
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }
}

private struct TourPage {
    var title: String
    var body: String
    var art: (Double) -> AnyView
}

private struct WhatsNewView: View {
    @EnvironmentObject private var state: AppState
    let close: () -> Void
    @ViewState private var index = 0
    @ViewState private var start = Date()

    private var pages: [TourPage] {
        [
            TourPage(title: "Scan with your phone",
                     body: "In the menu bar: Scan doc, Scan text or Photo. Your phone's camera opens, and the PDF, the text or the photo lands right where you're working.",
                     art: { AnyView(ScanArt(t: $0)) }),
            TourPage(title: "Deck",
                     body: "Your phone shows big buttons for the app you're using here, like Figma, Zoom or Spotify, and they change as you switch apps.",
                     art: { AnyView(DeckArt(t: $0)) }),
            TourPage(title: "Pad",
                     body: "Draw here with your phone or S Pen, place your signature on a PDF, or mark up this screen while you present.",
                     art: { AnyView(InkArt(t: $0)) }),
            TourPage(title: "While you were away",
                     body: AwaySummary.isAvailable
                        ? "Unlock your Mac and get a short summary of what came in on your phone. Made by Apple Intelligence on this Mac; nothing leaves it."
                        : "On Macs with Apple Intelligence: a short summary of what came in on your phone while you were away.",
                     art: { AnyView(AwayArt(t: $0)) }),
            TourPage(title: "AyPhone updates itself",
                     body: "When there's a new version, AyPhone tells you here and on your phone. One click installs it.",
                     art: { AnyView(UpdateArt(t: $0)) }),
        ]
    }

    var body: some View {
        let page = pages[index]
        VStack(spacing: 18) {
            Text("What's new in AyPhone").font(.caption.weight(.semibold)).foregroundStyle(.secondary).padding(.top, 12)
            TimelineView(.animation) { context in
                let t = context.date.timeIntervalSince(start).truncatingRemainder(dividingBy: 2.6) / 2.6
                Glass(padding: 0, radius: 22) {
                    page.art(t).frame(maxWidth: .infinity).frame(height: 230)
                }
            }
            .id(index)
            VStack(spacing: 8) {
                Text(page.title).font(.title2.weight(.bold))
                Text(page.body).font(.body).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 12)
            Spacer(minLength: 0)
            HStack(spacing: 6) {
                ForEach(pages.indices, id: \.self) { i in
                    Capsule().fill(.white.opacity(i == index ? 1 : 0.3)).frame(width: i == index ? 18 : 6, height: 6)
                }
            }
            HStack {
                if index < pages.count - 1 { Button("Skip") { close() }.buttonStyle(.plain).foregroundStyle(.secondary) }
                Spacer()
                if index == 3 && AwaySummary.isAvailable && !state.awaySummaryEnabled {
                    Button("Turn on") { state.awaySummaryEnabled = true }.buttonStyle(.glass)
                }
                Button {
                    if index == pages.count - 1 { close() } else { withAnimation(Theme.spring) { index += 1 }; start = Date() }
                } label: {
                    Text(index == pages.count - 1 ? "Get started" : "Next").font(.body.weight(.semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 20).padding(.vertical, 8)
                        .background { Capsule().fill(Theme.indigo) }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain).keyboardShortcut(.defaultAction)
            }
        }
        .padding(.horizontal, 24).padding(.top, 36).padding(.bottom, 24)
        // The window's full size (its content runs under the title bar).
        .frame(width: 520, height: 560)
        .background(AuroraBackground())
        // The window's content runs under its transparent title bar; one coordinate space for all.
        .ignoresSafeArea()
        .environment(\.colorScheme, .dark)
    }
}

// MARK: Animations (each loops over t, 0…1)

private struct ScanArt: View {
    let t: Double
    var body: some View {
        ZStack {
            // A page with a scan line passing over it, then flying into the laptop.
            RoundedRectangle(cornerRadius: 6).fill(Color(white: 0.95)).frame(width: 80, height: 104)
                .overlay(VStack(spacing: 8) { ForEach(0..<5, id: \.self) { i in Capsule().fill(Color(white: 0.75)).frame(width: i % 2 == 0 ? 56 : 40, height: 5) } })
                .overlay(alignment: .top) {
                    if t < 0.6 { Capsule().fill(Theme.cyan).frame(width: 96, height: 3).offset(y: 104 * t / 0.6) }
                }
                .offset(x: -80 + 150 * max(0, (t - 0.6) / 0.4), y: -40 * sin(.pi * max(0, (t - 0.6) / 0.4)))
                .scaleEffect(1 - 0.5 * max(0, (t - 0.6) / 0.4))
            Image(systemName: "laptopcomputer").font(.system(size: 80, weight: .thin)).foregroundStyle(.white.opacity(0.8)).offset(x: 90)
        }
    }
}

private struct DeckArt: View {
    let t: Double
    var body: some View {
        let lit = Int(t * 9) % 9
        RoundedRectangle(cornerRadius: 22).fill(Color(white: 0.1)).frame(width: 130, height: 210)
            .overlay(
                LazyVGrid(columns: Array(repeating: GridItem(.fixed(30), spacing: 8), count: 3), spacing: 8) {
                    ForEach(0..<9, id: \.self) { i in
                        RoundedRectangle(cornerRadius: 8).fill(i == lit ? Theme.indigo : .white.opacity(0.12)).frame(width: 30, height: 30)
                    }
                }
            )
            .overlay(alignment: .top) { RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0xA259FF)).frame(width: 22, height: 22).padding(.top, 18) }
    }
}

private struct InkArt: View {
    let t: Double
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 10).fill(Color(white: 0.16)).frame(width: 300, height: 180)
            Path { p in
                p.move(to: CGPoint(x: 40, y: 120))
                p.addCurve(to: CGPoint(x: 150, y: 70), control1: CGPoint(x: 70, y: 40), control2: CGPoint(x: 110, y: 140))
                p.addCurve(to: CGPoint(x: 260, y: 60), control1: CGPoint(x: 190, y: 10), control2: CGPoint(x: 230, y: 120))
            }
            .trim(from: 0, to: min(1, t / 0.8))
            .stroke(Color(hex: 0xFF4FD8), style: StrokeStyle(lineWidth: 5, lineCap: .round))
            .frame(width: 300, height: 180)
        }
    }
}

private struct AwayArt: View {
    let t: Double
    var body: some View {
        let slide = min(1, t / 0.3)
        ZStack(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: 10).fill(Color(white: 0.16)).frame(width: 300, height: 180)
            VStack(alignment: .leading, spacing: 8) {
                HStack { Image(systemName: "sparkles"); Capsule().fill(.white).frame(width: 90, height: 8) }
                ForEach(0..<3, id: \.self) { i in
                    Capsule().fill(.white.opacity(0.7)).frame(width: 120 * min(1, max(0, (t - 0.35 - Double(i) * 0.12) / 0.12)), height: 6)
                }
            }
            .padding(12).frame(width: 150, alignment: .leading)
            .background(Theme.indigo.opacity(0.85), in: RoundedRectangle(cornerRadius: 12))
            .padding(12)
            .offset(x: 170 * (1 - slide))
            .clipped()
        }
        .frame(width: 300, height: 180).clipped()
    }
}

private struct UpdateArt: View {
    let t: Double
    var body: some View {
        ZStack {
            Circle().trim(from: 0, to: min(1, t / 0.7)).stroke(Theme.indigo, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90)).frame(width: 110, height: 110)
            Image(systemName: t > 0.7 ? "checkmark" : "arrow.down").font(.system(size: 40, weight: .semibold)).foregroundStyle(.white)
        }
    }
}
