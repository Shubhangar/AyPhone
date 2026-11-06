import AppKit
import Foundation

/// One button on the phone's Deck. `icon` is a name the phone maps to a Material icon (see
/// DeckActivity.kt); `action` is what the Mac does when it's pressed:
/// - `keys:cmd+shift+a`: a keyboard shortcut, pressed in the app in front
/// - `media:playPause` / `media:next` / `media:previous`, `volume:up` / `volume:down`
/// - `mac:lock`
/// `section` groups buttons under a heading; `style` is `primary` (one large main button) or
/// `danger` (large and red, like Leave). `hint` is the shortcut as macOS writes it (⌥⌘K).
struct DeckButton: Codable, Hashable {
    var id: String
    var label: String
    var icon: String
    var action: String
    var section: String?
    var style: String?
    var hint: String?
}

/// The Deck: buttons for the app in front on the Mac, sent to the phone as it changes, and
/// pressed here when tapped there. Shortcuts are the apps' own (US keyboard positions).
@MainActor
enum Deck {
    private static func b(_ label: String, _ icon: String, _ action: String, style: String? = nil) -> DeckButton {
        DeckButton(id: label.lowercased().replacingOccurrences(of: " ", with: "-"), label: label, icon: icon,
                   action: action, style: style, hint: hint(for: action))
    }

    /// A group of buttons under a heading (nil: no heading).
    private static func section(_ name: String?, _ buttons: [DeckButton]) -> [DeckButton] {
        buttons.map { var b = $0; b.section = name; return b }
    }

    /// Layouts by bundle identifier.
    static let layouts: [String: [DeckButton]] = [
        "com.figma.Desktop":
            section("Create", [b("Frame", "frame", "keys:f"), b("Rectangle", "rectangle", "keys:r"), b("Text", "text", "keys:t"),
                               b("Pen", "pen", "keys:p"), b("Comment", "comment", "keys:c"), b("Duplicate", "duplicate", "keys:cmd+d")])
            + section("Arrange", [b("Auto layout", "autolayout", "keys:shift+a"), b("Component", "component", "keys:cmd+opt+k"),
                                  b("Group", "group", "keys:cmd+g"), b("Ungroup", "ungroup", "keys:cmd+shift+g"),
                                  b("Undo", "undo", "keys:cmd+z"), b("Redo", "redo", "keys:cmd+shift+z")])
            + section("View", [b("Zoom out", "zoomout", "keys:cmd+-"), b("Fit", "fit", "keys:shift+1"), b("Zoom in", "zoomin", "keys:cmd+="),
                               b("Hide UI", "hideui", "keys:cmd+\\"), b("Export", "export", "keys:cmd+shift+e")])
            + section(nil, [b("Present", "present", "keys:cmd+opt+return", style: "primary")]),
        "us.zoom.xos":
            section(nil, [b("Mute", "mic", "keys:cmd+shift+a", style: "primary")])
            + section("Meeting", [b("Camera", "camera", "keys:cmd+shift+v"), b("Share", "share", "keys:cmd+shift+s"),
                                  b("Raise hand", "hand", "keys:opt+y"), b("Chat", "chat", "keys:cmd+shift+h"),
                                  b("People", "people", "keys:cmd+u")])
            + section(nil, [b("Leave meeting", "leave", "keys:cmd+w", style: "danger")]),
        "com.microsoft.teams2":
            section(nil, [b("Mute", "mic", "keys:cmd+shift+m", style: "primary")])
            + section("Meeting", [b("Camera", "camera", "keys:cmd+shift+o"), b("Share", "share", "keys:cmd+shift+e"),
                                  b("Raise hand", "hand", "keys:cmd+shift+k"), b("Chat", "chat", "keys:cmd+2"),
                                  b("Calendar", "calendar", "keys:cmd+4")])
            + section(nil, [b("Leave meeting", "leave", "keys:cmd+shift+h", style: "danger")]),
        "com.tinyspeck.slackmacgap":
            section("Go to", [b("Jump to", "search", "keys:cmd+k"), b("Unreads", "inbox", "keys:cmd+shift+a"),
                              b("Threads", "threads", "keys:cmd+shift+t"), b("DMs", "chat", "keys:cmd+shift+k"),
                              b("Mentions", "mention", "keys:cmd+shift+m"), b("Huddle", "headset", "keys:cmd+shift+h")])
            + section("History", [b("Back", "back", "keys:cmd+["), b("Forward", "forward", "keys:cmd+]"),
                                  b("Mark read", "done", "keys:shift+escape")]),
        "com.google.Chrome": browser,
        "com.apple.Safari": browser,
        "company.thebrowser.Browser": browser,
        "com.spotify.client":
            section(nil, [b("Play", "playpause", "media:playPause", style: "primary")])
            + section("Playback", [b("Previous", "previous", "media:previous"), b("Like", "like", "keys:opt+shift+b"),
                                   b("Next", "next", "media:next"), b("Shuffle", "shuffle", "keys:cmd+s"),
                                   b("Repeat", "repeat", "keys:cmd+r"), b("Search", "search", "keys:cmd+l")])
            + section("Volume", [b("Volume down", "volumedown", "volume:down"), b("Volume up", "volumeup", "volume:up")]),
        "com.apple.Music":
            section(nil, [b("Play", "playpause", "media:playPause", style: "primary")])
            + section("Playback", [b("Previous", "previous", "media:previous"), b("Search", "search", "keys:cmd+f"),
                                   b("Next", "next", "media:next")])
            + section("Volume", [b("Volume down", "volumedown", "volume:down"), b("Volume up", "volumeup", "volume:up")]),
        "com.microsoft.VSCode":
            section("Go to", [b("Commands", "command", "keys:cmd+shift+p"), b("Open file", "file", "keys:cmd+p"),
                              b("Find", "search", "keys:cmd+shift+f")])
            + section("Edit", [b("Comment", "comment", "keys:cmd+/"), b("Format", "format", "keys:shift+opt+f"),
                               b("Undo", "undo", "keys:cmd+z")])
            + section("Layout", [b("Sidebar", "sidebar", "keys:cmd+b"), b("Terminal", "terminal", "keys:ctrl+`"),
                                 b("Split", "split", "keys:cmd+\\")])
            + section(nil, [b("Run", "play", "keys:f5", style: "primary")]),
        "com.apple.iWork.Keynote":
            section(nil, [b("Play", "present", "keys:cmd+opt+p", style: "primary")])
            + section("Slides", [b("Previous", "back", "keys:left"), b("Notes", "notes", "keys:s"), b("Next", "forward", "keys:right"),
                                 b("New slide", "add", "keys:cmd+shift+n")])
            + section(nil, [b("End show", "leave", "keys:escape", style: "danger")]),
    ]

