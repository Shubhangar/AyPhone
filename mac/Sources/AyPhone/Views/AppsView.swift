import SwiftUI

struct AppsView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var adb: AdbManager
    @ViewState private var search = ""

    private var filtered: [PhoneApp] {
        let q = search.trimmed.lowercased()
        return q.isEmpty ? state.apps : state.apps.filter { $0.name.lowercased().contains(q) }
    }

    /// Mac windows need scrcpy and a USB cable; otherwise apps open on the phone itself.
    private var canOpenWindows: Bool { adb.advancedEnabled && adb.scrcpyPath != nil && adb.hasDevice }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                PageHeader(title: "Apps", subtitle: canOpenWindows
                           ? "Click to open an app in its own Mac window"
                           : "Click to open an app on your phone") {
                    HStack(spacing: 10) {
                        HStack(spacing: 6) {
                            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                            TextField("Search", text: $search).textFieldStyle(.plain).frame(width: 150)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(.thinMaterial, in: Capsule())
                        Button { state.requestApps() } label: { Image(systemName: "arrow.clockwise") }
                            .buttonStyle(.pressable(1.15))
                            .help("Refresh")
                    }
                }

                if adb.advancedEnabled && !canOpenWindows && state.connected {
                    windowHint
                }

                if !state.connected {
                    EmptyState(symbol: "iphone.gen3.slash", title: "Phone not connected",
                               message: "Your phone's apps appear here once it connects.")
                        .frame(minHeight: 380)
                } else if state.apps.isEmpty && state.transport == "Bluetooth" {
                    EmptyState(symbol: "antenna.radiowaves.left.and.right", title: "Connected over Bluetooth",
                               message: "Your apps load once your phone reaches this Mac over Wi-Fi or USB.")
                        .frame(minHeight: 380)
                } else if state.apps.isEmpty {
                    EmptyState(symbol: "square.grid.2x2", title: "Loading apps…",
                               message: "Fetching the app list from your phone.")
                        .frame(minHeight: 380)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 96, maximum: 120), spacing: 12)], spacing: 18) {
                        ForEach(filtered) { app in
                            AppCell(app: app, icon: state.appIcons[app.pkg]) {
                                if canOpenWindows { state.openInWindow(app) } else { state.launchOnPhone(app) }
                            }
                            .contextMenu {
                                Button("Open on phone") { state.launchOnPhone(app) }
                                if adb.advancedEnabled {
                                    Button("Open in a Mac window") { state.openInWindow(app) }
                                        .disabled(!canOpenWindows)
                                }
                            }
                        }
                    }
                    .animation(Theme.spring, value: filtered.map(\.pkg))
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
            .frame(maxWidth: 980, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    private var windowHint: some View {
        Glass(padding: 14, radius: 16) {
            HStack(spacing: 12) {
                IconBadge(symbol: "macwindow.on.rectangle", colors: [Theme.indigo, Theme.pink], size: 32)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Run phone apps in Mac windows").font(.callout.weight(.semibold))
                    Text(adb.scrcpyPath == nil ? "Install mirroring from the Pairing page."
                                               : "Plug your phone in over USB once; after that this works over Wi-Fi too.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }
}

private struct AppCell: View {
    let app: PhoneApp
    let icon: NSImage?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Group {
                    if let icon {
                        Image(nsImage: icon).resizable()
                    } else {
                        IconBadge(symbol: "app.fill", size: 58)
                    }
                }
                .frame(width: 58, height: 58)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .shadow(color: .black.opacity(0.2), radius: 8, y: 4)
                Text(app.name)
                    .font(.caption)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .frame(height: 30, alignment: .top)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .buttonStyle(.pressable(1.1))
        .help(app.name)
    }
}
