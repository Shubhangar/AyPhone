import AppKit

/// Watches the Mac pasteboard (macOS has no change notification, so we poll
/// `changeCount`) and applies text that arrives from the phone.
@MainActor
final class ClipboardSync {
    var onLocalChange: (String) -> Void = { _ in }
    /// A copy that's never sent to the phone: what password managers mark, and anything that
    /// isn't text (images, files).
    var onPrivateChange: () -> Void = {}

    private var lastChangeCount = NSPasteboard.general.changeCount
    private var timer: Timer?
    /// The text both devices last had: sent from here, or applied from the phone. The phone
    /// answering ⌘V with it again has nothing new.
    private var shared: String?

    func start() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.75, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.poll() }
        }
    }

    /// Puts the phone's text on the pasteboard. With [onlyIfNew], text the phone already had from
    /// this Mac is left alone, so the Mac's own copy (with its formatting, or an image) stays.
    @discardableResult
    func setFromPhone(_ text: String, onlyIfNew: Bool = false) -> Bool {
        if onlyIfNew && text == shared { return false }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        // Don't echo the phone's own text back to it.
        lastChangeCount = pb.changeCount
        shared = text
        return true
    }

    /// Checks for a new copy now (the timer only looks every 0.75 s).
    func poll() {
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
            shared = text
            onLocalChange(text)
        } else {
            onPrivateChange()
        }
    }
}