    private static let browser: [DeckButton] =
        section("Tabs", [b("New tab", "add", "keys:cmd+t"), b("Close tab", "close", "keys:cmd+w"), b("Reopen", "undo", "keys:cmd+shift+t"),
                         b("Previous tab", "previous", "keys:ctrl+shift+tab"), b("Address", "search", "keys:cmd+l"),
                         b("Next tab", "next", "keys:ctrl+tab")])
        + section("Page", [b("Back", "back", "keys:cmd+["), b("Reload", "reload", "keys:cmd+r"), b("Forward", "forward", "keys:cmd+]")])

    /// For apps without their own layout.
    static let general: [DeckButton] =
        section("Mac", [b("Mission Control", "windows", "keys:ctrl+up"), b("Switch app", "switch", "keys:cmd+tab"),
                        b("Spotlight", "search", "keys:cmd+space"), b("Screenshot", "screenshot", "keys:cmd+shift+5"),
                        b("Lock", "lock", "mac:lock")])
        + section("Media", [b("Previous", "previous", "media:previous"), b("Play", "playpause", "media:playPause"),
                            b("Next", "next", "media:next"), b("Volume down", "volumedown", "volume:down"),
                            b("Volume up", "volumeup", "volume:up")])

    /// "cmd+opt+k" → "⌥⌘K", as macOS writes shortcuts (modifiers in its order).
    static func hint(for action: String) -> String? {
        guard action.hasPrefix("keys:") else { return nil }
        let parts = action.dropFirst(5).lowercased().split(separator: "+").map(String.init)
        guard let key = parts.last else { return nil }
        let symbols: [(String, String)] = [("ctrl", "⌃"), ("opt", "⌥"), ("shift", "⇧"), ("cmd", "⌘")]
        let mods = symbols.filter { parts.dropLast().contains($0.0) }.map(\.1).joined()
        let names = ["return": "↩", "escape": "esc", "left": "←", "right": "→", "up": "↑", "down": "↓",
                     "tab": "⇥", "space": "Space", "f5": "F5", "-": "−"]
        return mods + (names[key] ?? key.uppercased())
    }

