import SwiftUI

/// "Update available": in the overview (full) and the menu bar (compact). One click downloads,
/// checks and installs the new version, then AyPhone relaunches.
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
                        Text("Update available").font(compact ? .callout.weight(.semibold) : .headline)
                        Text(detail(release, phase)).font(compact ? .caption : .callout).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    if phase == .downloading || phase == .installing {
                        ProgressView().controlSize(.small)
                    } else {
                        Button("Update") { state.updater.install() }.buttonStyle(.glass)
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
        case .idle: compact ? "AyPhone \(release.version)" : "AyPhone \(release.version) is ready. It installs and reopens in a few seconds."
        }
    }
}
