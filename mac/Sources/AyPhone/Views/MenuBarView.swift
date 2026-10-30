import SwiftUI

struct MenuBarView: View {
    @EnvironmentObject private var state: AppState
    @ViewState private var panel = PanelSizer()
    @EnvironmentObject private var adb: AdbManager

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header

            if let call = state.call {
                HStack(spacing: 10) {
                    Avatar(name: call.display, size: 30)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(call.state == "ringing" ? "Incoming call" : "On a call")
                            .font(.caption2.weight(.semibold)).foregroundStyle(Theme.callLabel)
                        Text(call.display).font(.callout.weight(.medium)).lineLimit(1)
                    }
                    Spacer()
                    if call.state == "ringing" {
                        RoundButton(symbol: "phone.fill", colors: Theme.answerColors, size: 30) { state.callAction("answer") }
                        RoundButton(symbol: "phone.down.fill", colors: Theme.declineColors, size: 30) { state.callAction("decline") }
                    } else {
                        RoundButton(symbol: state.speakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                                    colors: state.speakerOn ? Theme.accentColors : Theme.neutralColors, size: 30) { state.callAction("speaker") }
                                    .help(state.speakerOn ? "Speaker off" : "Phone speaker")
                        RoundButton(symbol: "mic.slash.fill", colors: state.micMuted ? Theme.accentColors : Theme.neutralColors, size: 30) { state.callAction("mute") }
                        RoundButton(symbol: "phone.down.fill", colors: Theme.declineColors, size: 30) { state.callAction("end") }
                    }
                }
                .padding(10)
                .background(Theme.glassFill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            if let media = state.media {
                HStack(spacing: 10) {
                    Group {
                        if let art = media.art { Image(nsImage: art).resizable().scaledToFill() } else { Theme.accent }
                    }
                    .frame(width: 34, height: 34)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    VStack(alignment: .leading, spacing: 0) {
                        Text(media.title).font(.callout.weight(.medium)).lineLimit(1)
                        if let artist = media.artist { Text(artist).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                    }
                    Spacer()
                    Button { state.mediaAction("previous") } label: { Image(systemName: "backward.fill") }
                        .buttonStyle(.pressable(1.15))
                    Button { state.mediaAction(media.playing ? "pause" : "play") } label: {
                        Image(systemName: media.playing ? "pause.fill" : "play.fill")
                            .contentTransition(.symbolEffect(.replace))
                    }
                    .buttonStyle(.pressable(1.15))
                    Button { state.mediaAction("next") } label: { Image(systemName: "forward.fill") }
                        .buttonStyle(.pressable(1.15))
                }
                .font(.callout.weight(.semibold))
                .padding(10)
                .background(Theme.glassFill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            HStack(spacing: 8) {
                quick("doc.on.clipboard", "Clipboard") { state.sendClipboardNow() }
                quick("arrow.down.doc", "Paste") { state.pasteFromPhone() }
                quick(state.ringing ? "speaker.wave.3.fill" : "speaker.wave.2", "Find") { state.toggleFindPhone() }
                quick("rectangle.on.rectangle", "Mirror") { state.mirrorScreen() }
                if adb.advancedEnabled {
                    quick("camera.fill", "Camera", enabled: true) { state.openCamera() }
                }
            }

            if !state.notifications.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Recent").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    ForEach(state.notifications.prefix(3)) { n in
                        HStack(spacing: 8) {
                            Group {
                                if let icon = n.icon { Image(nsImage: icon).resizable() } else { Theme.accent }
                            }
                            .frame(width: 20, height: 20)
                            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
                            VStack(alignment: .leading, spacing: 0) {
                                Text(n.title.isEmpty ? n.app : n.title).font(.callout.weight(.medium)).lineLimit(1)
                                Text(n.text).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                        }
                    }
                }
            }

            Divider().opacity(0.5)
            switchRow("Notifications", isOn: $state.mirrorNotifications)
            switchRow("Calls", isOn: $state.showCallsOnMac)
            Divider().opacity(0.5)
            HStack {
                Button("Open AyPhone") { state.showMainWindow() }
                    .fontWeight(.medium)
                    .foregroundStyle(Theme.cyan)
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }.foregroundStyle(.secondary)
            }
            .buttonStyle(.pressable)
        }
        .toggleStyle(.switch)
        .tint(Theme.cyan)
        .padding(14)
        .frame(width: 320)
        // The menu bar window keeps the size it first had; pinning the height to the content
        // makes it follow changes (new switches, notifications) instead of clipping the top.
        .fixedSize(horizontal: false, vertical: true)
        .background(GeometryReader { proxy in
            Color.clear.preference(key: PanelHeightKey.self, value: proxy.size.height)
        })
        .background(PanelWindowReader { panel.window = $0 })
        .onPreferenceChange(PanelHeightKey.self) { panel.fit(height: $0) }
        .animation(Theme.spring, value: state.call)
        .animation(Theme.spring, value: state.connected)
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "iphone.gen3").font(.system(size: 22, weight: .light)).foregroundStyle(Theme.indigo)
                .frame(width: 36, height: 36)
                .background(Theme.indigo.opacity(0.15), in: Circle())
            VStack(alignment: .leading, spacing: 1) {
                Text(state.connected ? state.phoneName : "Not connected").font(.headline)
                Text(state.connected ? (state.transport ?? "") : "Waiting for phone…")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if state.connected {
                BatteryRing(level: state.battery, charging: state.charging, size: 40)
            }
        }
    }

    private func switchRow(_ title: String, isOn: Binding<Bool>) -> some View {
        HStack {
            Text(title)
            Spacer()
            Toggle(title, isOn: isOn).labelsHidden()
        }
    }

    private func quick(_ symbol: String, _ title: String, enabled: Bool = false, action: @escaping () -> Void) -> some View {
        QuickButton(symbol: symbol, title: title, action: action)
            .disabled(!(enabled || state.connected))
    }
}

/// A menu bar quick action: brightens under the pointer, and its icon bounces on hover and click.
private struct QuickButton: View {
    let symbol: String
    let title: String
    let action: () -> Void
    @ViewState private var hovering = false
    @ViewState private var bounces = 0

    var body: some View {
        Button {
            bounces += 1
            action()
        } label: {
            VStack(spacing: 5) {
                Image(systemName: symbol).font(.system(size: 15, weight: .semibold))
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.bounce, value: bounces)
                Text(title).font(.caption2)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 9)
            .background(Color.white.opacity(hovering ? 0.14 : 0.09), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(.pressable)
        .onHover { inside in
            withAnimation(.easeOut(duration: 0.15)) { hovering = inside }
            if inside { bounces += 1 }
        }
    }
}

/// Backstop for the menu bar window not resizing itself: grows or shrinks it to the
/// content's height, keeping its top edge under the menu bar.
@MainActor
final class PanelSizer {
    weak var window: NSWindow?

    func fit(height: CGFloat) {
        guard let window, height > 0 else { return }
        var frame = window.frame
        let target = window.frameRect(forContentRect: NSRect(x: 0, y: 0, width: frame.width, height: height)).height
        guard abs(frame.height - target) > 0.5 else { return }
        frame.origin.y += frame.height - target
        frame.size.height = target
        window.setFrame(frame, display: true)
    }
}

private struct PanelHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Finds the NSWindow hosting the menu bar panel.
private struct PanelWindowReader: NSViewRepresentable {
    let found: (NSWindow) -> Void

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        DispatchQueue.main.async { if let window = view.window { found(window) } }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        DispatchQueue.main.async { if let window = nsView.window { found(window) } }
    }
}
