// Renders the logo SVG to a PNG for the macOS app icon. macOS 26+ wants a full-bleed square
// and applies its own rounded mask; pre-rounded, padded icons get put on a grey plate (which
// is what Notification Center showed). So: square corners, edge to edge, no shadow.
// Usage: render-icon <logo.svg> <size> <out.png>
import AppKit

let args = CommandLine.arguments
guard args.count == 4, let size = Int(args[2]) else {
    print("usage: render-icon <logo.svg> <size> <out.png>")
    exit(2)
}
let source = try String(contentsOfFile: args[1], encoding: .utf8)
    .replacingOccurrences(of: #"rx="30""#, with: #"rx="0""#)
guard let logo = NSImage(data: Data(source.utf8)) else {
    print("could not load SVG")
    exit(1)
}

let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                           bytesPerRow: 0, bitsPerPixel: 0)!
let s = CGFloat(size)
let art = NSRect(x: 0, y: 0, width: s, height: s)

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current?.imageInterpolation = .high
logo.draw(in: art)
NSGraphicsContext.restoreGraphicsState()

try rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[3]))
