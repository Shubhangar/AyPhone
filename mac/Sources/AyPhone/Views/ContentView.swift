import SwiftUI

enum Section: String, CaseIterable, Identifiable {
    case overview = "Overview"
    case notifications = "Notifications"
    case messages = "Messages"
    case files = "Files"
    case apps = "Apps"
    case pairing = "Pairing"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .overview: "iphone.gen3"
        case .notifications: "bell.badge"
        case .messages: "message"
        case .files: "folder"
        case .apps: "square.grid.2x2"
        case .pairing: "qrcode"
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var adb: AdbManager
    @ViewState private var section: Section? = .overview

    var body: some View {
        NavigationSplitView {
            sidebar
                .scrollContentBackground(.hidden)
                .background(LinearGradient(colors: [Theme.nightPurple.opacity(0.85), Theme.nightBlue.opacity(0.8)],
                                           startPoint: .top, endPoint: .bottom))
        } detail: {
            ZStack {
                AuroraBackground()
                Group {
                    switch section ?? .overview {
                    case .overview: OverviewView()
                    case .notifications: NotificationsView()
                    case .messages: MessagesView()
                    case .files: FilesView()
                    case .apps: AppsView()
                    case .pairing: PairingView()
                    }
                }
                .id(section)
                .transition(.opacity.combined(with: .offset(y: 10)))
                // The window has no toolbar here, so pages start right under the top edge
                // (their own padding), not below an empty title bar.
                .ignoresSafeArea(.container, edges: .top)
            }
            .animation(.easeOut(duration: 0.25), value: section)
            .overlay(alignment: .bottom) {
                if let toast = state.toast {
                    ToastView(toast: toast)
                        .padding(.bottom, 24)
                        .transition(.move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.9)))
                        .id(toast.id)
                }
            }
            .overlay(alignment: .top) {
                if let message = adb.message {
                    InfoBanner(text: message) { withAnimation(Theme.spring) { adb.message = nil } }
                        .padding(.top, 14)
                        .padding(.horizontal, 24)
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
            .animation(Theme.spring, value: adb.message)
        }
        .preferredColorScheme(.dark)
        .tint(Theme.cyan)
        .onAppear {
            if !state.connected && UserDefaults.standard.object(forKey: "everConnected") == nil { section = .pairing }
        }
        .onChange(of: state.connected) { _, connected in
            if connected {
                UserDefaults.standard.set(true, forKey: "everConnected")
                if section == .pairing { section = .overview }
            }
        }
    }

    private var sidebar: some View {
        // Rows draw their own violet selection: macOS paints a List's selection in the system
        // accent colour, whatever the app's tint.
        List {
            ForEach(Section.allCases) { s in
                let selected = (section ?? .overview) == s
                Button { section = s } label: {
                    HStack(spacing: 8) {
                        Image(systemName: s.icon)
                            .frame(width: 18)
                            .foregroundStyle(selected ? Color.primary : Color.secondary)
                        Text(s.rawValue)
                        Spacer(minLength: 0)
                        if s == .notifications, !state.notifications.isEmpty {
                            Text("\(state.notifications.count)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                    .background(selected ? Theme.indigo.opacity(0.7) : .clear,
                                in: RoundedRectangle(cornerRadius: 6, style: .continuous))
                }
                .buttonStyle(.plain)
                .listRowInsets(EdgeInsets(top: 1, leading: 0, bottom: 1, trailing: 0))
            }
        }
        .navigationSplitViewColumnWidth(min: 190, ideal: 210)
        .safeAreaInset(edge: .top) {
            HStack(spacing: 8) {
                Image(nsImage: LogoGlyph.connected)
                    .resizable()
                    .renderingMode(.template)
                    .foregroundStyle(.white)
                    .frame(width: 28, height: 28)
                Text("AyPhone").font(.system(size: 17, weight: .bold, design: .rounded))
                Spacer()
            }
            .padding(.horizontal, 18)
            .padding(.top, 8)
            .padding(.bottom, 4)
        }
        .safeAreaInset(edge: .bottom) { ConnectionPill().padding(12) }
    }
}

struct ConnectionPill: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                PulseRings(active: state.connected, color: Theme.connectedDot).frame(width: 10, height: 10)
                Circle()
                    .fill(state.connected ? Theme.connectedDot : Color.secondary.opacity(0.5))
                    .frame(width: 8, height: 8)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(state.connected ? state.phoneName : "Not connected")
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
                    .contentTransition(.numericText())
            }
            Spacer()
        }
        .padding(10)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .animation(Theme.spring, value: state.connected)
    }

    private var subtitle: String {
        guard state.connected else { return "Waiting for phone…" }
        let battery = state.battery.map { "\($0)%" } ?? ""
        return [state.transport, battery].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

struct InfoBanner: View {
    let text: String
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "info.circle.fill").foregroundStyle(Theme.accent)
            Text(text).font(.callout).textSelection(.enabled)
            Spacer(minLength: 8)
            Button(action: onClose) { Image(systemName: "xmark").font(.caption.weight(.bold)) }
                .buttonStyle(.pressable(1.15))
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .frame(maxWidth: 640)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(.white.opacity(0.1)))
        .shadow(color: .black.opacity(0.15), radius: 14, y: 6)
    }
}
