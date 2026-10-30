import AppKit
import SwiftUI
import UniformTypeIdentifiers

struct FilesView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var files: FileTransfers
    @ViewState private var targeted = false
    @ViewState private var dashPhase: CGFloat = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                PageHeader(title: "Files", subtitle: "Drop files here, on the Dock icon, or right-click in Finder → Send to Phone") {
                    if files.transfers.contains(where: { $0.status == .done || $0.status == .failed }) {
                        Button("Clear finished") { withAnimation(Theme.spring) { files.clearFinished() } }
                            .buttonStyle(.pressable)
                            .foregroundStyle(Theme.accent)
                    }
                }
                dropZone
                if !files.transfers.isEmpty {
                    Text("Transfers").font(.title3.weight(.semibold)).padding(.top, 4)
                    LazyVStack(spacing: 10) {
                        ForEach(files.transfers) { t in
                            TransferRow(transfer: t)
                                .transition(.move(edge: .top).combined(with: .opacity))
                        }
                    }
                    .animation(Theme.spring, value: files.transfers.map(\.id))
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
            .frame(maxWidth: 820, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
    }

    private var dropZone: some View {
        VStack(spacing: 14) {
            IconBadge(symbol: targeted ? "arrow.down.doc.fill" : "arrow.up.doc", size: 64)
                .symbolEffect(.bounce, value: targeted)
                .scaleEffect(targeted ? 1.12 : 1)
            Text(state.connected ? (targeted ? "Release to send" : "Drop files to send them to your phone")
                                 : "Connect your phone to send files")
                .font(.title3.weight(.semibold))
                .contentTransition(.opacity)
            Text("Files from your phone land in your Downloads folder. Drag a received file out to put it anywhere.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button("Choose files…", action: pickFiles)
                .buttonStyle(.borderedProminent).tint(Theme.indigo)
                .disabled(!state.connected)
        }
        .padding(30)
        .frame(maxWidth: .infinity, minHeight: 240)
        .glass(24)
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .strokeBorder(style: StrokeStyle(lineWidth: targeted ? 2.5 : 1.5, dash: [9, 7], dashPhase: dashPhase))
                .foregroundStyle(targeted ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.secondary.opacity(0.35)))
        )
        .shadow(color: Theme.cyan.opacity(targeted ? 0.35 : 0), radius: 24)
        .scaleEffect(targeted ? 1.015 : 1)
        .animation(Theme.spring, value: targeted)
        .onChange(of: targeted) { _, isTargeted in
            if isTargeted {
                withAnimation(.linear(duration: 1).repeatForever(autoreverses: false)) { dashPhase = -16 }
            } else {
                withAnimation(.default) { dashPhase = 0 }
            }
        }
        .onDrop(of: [.fileURL], isTargeted: $targeted) { providers in
            guard state.connected else { return false }
            for p in providers {
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    guard let url else { return }
                    Task { @MainActor in state.sendFiles([url], glow: true) }
                }
            }
            return true
        }
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        if panel.runModal() == .OK { state.sendFiles(panel.urls, glow: true) }
    }
}

struct TransferRow: View {
    let transfer: Transfer
    @ViewState private var hovering = false

    var body: some View {
        HStack(spacing: 14) {
            IconBadge(symbol: transfer.direction == .toPhone ? "arrow.up" : "arrow.down",
                      colors: transfer.status == .failed ? [Theme.red, Theme.pink]
                          : (transfer.direction == .toPhone ? Theme.accentColors : [Theme.green, Theme.mint]),
                      size: 34)
            VStack(alignment: .leading, spacing: 5) {
                Text(transfer.name).font(.callout.weight(.medium)).lineLimit(1)
                switch transfer.status {
                case .active:
                    ProgressView(value: transfer.progress)
                        .tint(Theme.cyan)
                        .animation(.smooth, value: transfer.progress)
                case .queued:
                    Text("Waiting…").font(.caption).foregroundStyle(.secondary)
                case .done:
                    Text("\(ByteCountFormatter.string(fromByteCount: transfer.size, countStyle: .file)) · \(transfer.direction == .toPhone ? "Sent to phone" : "In Downloads")")
                        .font(.caption).foregroundStyle(.secondary)
                case .failed:
                    Text("Failed").font(.caption).foregroundStyle(Theme.red)
                }
            }
            Spacer()
            if transfer.status == .done {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.green)
                    .transition(.scale.combined(with: .opacity))
            }
            if transfer.status == .done, transfer.direction == .fromPhone, let url = transfer.localURL {
                Button { NSWorkspace.shared.activateFileViewerSelecting([url]) } label: {
                    Image(systemName: "magnifyingglass")
                }
                .buttonStyle(.pressable(1.15))
                .help("Show in Finder")
            }
        }
        .padding(14)
        .glass(16)
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.white.opacity(hovering ? 0.15 : 0.07)))
        .animation(Theme.spring, value: transfer.status)
        .onHover { hovering = $0 }
        .onDrag {
            // Received files can be dragged straight into Finder or other apps.
            guard transfer.status == .done, let url = transfer.localURL else { return NSItemProvider() }
            return NSItemProvider(object: url as NSURL)
        }
    }
}