    /// The app's icon (96 px PNG) and its main colour, for the Deck's header and tint.
    static func look(of app: NSRunningApplication?) -> (icon: String?, color: String?) {
        guard let image = app?.icon else { return (nil, nil) }
        let size = 96
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                                         samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                         bytesPerRow: 0, bitsPerPixel: 0) else { return (nil, nil) }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        image.draw(in: NSRect(x: 0, y: 0, width: size, height: size))
        NSGraphicsContext.restoreGraphicsState()
        let png = rep.representation(using: .png, properties: [:])?.base64EncodedString()
        // The most colourful pixels, averaged: Figma's purple-red, Zoom's blue, Spotify's green.
        var r = 0.0, g = 0.0, b = 0.0, weight = 0.0
        for y in stride(from: 0, to: size, by: 3) {
            for x in stride(from: 0, to: size, by: 3) {
                guard let c = rep.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB), c.alphaComponent > 0.5 else { continue }
                let w = pow(c.saturationComponent, 3) * c.brightnessComponent
                guard w > 0.05 else { continue }
                r += c.redComponent * w; g += c.greenComponent * w; b += c.blueComponent * w; weight += w
            }
        }
        guard weight > 0 else { return (png, nil) }
        let hex = String(format: "#%02X%02X%02X", Int(r / weight * 255), Int(g / weight * 255), Int(b / weight * 255))
        return (png, hex)
    }

    static func buttons(for bundleID: String?) -> [DeckButton] {
        bundleID.flatMap { layouts[$0] } ?? general
    }

    /// Whether the app has its own layout (the phone offers "Open its Deck" then).
    static func hasLayout(_ bundleID: String?) -> Bool {
        bundleID.map { layouts[$0] != nil } ?? false
    }

    static func perform(_ action: String) {
        let parts = action.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        switch parts[0] {
        case "keys": press(parts[1])
        case "media": MacRemote.media(parts[1])
        case "volume": MacRemote.media(parts[1] == "up" ? "volumeUp" : "volumeDown")
        case "mac" where parts[1] == "lock": MacRemote.lock()
        default: break
        }
    }

    // MARK: Shortcuts

    /// Physical key positions (US layout), as macOS numbers them.
    private static let keyCodes: [String: CGKeyCode] = [
        "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5, "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12, "w": 13,
        "e": 14, "r": 15, "y": 16, "t": 17, "1": 18, "2": 19, "3": 20, "4": 21, "6": 22, "5": 23, "=": 24, "9": 25,
        "7": 26, "-": 27, "8": 28, "0": 29, "]": 30, "o": 31, "u": 32, "[": 33, "i": 34, "p": 35, "l": 37, "j": 38,
        "'": 39, "k": 40, ";": 41, "\\": 42, ",": 43, "/": 44, "n": 45, "m": 46, ".": 47, "`": 50,
        "return": 36, "tab": 48, "space": 49, "delete": 51, "escape": 53,
        "left": 123, "right": 124, "down": 125, "up": 126, "f5": 96,
    ]

    /// Presses a shortcut like "cmd+shift+a" in the app in front (needs Accessibility).
    static func press(_ combo: String) {
        guard AXIsProcessTrusted() else { return }
        let parts = combo.lowercased().split(separator: "+").map(String.init)
        guard let key = parts.last, let code = keyCodes[key] else { return }
        var flags: CGEventFlags = []
        for modifier in parts.dropLast() {
            switch modifier {
            case "cmd": flags.insert(.maskCommand)
            case "shift": flags.insert(.maskShift)
            case "opt": flags.insert(.maskAlternate)
            case "ctrl": flags.insert(.maskControl)
            default: break
            }
        }
        // Arrow keys carry the "function" flag on a real keyboard; Mission Control looks for it.
        if (123...126).contains(code) { flags.insert(.maskSecondaryFn) }
        let source = CGEventSource(stateID: .hidSystemState)
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down)
            event?.flags = flags
            event?.post(tap: .cghidEventTap)
        }
    }
}
