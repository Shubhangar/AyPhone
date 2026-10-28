import AppKit

/// Watches the Mac pasteboard (macOS has no change notification, so we poll
/// `changeCount`) and applies text that arrives from the phone.
@MainActor
final class ClipboardSync {
    var onLocalChange: (String) -> Void = { _ in }
    /// A copy that's never sent to the phone (password managers mark those).
    var onPrivateChange: () -> Void = {}

    private var lastChangeCount = NSPasteboard.general.changeCount
    private var timer: Timer?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.75, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
    }

    func setFromPhone(_ text: String) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        // Don't echo the phone's own text back to it.
        lastChangeCount = pb.changeCount
    }

    private func poll() {
        let pb = NSPasteboard.general
        guard pb.changeCount != lastChangeCount else { return }
        lastChangeCount = pb.changeCount
        // Skip password managers that mark entries as concealed or transient.
        let types = pb.types ?? []
        if types.contains(NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType")) ||
            types.contains(NSPasteboard.PasteboardType("org.nspasteboard.TransientType")) {
            onPrivateChange()
            return
        }
        if let text = pb.string(forType: .string), !text.isEmpty {
            onLocalChange(text)
        }
    }
}
