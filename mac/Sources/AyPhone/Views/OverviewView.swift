import AppKit
import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var adb: AdbManager
    @ViewState private var urlText = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                hero
                if state.notificationPermission != .allowed(persistent: true) && state.notificationPermission != .unknown {
                    NotificationPermissionCard()
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
                if let call = state.call {
                    CallCard(call: call)
                        .transition(.move(edge: .top).combined(with: .opacity).combined(with: .scale(scale: 0.96)))
                }
                if let media = state.media {
                    MediaCard(media: media)
                        .transition(.opacity.combined(with: .scale(scale: 0.97)))
                }
                Text("Quick actions").font(.title3.weight(.semibold)).padding(.top, 4)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), spacing: 14)], spacing: 14) { tiles }
                linkCard
                preferences
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
            .frame(maxWidth: 920, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: Hero

    private var hero: some View {
        Glass(padding: 26, radius: 28, clipped: true) {
            HStack(spacing: 26) {
                ZStack {
                    PulseRings(active: true, color: state.connected ? Theme.cyan : Theme.indigo)
                    Circle().fill(Theme.accent.opacity(0.14)).frame(width: 92, height: 92)
                    if state.connected {
                        Image(systemName: "iphone.gen3")
                            .font(.system(size: 50, weight: .thin))
                            .foregroundStyle(Theme.accent)
                            .transition(.scale(scale: 0.5).combined(with: .opacity))
                    } else {
                        SearchingGlass()
                            .transition(.scale(scale: 0.5).combined(with: .opacity))
                    }
                }
                .frame(width: 112, height: 112)
                .animation(.spring(response: 0.45, dampingFraction: 0.6), value: state.connected)

                VStack(alignment: .leading, spacing: 8) {
                    Text(state.connected ? state.phoneName : "Waiting for your phone")
                        .font(.system(size: 30, weight: .bold, design: .rounded))
                        .contentTransition(.opacity)
                    Text(state.connected
                         ? (state.deviceModel ?? "Android")
                         : "Open AyPhone on your Android phone. It connects automatically over Wi-Fi or USB.")
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 6) {
                        if state.connected {
                            Chip(text: "Connected", symbol: "checkmark.circle.fill", tint: Theme.connectedTint)
                            if let transport = state.transport {
                                Chip(text: transport, symbol: transport == "USB" ? "cable.connector"
                                     : transport == "Bluetooth" ? "antenna.radiowaves.left.and.right" : "wifi")
                            }
                        } else {
                            Chip(text: "Searching", symbol: "dot.radiowaves.left.and.right")
                        }
                        if !adb.advancedEnabled {
                            EmptyView()
                        } else if adb.hasUSBPhone && state.transport != "USB" {
                            Chip(text: "USB ready", symbol: "cable.connector")
                        } else if adb.usbState == "unauthorized" {
                            Chip(text: "Tap Allow on phone", symbol: "exclamationmark.triangle.fill", tint: Theme.orange)
                        }
                    }
                    .padding(.top, 2)
                }
                Spacer(minLength: 0)
                if state.connected {
                    BatteryRing(level: state.battery, charging: state.charging)
                        .transition(.scale(scale: 0.6).combined(with: .opacity))
                }
            }
        }
    }

    // MARK: Tiles

    @ViewBuilder
    private var tiles: some View {
        ActionTile(title: "Send clipboard", subtitle: "Mac → phone", symbol: "doc.on.clipboard") {
            state.sendClipboardNow()
        }
        .disabled(!state.connected)

        ActionTile(title: "Send files", subtitle: "Or drop on the Dock icon", symbol: "arrow.up.doc",
                   colors: [Theme.cyan, Theme.green]) {
            let panel = NSOpenPanel()
            panel.allowsMultipleSelection = true
            panel.canChooseDirectories = false
            if panel.runModal() == .OK { state.sendFiles(panel.urls, glow: true) }
        }
        .disabled(!state.connected)

        ActionTile(title: state.ringing ? "Stop ringing" : "Find my phone",
                   subtitle: state.ringing ? "Ringing at full volume" : "Rings even on silent",
                   symbol: state.ringing ? "speaker.wave.3.fill" : "speaker.wave.2",
                   colors: [Theme.orange, Theme.pink], active: state.ringing) {
            state.toggleFindPhone()
        }
        .disabled(!state.connected)

        ActionTile(title: "Mirror screen", subtitle: "See and control your phone", symbol: "rectangle.on.rectangle",
                   colors: [Theme.indigo, Theme.pink], active: state.mirror.isActive) {
            state.mirrorScreen()
        }
        .disabled(!state.connected)
        .contextMenu {
            Button("Mirror screen") { state.mirrorScreen() }
            if adb.advancedEnabled {
                Button("Advanced mirroring (debugging)") { state.mirrorScreenAdvanced() }
                    .disabled(adb.scrcpyPath == nil || !adb.hasDevice)
            }
        }

        if adb.advancedEnabled {
            ActionTile(title: "Phone camera", subtitle: adb.scrcpyPath == nil ? "Set up in Pairing" : "Live camera window",
                       symbol: "camera.fill", colors: [Theme.pink, Theme.orange]) {
                state.openCamera()
            }
        }
    }


    // MARK: Link + preferences

    private var linkCard: some View {
        Glass(padding: 14, radius: 18) {
            HStack(spacing: 12) {
                IconBadge(symbol: "safari.fill", colors: [Theme.cyan, Theme.indigo], size: 32)
                TextField("Open a link on your phone…", text: $urlText)
                    .textFieldStyle(.plain)
                    .font(.title3)
                    .onSubmit(sendURL)
                Button("Open on phone", action: sendURL)
                    .buttonStyle(.borderedProminent).tint(Theme.indigo)
                    .disabled(urlText.isEmpty || !state.connected)
            }
        }
    }

    @ViewState private var pasteTrusted = AXIsProcessTrusted()

    private var preferences: some View {
        Glass(radius: 18) {
            VStack(alignment: .leading, spacing: 12) {
                Toggle(isOn: $state.clipboardSyncEnabled) {
                    Label("Sync clipboard with phone", systemImage: "doc.on.clipboard")
                }
                Divider().opacity(0.5)
                Toggle(isOn: $state.pasteFetchEnabled) {
                    Label("⌘V pastes what you copied on the phone", systemImage: "arrow.down.doc")
                }
                .disabled(!state.clipboardSyncEnabled)
                .help(state.clipboardSyncEnabled ? "" : "Turn on Sync clipboard with phone first")
                .onAppear { pasteTrusted = AXIsProcessTrusted() }
                // Coming back from System Settings after allowing it.
                .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
                    pasteTrusted = AXIsProcessTrusted()
                }
                if state.clipboardSyncEnabled && state.pasteFetchEnabled && !pasteTrusted {
                    HStack {
                        Text("Needs AyPhone allowed in Accessibility on this Mac (doesn't affect the phone).")
                            .font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Button("Allow…") {
                            let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
                            pasteTrusted = AXIsProcessTrustedWithOptions(options)
                        }
                    }
                }
                Divider().opacity(0.5)
                Toggle(isOn: $state.mirrorNotifications) {
                    Label("Show phone notifications on this Mac", systemImage: "bell.badge")
                }
                Divider().opacity(0.5)
                Toggle(isOn: $state.showCallsOnMac) {
                    Label("Show calls on this Mac", systemImage: "phone")
                }
            }
            .toggleStyle(.row)
        }
    }

    private func sendURL() {
        var s = urlText.trimmed
        guard !s.isEmpty else { return }
        if !s.contains("://") { s = "https://" + s }
        state.sendURL(s)
        urlText = ""
    }
}

