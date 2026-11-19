import SwiftUI

/// "Update ready" (downloaded in the background) or "Update available": in the overview (full)
/// and the menu bar (compact). One click installs the new version, then AyPhone relaunches.
struct UpdateBanner: View {
    @EnvironmentObject private var state: AppState
    var compact = false

    var body: some View {
        if let release = state.updater.available {
            let phase = state.updater.phase
            Glass(padding: compact ? 10 : 16, radius: compact ? 14 : 18) {
                HStack(spacing: compact ? 10 : 14) {
                    IconBadge(symbol: "arrow.down.circle.fill", colors: [Theme.indigo, Theme.pink], size: compact ? 26 : 36)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(state.updater.ready ? "Update ready" : "Update available").font(compact ? .callout.weight(.semibold) : .headline)
                        Text(detail(release, phase)).font(compact ? .caption : .callout).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    if phase == .downloading || phase == .installing {
                        ProgressView().controlSize(.small)
                    } else {
                        Button(state.updater.ready ? "Install now" : "Update") { state.updater.install() }.buttonStyle(.pill)
                    }
                }
            }
        }
    }

    private func detail(_ release: Updater.Release, _ phase: Updater.Phase) -> String {
        switch phase {
        case .downloading: "Downloading AyPhone \(release.version)…"
        case .installing: "Installing. AyPhone will reopen."
        case .failed(let reason): reason
        case .idle where state.updater.ready:
            compact ? "AyPhone \(release.version)" : "AyPhone \(release.version) is downloaded. It installs and reopens in a few seconds."
        case .idle: compact ? "AyPhone \(release.version)" : "AyPhone \(release.version) is out. It downloads, installs and reopens in a few seconds."
        }
    }
}
