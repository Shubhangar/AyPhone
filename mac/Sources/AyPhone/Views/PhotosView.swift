import SwiftUI

/// The phone's latest photos and screenshots. Click one to bring the full picture over; it
/// lands in Downloads/AyPhone and shows in Files, ready to drag anywhere.
struct PhotosView: View {
    @EnvironmentObject private var state: AppState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                PageHeader(title: "Photos", subtitle: "The latest from your phone · click one to bring it here") {
                    Button { state.requestPhotos() } label: { Image(systemName: "arrow.clockwise") }
                        .buttonStyle(.pressable(1.15))
                        .help("Refresh")
                }
                Glass(radius: 18) {
                    Toggle(isOn: Binding(get: { state.screenshotSync }, set: { state.setScreenshotSync($0) })) {
                        Label("Send new phone screenshots to this Mac", systemImage: "camera.viewfinder")
                    }
                    .toggleStyle(.row)
                    .disabled(!state.connected)
                }
                if !state.connected {
                    EmptyState(symbol: "photo.on.rectangle", title: "Phone not connected", message: "Your latest photos show here once it connects.")
                        .frame(height: 300)
                } else if state.photosAllowed == false {
                    EmptyState(symbol: "lock.fill", title: "Allow photos on your phone",
                               message: "In AyPhone on your phone, open Setup and allow Messages, contacts, calls, photos & sound.")
                        .frame(height: 300)
                } else if state.photos.isEmpty {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 200)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 12)], spacing: 12) {
                        ForEach(state.photos) { photo in
                            PhotoTile(photo: photo, thumb: state.photoThumbs[photo.id], fetching: state.fetchingPhotos.contains(photo.id)) {
                                state.getPhoto(photo)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
        }
        .onAppear { state.requestPhotos() }
    }
}

private struct PhotoTile: View {
    let photo: PhonePhoto
    let thumb: NSImage?
    let fetching: Bool
    let action: () -> Void
    @ViewState private var hovering = false

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .bottomLeading) {
                Group {
                    if let thumb { Image(nsImage: thumb).resizable().scaledToFill() } else { Color.white.opacity(0.06) }
                }
                .frame(minWidth: 0, maxWidth: .infinity)
                .frame(height: 130)
                .clipped()
                .scaleEffect(hovering ? 1.06 : 1)
                if photo.screenshot == true {
                    Image(systemName: "camera.viewfinder")
                        .font(.caption.weight(.semibold))
                        .padding(5)
                        .background(.ultraThinMaterial, in: Circle())
                        .padding(6)
                }
                if fetching {
                    ZStack { Color.black.opacity(0.45); ProgressView().controlSize(.small) }
                } else if hovering {
                    ZStack {
                        Color.black.opacity(0.35)
                        Image(systemName: "arrow.down.circle.fill").font(.system(size: 28)).foregroundStyle(.white)
                            .transition(.scale.combined(with: .opacity))
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Color.white.opacity(hovering ? 0.3 : 0.1)))
        }
        .buttonStyle(.pressable(1.03))
        .onHover { inside in withAnimation(.easeOut(duration: 0.18)) { hovering = inside } }
        .help("\(photo.name) · click to bring it to this Mac")
    }
}
