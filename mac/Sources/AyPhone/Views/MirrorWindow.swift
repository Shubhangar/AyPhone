import AVFoundation
import AppKit
import SwiftUI

/// The standalone mirror window: the phone's screen plus a slim controls bar.
@MainActor
final class MirrorWindowController: NSObject, NSWindowDelegate {
    private static let barHeight: CGFloat = 48
    private let window: NSWindow
    private weak var session: MirrorSession?

    init(session: MirrorSession) {
        self.session = session
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 380, height: 820),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        super.init()
        window.titlebarAppearsTransparent = true
        window.isReleasedWhenClosed = false
        window.backgroundColor = .black
        window.contentMinSize = NSSize(width: 240, height: 320)
        window.contentView = NSHostingView(rootView: MirrorWindowView().environmentObject(session))
        window.delegate = self
        window.center()
    }

    func show(title: String) {
        window.title = title
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    /// Resizes to the phone's shape (portrait or landscape) keeping the window's center.
    func fit(to video: CGSize) {
        guard video.width > 0, video.height > 0, let screen = window.screen ?? NSScreen.main else { return }
        let limit = screen.visibleFrame.size
        let portrait = video.height >= video.width
        var height = portrait ? min(820, limit.height * 0.85) : min(560, limit.height * 0.7)
        var width = height * video.width / video.height
        if width > limit.width * 0.85 {
            width = limit.width * 0.85
            height = width * video.height / video.width
        }
        let content = NSRect(x: 0, y: 0, width: width, height: height + Self.barHeight)
        var frame = window.frameRect(forContentRect: content)
        frame.origin = NSPoint(x: window.frame.midX - frame.width / 2, y: window.frame.midY - frame.height / 2)
        window.setFrame(frame, display: true, animate: window.isVisible)
    }

    func windowWillClose(_ notification: Notification) {
        session?.windowClosed()
    }
}

struct MirrorWindowView: View {
    @EnvironmentObject private var mirror: MirrorSession

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color.black
                MirrorVideoSurface(session: mirror)
                if !mirror.hasPicture || mirror.phase != .running {
                    overlay
                        .transition(.opacity)
                }
            }
            .animation(Theme.spring, value: mirror.hasPicture)
            controls
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder
    private var overlay: some View {
        VStack(spacing: 14) {
            switch mirror.phase {
            case .requesting, .running:
                ProgressView().controlSize(.large)
                Text("Tap “Start now” on your phone").font(.headline)
                Text("Android asks each time before sharing your screen.")
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            case .denied:
                Image(systemName: "hand.raised.fill").font(.system(size: 34)).foregroundStyle(Theme.orange)
                Text("Sharing was declined on the phone").font(.headline)
                Button("Try again") { mirror.start() }.buttonStyle(.borderedProminent)
            case .failed(let message):
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 34)).foregroundStyle(Theme.orange)
                Text(message).font(.headline).multilineTextAlignment(.center)
                Button("Try again") { mirror.start() }.buttonStyle(.borderedProminent)
            case .idle:
                Image(systemName: "rectangle.on.rectangle").font(.system(size: 34)).foregroundStyle(Theme.accent)
                Text("Mirroring stopped").font(.headline)
                Button("Start again") { mirror.start() }.buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(maxWidth: 300)
    }

    private var controls: some View {
        HStack(spacing: 18) {
            navButton("chevron.backward", help: "Back (or right-click, or Esc)") { mirror.key("back") }
            navButton("circle", help: "Home") { mirror.key("home") }
            navButton("square.on.square", help: "Recent apps") { mirror.key("recents") }
            Spacer(minLength: 4)
            Text(mirror.controlAvailable ? "Click · drag · scroll · type" : "View only · turn on Control in the phone app")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
            Spacer(minLength: 4)
            Button(mirror.isActive ? "Stop" : "Start") { mirror.isActive ? mirror.stop() : mirror.start() }
                .buttonStyle(.pressable)
                .foregroundStyle(mirror.isActive ? Theme.red : Theme.green)
        }
        .padding(.horizontal, 16)
        .frame(height: 48)
        .background(.ultraThinMaterial)
    }

    private func navButton(_ symbol: String, help: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.system(size: 15, weight: .semibold)).frame(width: 26, height: 26)
        }
        .buttonStyle(.pressable(1.15))
        .help(help)
        .disabled(!mirror.controlAvailable)
    }
}

