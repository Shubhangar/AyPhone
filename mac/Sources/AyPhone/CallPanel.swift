import AppKit
import Combine
import SwiftUI
import os

/// A floating card in the top-right corner (on every Space, over full-screen apps) that
/// stays up for as long as a call is ringing or in progress, until it's handled on the
/// phone or the Mac. macOS banners can't be made to stay, so calls use this instead.
@MainActor
final class CallPanelController {
    private static let margin: CGFloat = 4
    private var panel: NSPanel?
    private var host: PanelHostingView?
    private var observer: AnyCancellable?
    private var visibleID: String?
    private var ringTimer: Timer?

    func start(state: AppState) {
        // objectWillChange fires before the change; hopping to the main queue reads the new value.
        // (The main queue also runs during modal dialogs and open menus, unlike RunLoop.main.)
        observer = state.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak state] _ in
                guard let self, let state else { return }
                self.update(state)
            }
    }

    private func update(_ state: AppState) {
        let model = state.callPanel
        if let model {
            show(state)
            ring(model.incoming)
            if visibleID != model.id { visibleID = model.id }
        } else {
            hide()
            ring(false)
            visibleID = nil
        }
    }

    private func show(_ state: AppState) {
        let panel = self.panel ?? makePanel(state)
        guard let host else { return }
        let size = host.fittingSize
        let screen = NSScreen.main ?? NSScreen.screens.first
        guard let visible = screen?.visibleFrame else { return }
        let target = NSRect(x: visible.maxX - size.width - Self.margin,
                            y: visible.maxY - size.height - Self.margin,
                            width: size.width, height: size.height)
        if panel.isVisible {
            if panel.frame != target { panel.setFrame(target, display: true, animate: true) }
            return
        }
        // Slide in from the right edge.
        panel.setFrame(target.offsetBy(dx: 60, dy: 0), display: false)
        panel.alphaValue = 0
        panel.orderFrontRegardless()
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.35
            ctx.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.9, 0.3, 1)
            panel.animator().alphaValue = 1
            panel.animator().setFrame(target, display: true)
        }
    }

    private func hide() {
        guard let panel, panel.isVisible else { return }
        NSAnimationContext.runAnimationGroup({ ctx in
            ctx.duration = 0.25
            panel.animator().alphaValue = 0
            panel.animator().setFrame(panel.frame.offsetBy(dx: 40, dy: 0), display: true)
        }, completionHandler: {
            MainActor.assumeIsolated {
                // A new call may have arrived during the fade.
                if self.visibleID == nil { panel.orderOut(nil) } else { panel.alphaValue = 1 }
            }
        })
    }

    private func makePanel(_ state: AppState) -> NSPanel {
        let host = PanelHostingView(rootView: AnyView(CallPanelView().environmentObject(state)))
        host.sizingOptions = [.intrinsicContentSize]
        let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 400, height: 160),
                            styleMask: [.nonactivatingPanel, .borderless],
                            backing: .buffered, defer: false)
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.hasShadow = false // SwiftUI draws a softer one
        panel.hidesOnDeactivate = false
        panel.contentView = host
        self.panel = panel
        self.host = host
        return panel
    }

    /// Rings softly on the Mac while a call is incoming.
    private func ring(_ on: Bool) {
        if on, ringTimer == nil {
            NSSound(named: "Submarine")?.play()
            ringTimer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: true) { _ in
                NSSound(named: "Submarine")?.play()
            }
        } else if !on {
            ringTimer?.invalidate()
            ringTimer = nil
        }
    }
}

/// Hosting view for the floating panel. Buttons must react to the very first click (the panel
/// never becomes key), and clicks must never turn into window drags.
final class PanelHostingView: NSHostingView<AnyView> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
    override var mouseDownCanMoveWindow: Bool { false }

    override func mouseDown(with event: NSEvent) {
        panelLog.notice("click in call panel at \(Int(event.locationInWindow.x), privacy: .public),\(Int(event.locationInWindow.y), privacy: .public)")
        super.mouseDown(with: event)
    }
}