// MARK: - Notification permission

/// Explains what's needed for phone notifications to appear (and stay) in the Mac's corner.
struct NotificationPermissionCard: View {
    @EnvironmentObject private var state: AppState

    private var content: (title: String, message: String, button: String, symbol: String) {
        switch state.notificationPermission {
        case .denied:
            ("Notifications are off for AyPhone",
             "Phone notifications only show inside this window. Turn them on in System Settings.",
             "Open Settings", "bell.slash.fill")
        case .allowed(persistent: false):
            ("Keep notifications on screen",
             "Banners slide away after a few seconds. In System Settings, set AyPhone's style to Alerts so they stay until you act on the phone or Mac.",
             "Open Settings", "bell.badge.fill")
        default:
            ("Show phone notifications on your Mac",
             "Allow notifications so messages and calls appear in the corner of your screen, with Reply and the app's own buttons.",
             "Allow", "bell.badge.fill")
        }
    }

    var body: some View {
        Glass(padding: 16, radius: 18) {
            HStack(spacing: 14) {
                IconBadge(symbol: content.symbol, colors: [Theme.orange, Theme.pink], size: 36)
                VStack(alignment: .leading, spacing: 2) {
                    Text(content.title).font(.headline)
                    Text(content.message).font(.callout).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                Button(content.button) {
                    if case .allowed = state.notificationPermission {
                        state.openNotificationSettings()
                    } else {
                        state.requestNotificationPermission()
                    }
                }
                .buttonStyle(.glass)
            }
        }
    }
}

// MARK: - Call

struct CallCard: View {
    @EnvironmentObject private var state: AppState
    let call: CallInfo
    @ViewState private var glow = false

