import SwiftUI

struct NotificationsView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                PageHeader(title: "Notifications", subtitle: subtitle) {
                    if !state.notifications.isEmpty {
                        Button("Clear all") { state.clearAllNotifications() }
                            .buttonStyle(.pressable)
                            .foregroundStyle(Theme.accent)
                    }
                }
                NotificationControls()
                if !state.connected {
                    EmptyState(symbol: "iphone.gen3.slash", title: "Phone not connected",
                               message: "Notifications appear here as soon as your phone connects.")
                        .frame(minHeight: 380)
                } else if state.notifications.isEmpty {
                    EmptyState(symbol: "bell.slash", title: "All caught up",
                               message: "New notifications from your phone show up here, and as banners you can reply to.")
                        .frame(minHeight: 380)
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(state.notifications) { n in
                            NotificationCard(notification: n)
                                .transition(.asymmetric(
                                    insertion: .move(edge: .top).combined(with: .opacity),
                                    removal: .move(edge: .trailing).combined(with: .opacity)
                                ))
                        }
                    }
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
            .frame(maxWidth: 780, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    private var subtitle: String {
        let count = state.notifications.count
        return count == 0 ? "Reply right from your Mac" : "\(count) from your phone"
    }
}

/// On/off switches for what reaches the Mac, and the list of muted apps.
struct NotificationControls: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        Glass(padding: 16, radius: 18) {
            VStack(alignment: .leading, spacing: 12) {
                Toggle(isOn: $state.mirrorNotifications) {
                    Label("Show phone notifications on this Mac", systemImage: "bell.badge")
                }
                Toggle(isOn: $state.showCallsOnMac) {
                    Label("Show calls on this Mac", systemImage: "phone")
                }
                Toggle(isOn: $state.muteAppsOnThisMac) {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Skip apps that are also on this Mac", systemImage: "macbook.and.iphone")
                        Text(state.appsAlsoOnThisMac.isEmpty
                             ? "For example Teams or Slack, so you don't get every notification twice."
                             : "Found here: " + state.appsAlsoOnThisMac.map(\.name).joined(separator: ", "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                if !state.mutedApps.isEmpty {
                    Divider().opacity(0.5)
                    Text("Muted on this Mac · click to unmute").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(state.mutedApps.sorted(), id: \.self) { pkg in
                                Button { withAnimation(Theme.spring) { state.setMuted(pkg, false) } } label: {
                                    HStack(spacing: 5) {
                                        Image(systemName: "bell.slash.fill").font(.caption)
                                        Text(state.appNames[pkg] ?? pkg)
                                        Image(systemName: "xmark").font(.caption2.weight(.bold))
                                    }
                                    .font(.callout)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(.thinMaterial, in: Capsule())
                                }
                                .buttonStyle(.pressable)
                            }
                        }
                    }
                }
            }
            .toggleStyle(.row)
        }
        .animation(Theme.spring, value: state.mutedApps)
    }
}

struct NotificationCard: View {
    @EnvironmentObject private var state: AppState
    let notification: PhoneNotification
    @ViewState private var replying = false
    @ViewState private var replyText = ""
    @ViewState private var hovering = false
    @FocusState private var replyFocused: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Group {
                if let icon = notification.icon {
                    Image(nsImage: icon).resizable()
                } else {
                    IconBadge(symbol: "app.badge", size: 38)
                }
            }
            .frame(width: 38, height: 38)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .shadow(color: .black.opacity(0.15), radius: 4, y: 2)

