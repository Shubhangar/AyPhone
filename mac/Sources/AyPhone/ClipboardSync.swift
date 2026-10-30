import AppKit

/// Watches the Mac pasteboard (macOS has no change notification, so we poll
/// `changeCount`) and applies text and images that arrive from the phone.
@MainActor
final class ClipboardSync {
    var onLocalChange: (String) -> Void = { _ in }
    /// An image copied on the Mac (a screenshot, a picture from a web page): PNG or JPEG data.
    var onLocalImage: (Data, String) -> Void = { _, _ in }
    /// A copy that's never sent to the phone: what password managers mark, and copied files.
    var onPrivateChange: () -> Void = {}

    private var lastChangeCount = NSPasteboard.general.changeCount
    private var timer: Timer?
    /// The text both devices last had: sent from here, or applied from the phone. The phone
    /// answering ⌘V with it again has nothing new.
    private var shared: String?
    /// The same for images (a hash of the data).
    private var sharedImage: Int?

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
        } else if !types.contains(.fileURL), let (data, mime) = Self.imageData(pb) {
            // (Copied files carry their icon as an image; those aren't pictures to paste.)
            shared = nil
            sharedImage = data.hashValue
            onLocalImage(data, mime)
        } else {
            onPrivateChange()
        }
    }

    /// Puts an image from the phone on the pasteboard; with [onlyIfNew], not one the phone got from here.
    @discardableResult
    func setImageFromPhone(_ data: Data, onlyIfNew: Bool = false) -> Bool {
        if onlyIfNew && data.hashValue == sharedImage { return false }
        guard let image = NSImage(data: data) else { return false }
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.writeObjects([image])
        lastChangeCount = pb.changeCount
        shared = nil
        sharedImage = data.hashValue
        return true
    }

    /// The pasteboard's image as PNG (or JPEG when that would be too big to send), at most
    /// 3000 px on its longest side.
    private static func imageData(_ pb: NSPasteboard) -> (Data, String)? {
        guard let image = NSImage(pasteboard: pb),
              var rep = image.tiffRepresentation.flatMap(NSBitmapImageRep.init(data:)) else { return nil }
        let longest = max(rep.pixelsWide, rep.pixelsHigh)
        if longest > 3000 {
            let scale = 3000 / Double(longest)
            let size = NSSize(width: Double(rep.pixelsWide) * scale, height: Double(rep.pixelsHigh) * scale)
            guard let smaller = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height),
                                                 bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                                 colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: smaller)
            rep.draw(in: NSRect(origin: .zero, size: size))
            NSGraphicsContext.restoreGraphicsState()
            rep = smaller
        }
        if let png = rep.representation(using: .png, properties: [:]), png.count <= 6 * 1024 * 1024 { return (png, "image/png") }
        return rep.representation(using: .jpeg, properties: [.compressionFactor: 0.85]).map { ($0, "image/jpeg") }
    }
}
