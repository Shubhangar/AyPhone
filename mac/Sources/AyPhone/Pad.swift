import AppKit
import SwiftUI

/// The phone's Pad. Draw: the phone is a drawing tablet for the Mac's main screen (absolute
/// positions, pen pressure). Mark up: strokes drawn on the phone appear as ink floating over the
/// Mac's screen, click-through, until cleared. (Sign goes through insertImage, like a photo.)
@MainActor
enum Pad {
    // MARK: Draw

    private static var down = false

    /// One batch from the phone: `state` is down / move / up, `points` are x, y, pressure triples
    /// in 0…1 of the drawing area.
    static func pointer(state: String, points: [Double]) {
        guard AXIsProcessTrusted(), let screen = NSScreen.screens.first else { return }
        let frame = screen.frame
        for i in stride(from: 0, to: points.count - 2, by: 3) {
            let location = CGPoint(x: frame.minX + points[i] * frame.width, y: frame.minY + points[i + 1] * frame.height)
            let pressure = points[i + 2]
            let type: CGEventType
            switch state {
            case "down" where i == 0: type = .leftMouseDown; down = true
            case "up" where i + 3 >= points.count - 2: type = .leftMouseUp; down = false
            default: type = down ? .leftMouseDragged : .mouseMoved
            }
            let event = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: location, mouseButton: .left)
            event?.setDoubleValueField(.mouseEventPressure, value: type == .leftMouseUp ? 0 : pressure)
            event?.post(tap: .cghidEventTap)
        }
    }

    // MARK: Mark up

    private static var panel: NSPanel?
    private static let ink = InkModel()

    /// `state`: start (new stroke `id`, with `index` colour, `width`, `on` highlighter), add
    /// (`points` x, y in 0…1), remove (strokes with the ids in `text`, comma-separated: undo and
    /// the eraser), clear, done (hide), save (a screenshot with the ink on the Desktop).
    static func ink(_ m: Msg) {
        switch m.state {
        case "start":
            show()
            ink.strokes.append(InkModel.Stroke(id: m.id ?? UUID().uuidString, points: [], color: Color(hex: UInt32(m.index ?? 0xFF4FD8)),
                                               width: m.width.map(CGFloat.init) ?? 5, highlighter: m.on == true))
            add(m.points ?? [])
        case "add":
            add(m.points ?? [])
        case "remove":
            let ids = Set((m.text ?? "").split(separator: ",").map(String.init))
            ink.strokes.removeAll { ids.contains($0.id) }
        case "clear":
            ink.strokes.removeAll()
        case "done":
            ink.strokes.removeAll()
            panel?.orderOut(nil)
        case "save":
            save()
        default:
            break
        }
    }

    private static func add(_ points: [Double]) {
        guard !ink.strokes.isEmpty, let screen = NSScreen.screens.first else { return }
        var stroke = ink.strokes.removeLast()
        for i in stride(from: 0, to: points.count - 1, by: 2) {
            stroke.points.append(CGPoint(x: points[i] * screen.frame.width, y: points[i + 1] * screen.frame.height))
        }
        ink.strokes.append(stroke)
    }

    private static func show() {
        guard let screen = NSScreen.screens.first else { return }
        let panel = self.panel ?? {
            let p = NSPanel(contentRect: .zero, styleMask: [.nonactivatingPanel, .borderless], backing: .buffered, defer: false)
            p.level = .screenSaver
            p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
            p.backgroundColor = .clear
            p.isOpaque = false
            p.hasShadow = false
            p.ignoresMouseEvents = true
            p.contentView = NSHostingView(rootView: InkView(model: ink))
            self.panel = p
            return p
        }()
        panel.setFrame(screen.frame, display: true)
        panel.orderFrontRegardless()
    }

    private static func save() {
        let name = "Mark-up " + DateFormatter.localizedString(from: Date(), dateStyle: .short, timeStyle: .medium)
            .replacingOccurrences(of: "/", with: "-").replacingOccurrences(of: ":", with: ".") + ".png"
        let desktop = FileManager.default.urls(for: .desktopDirectory, in: .userDomainMask)[0].appendingPathComponent(name)
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/sbin/screencapture")
        p.arguments = ["-x", "-m", desktop.path]
        try? p.run()
    }
}

/// The ink over the Mac's screen.
@MainActor
final class InkModel: ObservableObject {
    struct Stroke {
        var id: String
        var points: [CGPoint]
        var color: Color
        var width: CGFloat
        var highlighter: Bool
    }

    @Published var strokes: [Stroke] = []
}

private struct InkView: View {
    @ObservedObject var model: InkModel

    var body: some View {
        Canvas { context, _ in
            for stroke in model.strokes where stroke.points.count > 1 {
                var path = Path()
                path.addLines(stroke.points)
                context.stroke(path, with: .color(stroke.highlighter ? stroke.color.opacity(0.35) : stroke.color),
                               style: StrokeStyle(lineWidth: stroke.highlighter ? stroke.width * 4 : stroke.width,
                                                  lineCap: .round, lineJoin: .round))
            }
        }
        .ignoresSafeArea()
    }
}