/// Hosts the hardware video layer and turns mouse and keyboard input into phone gestures.
struct MirrorVideoSurface: NSViewRepresentable {
    let session: MirrorSession

    func makeNSView(context: Context) -> MirrorVideoView {
        let view = MirrorVideoView()
        view.session = session
        view.attach(session.displayLayer)
        return view
    }

    func updateNSView(_ nsView: MirrorVideoView, context: Context) {}
}

final class MirrorVideoView: NSView {
    weak var session: MirrorSession?
    private var downPoint: CGPoint?
    private var downTime = Date()
    private var scrollDelta: CGFloat = 0
    private var scrollFlush: DispatchWorkItem?

    override init(frame: NSRect) {
        super.init(frame: frame)
        wantsLayer = true
        layer = CALayer()
        layer?.backgroundColor = NSColor.black.cgColor
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

    func attach(_ displayLayer: AVSampleBufferDisplayLayer) {
        displayLayer.videoGravity = .resizeAspect
        displayLayer.frame = bounds
        layer?.addSublayer(displayLayer)
    }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer?.sublayers?.forEach { $0.frame = bounds }
        CATransaction.commit()
    }

    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    /// Where the phone's screen actually sits inside the (letterboxed) view.
    private var videoRect: CGRect {
        AVMakeRect(aspectRatio: session?.videoSize ?? CGSize(width: 9, height: 19.5), insideRect: bounds)
    }

    private func normalized(_ point: CGPoint) -> CGPoint? {
        let r = videoRect
        guard r.width > 0, r.height > 0, r.insetBy(dx: -1, dy: -1).contains(point) else { return nil }
        let x = min(max((point.x - r.minX) / r.width, 0), 1)
        let y = min(max(1 - (point.y - r.minY) / r.height, 0), 1) // AppKit's y runs bottom-up
        return CGPoint(x: x, y: y)
    }

    private func location(_ event: NSEvent) -> CGPoint { convert(event.locationInWindow, from: nil) }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        downPoint = location(event)
        downTime = Date()
    }

    override func mouseUp(with event: NSEvent) {
        guard let session, let start = downPoint, let a = normalized(start) else { return }
        downPoint = nil
        let end = location(event)
        let elapsed = Date().timeIntervalSince(downTime)
        if hypot(end.x - start.x, end.y - start.y) < 6 {
            session.tap(x: a.x, y: a.y, long: elapsed > 0.5)
        } else if let b = normalized(end) ?? normalized(clampedToVideo(end)) {
            session.swipe(from: a, to: b, durationMs: Int(min(max(elapsed, 0.1), 1.5) * 1000))
        }
    }

    private func clampedToVideo(_ p: CGPoint) -> CGPoint {
        let r = videoRect
        return CGPoint(x: min(max(p.x, r.minX), r.maxX), y: min(max(p.y, r.minY), r.maxY))
    }

    override func rightMouseDown(with event: NSEvent) {
        session?.key("back")
    }

    /// Trackpad/mouse scrolling becomes a swipe, batched so it's not one gesture per tick.
    override func scrollWheel(with event: NSEvent) {
        scrollDelta += event.hasPreciseScrollingDeltas ? event.scrollingDeltaY : event.scrollingDeltaY * 12
        scrollFlush?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.flushScroll() }
        scrollFlush = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.12, execute: work)
    }

    private func flushScroll() {
        guard let session, scrollDelta != 0, videoRect.height > 0 else { return }
        let travel = min(max(scrollDelta / videoRect.height * 1.6, -0.6), 0.6)
        scrollDelta = 0
        let from = CGPoint(x: 0.5, y: 0.5 - travel / 2)
        let to = CGPoint(x: 0.5, y: 0.5 + travel / 2)
        session.swipe(from: from, to: to, durationMs: 180)
    }

    override func keyDown(with event: NSEvent) {
        guard let session else { return }
        if event.modifierFlags.contains(.command) {
            if event.charactersIgnoringModifiers == "v", let text = NSPasteboard.general.string(forType: .string) {
                session.type(text)
            }
            return
        }
        switch event.keyCode {
        case 51: session.type("\u{8}")      // delete
        case 36, 76: session.type("\n")     // return, enter
        case 53: session.key("back")        // escape
        default:
            if let chars = event.characters, !chars.isEmpty,
               chars.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) {
                session.type(chars)
            }
        }
    }
}