private let panelLog = Logger(subsystem: "com.ayphone.mac", category: "bluetooth")

struct CallPanelView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        Group {
            if let model = state.callPanel {
                card(model)
                    .id(model.id)
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
            }
        }
        .padding(16) // room for the shadow inside the transparent window
        .animation(Theme.spring, value: state.callPanel?.buttons)
    }

    private func card(_ m: CallPanelModel) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ZStack {
                    if m.incoming { PulseRings(active: true, color: Theme.avatarRing) }
                    Circle().strokeBorder(Theme.avatarRing.opacity(0.5), lineWidth: 1.5)
                    Avatar(name: m.caller, size: 46)
                    if let icon = m.icon {
                        Image(nsImage: icon)
                            .resizable()
                            .frame(width: 20, height: 20)
                            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                            .overlay(RoundedRectangle(cornerRadius: 5, style: .continuous).strokeBorder(.white.opacity(0.4)))
                            .offset(x: 17, y: 17)
                    }
                }
                .frame(width: 56, height: 56)

                VStack(alignment: .leading, spacing: 2) {
                    Text("\(m.app) · \(m.incoming ? "Incoming call" : "On a call")")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Theme.callLabel)
                    Text(m.caller).font(.title3.weight(.semibold)).lineLimit(1)
                    if !m.detail.isEmpty, m.detail != m.caller {
                        Text(m.detail).font(.callout).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                Button { state.hideCallPanel(m.id) } label: {
                    Image(systemName: "xmark").font(.system(size: 11, weight: .bold))
                        .frame(width: 22, height: 22)
                        .background(Theme.glassFill, in: Circle())
                }
                .buttonStyle(.pressable(1.12))
                .foregroundStyle(.secondary)
                .help("Hide on this Mac (the call keeps ringing on your phone)")
            }

            if !m.buttons.isEmpty {
                HStack(spacing: 8) {
                    // Answer first and End/Decline last, whatever order the call's app uses.
                    ForEach(Array(m.buttons.enumerated()).sorted { $0.element.kind.order < $1.element.kind.order || ($0.element.kind.order == $1.element.kind.order && $0.offset < $1.offset) },
                            id: \.offset) { _, button in
                        CallActionButton(button: button) { state.performCallButton(button) }
                    }
                }
            }
        }
        .padding(16)
        .frame(width: 370)
        .background(VisualEffect().clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous)))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Color.white.opacity(0.14), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.25), radius: 14, y: 4)
    }
}

struct CallActionButton: View {
    let button: CallPanelModel.Button
    let action: () -> Void

    private var symbol: String? {
        let title = button.title.lowercased()
        switch button.kind {
        case .accept: return "phone.fill"
        case .reject: return "phone.down.fill"
        case .neutral:
            if title.contains("speaker") { return "speaker.wave.3.fill" }
            if title.contains("unmute") { return "mic.fill" }
            if title.contains("mute") { return "mic.slash.fill" }
            if title.contains("to mac") { return "laptopcomputer" }
            if title.contains("phone") { return "iphone" }
            return nil
        }
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let symbol { Image(systemName: symbol) }
                Text(button.title).lineLimit(1)
            }
            .font(.callout.weight(.semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .background {
                switch button.kind {
                case .accept: Capsule().fill(LinearGradient(colors: Theme.answerColors, startPoint: .topLeading, endPoint: .bottomTrailing))
                case .reject: Capsule().fill(LinearGradient(colors: Theme.declineColors, startPoint: .topLeading, endPoint: .bottomTrailing))
                case .neutral: Capsule().fill(Theme.glassFill).overlay(Capsule().strokeBorder(Theme.glassEdge))
                }
            }
        }
        .buttonStyle(.pressable(1.04))
    }
}

/// Real behind-window blur (SwiftUI materials blend within the window, which is transparent here).
private struct VisualEffect: NSViewRepresentable {
    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = .hudWindow
        view.blendingMode = .behindWindow
        view.state = .active
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {}
}
