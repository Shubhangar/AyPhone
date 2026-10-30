import AppKit
import SwiftUI

/// A glow around the screen's edges when a file moves between the phone and the Mac, as if
/// it travelled through the top of the screen. Sending: the edges warm up, a band of light
/// climbs both sides and gathers at the top before fading. Receiving: the light arrives at
/// the top and flows down the sides. Then a small "Sent" / "Received" drops in from the top.
/// Click-through, on top of everything, about 4 seconds.
@MainActor
enum TransferGlow {
    enum Kind { case received, sending }

    private static var panel: NSPanel?
    private static var busy = false

    static func show(_ kind: Kind) {
        guard !busy, !screenLocked, let screen = NSScreen.main else { return }
        busy = true
        let panel = self.panel ?? makePanel()
        panel.setFrame(screen.frame, display: false)
        panel.contentView = NSHostingView(rootView: GlowView(kind: kind))
        panel.orderFrontRegardless()
        DispatchQueue.main.asyncAfter(deadline: .now() + GlowView.duration(kind) + 0.1) {
            panel.orderOut(nil)
            panel.contentView = nil
            busy = false
        }
    }

    /// Locked or asleep: nobody would see it.
    private static var screenLocked: Bool {
        let session = CGSessionCopyCurrentDictionary() as? [String: Any]
        return session?["CGSSessionScreenIsLocked"] as? Bool == true || CGDisplayIsAsleep(CGMainDisplayID()) != 0
    }

    private static func makePanel() -> NSPanel {
        let panel = NSPanel(contentRect: .zero, styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
        panel.level = .screenSaver
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = false
        panel.ignoresMouseEvents = true
        self.panel = panel
        return panel
    }
}

/// Everything follows the clock (not chained animations), so the motion stays smooth.
private struct GlowView: View {
    let kind: TransferGlow.Kind
    @State private var start = Date()

    static func duration(_ kind: TransferGlow.Kind) -> Double { glowEnd(kind) + 1.6 }
    /// When the glow itself has faded.
    static func glowEnd(_ kind: TransferGlow.Kind) -> Double { kind == .sending ? 3.0 : 3.1 }

    private let colors = [Theme.indigo, Theme.cyan, Color(hex: 0x5B6BFF), Theme.cyan, Theme.indigo]

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSince(start)
            GeometryReader { geo in
                frame(t: t, size: geo.size)
            }
        }
        .ignoresSafeArea()
        .onAppear { start = Date() }
    }

    @ViewBuilder
    private func frame(t: Double, size: CGSize) -> some View {
        let h = size.height
        let sending = kind == .sending
        let total = Self.glowEnd(kind)
        // The whole edge: warms up, holds, fades (with a last swell after a file arrives).
        let base = 0.4 * ramp(t, 0, 0.9) * (1 - ramp(t, total - 0.8, total))
            + (sending ? 0 : 0.35 * bump(t, 2.0, 2.5, 3.0))
        // The band's centre, travelling up (sending) or down (receiving) past the screen.
        let travel = sending ? ease(ramp(t, 0.5, 2.3)) : ease(ramp(t, 0.7, 2.5))
        let center = sending ? h * (1.2 - 1.45 * travel) : h * (-0.25 + 1.45 * travel)
        // The gathering at the top: as it leaves (sending), or as it arrives (receiving).
        let top = sending ? bump(t, 1.7, 2.3, 2.9) : bump(t, 0, 0.45, 1.3)

        ZStack {
            edge(width: 26, blur: 30).opacity(base)
            ZStack {
                edge(width: 40, blur: 34)
                edge(width: 5, blur: 2)
            }
            .mask {
                // A soft band with a bright head leading the way and a long tail behind it.
                LinearGradient(stops: sending
                               ? [.init(color: .clear, location: 0), .init(color: .white, location: 0.12),
                                  .init(color: .white.opacity(0.55), location: 0.4), .init(color: .clear, location: 1)]
                               : [.init(color: .clear, location: 0), .init(color: .white.opacity(0.55), location: 0.6),
                                  .init(color: .white, location: 0.88), .init(color: .clear, location: 1)],
                               startPoint: .top, endPoint: .bottom)
                    .frame(width: size.width, height: h * 0.75)
                    .position(x: size.width / 2, y: center)
            }
            Ellipse()
                .fill(LinearGradient(colors: [Theme.cyan, Theme.indigo, Color(hex: 0x5B6BFF)], startPoint: .leading, endPoint: .trailing))
                .frame(width: size.width * 0.55, height: 170)
                .blur(radius: 70)
                .position(x: size.width / 2, y: 0)
                .opacity(0.9 * top)

            // The little drop-down once it's done.
            let drop = ease(ramp(t, total - 0.55, total - 0.05)) * (1 - ease(ramp(t, total + 1.0, total + 1.5)))
            HStack(spacing: 8) {
                Image(systemName: sending ? "checkmark.circle.fill" : "arrow.down.circle.fill")
                    .foregroundStyle(Theme.accent)
                Text(sending ? "Sent" : "Received").font(.callout.weight(.semibold)).foregroundStyle(.white)
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(LinearGradient(colors: [Theme.cyan.opacity(0.7), Theme.indigo.opacity(0.4)],
                                                          startPoint: .leading, endPoint: .trailing), lineWidth: 1))
            .shadow(color: Theme.indigo.opacity(0.45), radius: 16, y: 4)
            .environment(\.colorScheme, .dark)
            .position(x: size.width / 2, y: -30 + 92 * drop)
            .opacity(drop)
        }
    }

    private func edge(width: CGFloat, blur: CGFloat) -> some View {
        RoundedRectangle(cornerRadius: 18, style: .continuous)
            .stroke(AngularGradient(colors: colors, center: .center), lineWidth: width)
            .blur(radius: blur)
    }

    /// 0 before `a`, 1 after `b`, smooth in between.
    private func ramp(_ t: Double, _ a: Double, _ b: Double) -> Double {
        let x = min(max((t - a) / (b - a), 0), 1)
        return x * x * (3 - 2 * x)
    }

    /// Rises from `a` to a peak at `peak`, back to 0 by `b`.
    private func bump(_ t: Double, _ a: Double, _ peak: Double, _ b: Double) -> Double {
        ramp(t, a, peak) * (1 - ramp(t, peak, b))
    }

    private func ease(_ x: Double) -> Double { x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2 }
}