    var body: some View {
        HStack(spacing: 16) {
            ZStack {
                if call.state == "ringing" { PulseRings(active: true, color: Theme.avatarRing) }
                Avatar(name: call.display, size: 52)
            }
            .frame(width: 64, height: 64)
            VStack(alignment: .leading, spacing: 3) {
                Text(call.state == "ringing" ? "Incoming call" : "On a call")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Theme.callLabel)
                    .textCase(.uppercase)
                Text(call.display).font(.title2.weight(.semibold))
                Text(call.hasWaiting ? "\(call.waitingDisplay) is calling"
                     : state.callPanel?.id == "telephony" ? (state.callPanel?.detail ?? "") : "Audio stays on your phone")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let panel = state.callPanel, panel.id == "telephony" {
                HStack(spacing: 8) {
                    ForEach(Array(panel.buttons.enumerated()), id: \.offset) { _, button in
                        CallActionButton(button: button) { state.performCallButton(button) }
                            .frame(width: 118)
                    }
                }
            } else {
                HStack(spacing: 14) {
                    if call.state == "ringing" {
                        RoundButton(symbol: "phone.down.fill", colors: Theme.declineColors, size: 50) { state.callAction("decline") }
                        RoundButton(symbol: "phone.fill", colors: Theme.answerColors, size: 50) { state.callAction("answer") }
                    } else {
                        RoundButton(symbol: state.speakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                                    colors: state.speakerOn ? Theme.accentColors : Theme.neutralColors, size: 46) { state.callAction("speaker") }
                                    .help(state.speakerOn ? "Speaker off" : "Phone speaker")
                        RoundButton(symbol: "mic.slash.fill", colors: state.micMuted ? Theme.accentColors : Theme.neutralColors, size: 46) { state.callAction("mute") }
                        RoundButton(symbol: "phone.down.fill", colors: Theme.declineColors, size: 50) { state.callAction("end") }
                    }
                }
            }
        }
        .padding(20)
        .glass(22)
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(Theme.indigo.opacity(glow ? 0.7 : 0.25), lineWidth: 1.5)
        )
        .shadow(color: Theme.indigo.opacity(glow ? 0.35 : 0.1), radius: 18)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) { glow = true }
        }
    }
}

// MARK: - Media

struct MediaCard: View {
    @EnvironmentObject private var state: AppState
    let media: MediaInfo

    var body: some View {
        HStack(spacing: 16) {
            Group {
                if let art = media.art {
                    Image(nsImage: art).resizable().scaledToFill()
                } else {
                    ZStack {
                        Theme.accent
                        Image(systemName: "music.note").font(.title).foregroundStyle(.white)
                    }
                }
            }
            .frame(width: 68, height: 68)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
            .scaleEffect(media.playing ? 1 : 0.92)
            .animation(Theme.spring, value: media.playing)

            VStack(alignment: .leading, spacing: 3) {
                Text(media.title).font(.headline).lineLimit(1)
                Text([media.artist, media.app].compactMap { $0 }.joined(separator: " · "))
                    .font(.callout).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            HStack(spacing: 18) {
                mediaButton("speaker.wave.1.fill") { state.mediaAction("volumeDown") }
                mediaButton("backward.fill") { state.mediaAction("previous") }
                RoundButton(symbol: media.playing ? "pause.fill" : "play.fill", size: 48) {
                    state.mediaAction(media.playing ? "pause" : "play")
                }
                .contentTransition(.symbolEffect(.replace))
                mediaButton("forward.fill") { state.mediaAction("next") }
                mediaButton("speaker.wave.3.fill") { state.mediaAction("volumeUp") }
            }
        }
        .padding(18)
        .background {
            ZStack {
                if let art = media.art {
                    Image(nsImage: art).resizable().scaledToFill().blur(radius: 50).opacity(0.55)
                }
                Rectangle().fill(.ultraThinMaterial)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: .black.opacity(0.1), radius: 16, y: 6)
    }

    private func mediaButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        BouncingIconButton(symbol: symbol, action: action)
    }
}