            VStack(alignment: .leading, spacing: 5) {
                HStack {
                    Text(notification.app).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Text(notification.time, style: .relative).font(.caption).foregroundStyle(.tertiary)
                    if state.isMuted(notification.pkg) {
                        Chip(text: "Muted on Mac", symbol: "bell.slash.fill")
                    }
                    Spacer()
                    Button {
                        withAnimation(Theme.spring) { state.setMuted(notification.pkg, !state.mutedApps.contains(notification.pkg)) }
                    } label: {
                        Image(systemName: state.mutedApps.contains(notification.pkg) ? "bell.fill" : "bell.slash.fill").font(.system(size: 13))
                    }
                    .buttonStyle(.pressable(1.15))
                    .foregroundStyle(.secondary)
                    .opacity(hovering ? 1 : 0)
                    .help(state.mutedApps.contains(notification.pkg)
                          ? "Show \(notification.app) notifications on this Mac again"
                          : "Stop showing \(notification.app) notifications on this Mac")
                    Button { state.dismiss(notification.key) } label: {
                        Image(systemName: "xmark.circle.fill").font(.system(size: 15))
                    }
                    .buttonStyle(.pressable(1.15))
                    .foregroundStyle(.secondary)
                    .opacity(hovering ? 1 : 0)
                    .help("Dismiss on phone")
                }
                if !notification.title.isEmpty { Text(notification.title).font(.headline) }
                if !notification.text.isEmpty {
                    Text(notification.text).foregroundStyle(.primary.opacity(0.85)).textSelection(.enabled)
                }

                if notification.canReply || !notification.actions.isEmpty {
                    HStack(spacing: 8) {
                        if notification.canReply {
                            pill(replying ? "Cancel" : "Reply", symbol: "arrowshape.turn.up.left.fill", prominent: !replying) {
                                withAnimation(Theme.spring) { replying.toggle() }
                                replyFocused = replying
                            }
                        }
                        ForEach(Array(notification.actions.enumerated()), id: \.offset) { i, title in
                            pill(title, symbol: nil, prominent: false) { state.runAction(notification.key, index: i) }
                        }
                    }
                    .padding(.top, 4)
                }

                if replying {
                    HStack(spacing: 8) {
                        TextField("Reply to \(notification.title.isEmpty ? notification.app : notification.title)…", text: $replyText)
                            .textFieldStyle(.plain)
                            .focused($replyFocused)
                            .onSubmit(send)
                        Button(action: send) {
                            Image(systemName: "arrow.up.circle.fill").font(.system(size: 24)).foregroundStyle(Theme.accent)
                        }
                        .buttonStyle(.pressable(1.12))
                        .disabled(replyText.trimmed.isEmpty)
                    }
                    .padding(.leading, 14)
                    .padding(.trailing, 6)
                    .padding(.vertical, 6)
                    .background(.thinMaterial, in: Capsule())
                    .overlay(Capsule().strokeBorder(Theme.accent.opacity(0.5)))
                    .padding(.top, 4)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
        .padding(16)
        .glass(18)
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.white.opacity(hovering ? 0.16 : 0.07)))
        .shadow(color: .black.opacity(hovering ? 0.14 : 0.07), radius: hovering ? 16 : 8, y: hovering ? 6 : 3)
        .scaleEffect(hovering ? 1.005 : 1)
        .contextMenu {
            Button(state.mutedApps.contains(notification.pkg)
                   ? "Show \(notification.app) notifications on this Mac"
                   : "Mute \(notification.app) on this Mac") {
                state.setMuted(notification.pkg, !state.mutedApps.contains(notification.pkg))
            }
            Button("Dismiss on phone") { state.dismiss(notification.key) }
        }
        .animation(Theme.spring, value: hovering)
        .onHover { hovering = $0 }
    }

    private func pill(_ title: String, symbol: String?, prominent: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let symbol { Image(systemName: symbol) }
                Text(title)
            }
            .font(.callout.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .foregroundStyle(prominent ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
            .background {
                if prominent { Capsule().fill(Theme.accent) } else { Capsule().fill(.thinMaterial) }
            }
        }
        .buttonStyle(.pressable)
    }

    private func send() {
        let text = replyText.trimmed
        guard !text.isEmpty else { return }
        state.reply(to: notification.key, text: text)
        replyText = ""
        withAnimation(Theme.spring) { replying = false }
    }
}
