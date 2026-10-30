import AppKit
import SwiftUI
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "remote")

/// The phone as a remote for this Mac: lock it, make it ring, control its music, and use the
/// phone's screen as a trackpad and keyboard. Pointer and key events need the Accessibility
/// permission (the same one ⌘V paste uses).
@MainActor
enum MacRemote {
    // MARK: Lock

    /// Locks the screen right away, like Control-Command-Q.
    static func lock() {
        // login.framework's SACLockScreenImmediate is what the Lock Screen menu item calls.
        if let handle = dlopen("/System/Library/PrivateFrameworks/login.framework/Versions/Current/login", RTLD_NOW),
           let symbol = dlsym(handle, "SACLockScreenImmediate") {
            typealias Lock = @convention(c) () -> Int32
            _ = unsafeBitCast(symbol, to: Lock.self)()
            log.notice("locked the Mac for the phone")
            return
        }
        // Fallback: sleep the display (locks when "require password" is immediate).
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        p.arguments = ["displaysleepnow"]
        try? p.run()
    }

    // MARK: Find my Mac

    private static var findSound: NSSound?
    private static var findWindow: NSPanel?
    private static var volumeBefore: Int?

    /// Rings loudly until stopped from the phone or on the Mac.
    static func find(_ start: Bool, onStop: @escaping () -> Void) {
        guard start else { stopFinding(); return }
        guard findSound == nil else { return }
        volumeBefore = outputVolume
        setOutputVolume(80)
        MacInfo.setOutputMuted(false)
        let sound = NSSound(named: "Sosumi") ?? NSSound(named: "Glass")
        sound?.loops = true
        sound?.play()
        findSound = sound
        showFindWindow(onStop: onStop)
    }

    static func stopFinding() {
        findSound?.stop()
        findSound = nil
        if let v = volumeBefore { setOutputVolume(v) }
        volumeBefore = nil
        findWindow?.orderOut(nil)
        findWindow = nil
    }

    private static func showFindWindow(onStop: @escaping () -> Void) {
        guard let screen = NSScreen.main else { return }
        let size = NSSize(width: 360, height: 200)
        let panel = NSPanel(contentRect: NSRect(origin: .zero, size: size), styleMask: [.nonactivatingPanel, .borderless],
                            backing: .buffered, defer: false)
        panel.level = .screenSaver
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.backgroundColor = .clear
        panel.isOpaque = false
        panel.contentView = NSHostingView(rootView: FoundItView {
            stopFinding()
            onStop()
        })
        panel.setFrameOrigin(NSPoint(x: screen.frame.midX - size.width / 2, y: screen.frame.midY - size.height / 2))
        panel.orderFrontRegardless()
        findWindow = panel
    }

    // MARK: Volume and music

    static var outputVolume: Int {
        var error: NSDictionary?
        return Int(NSAppleScript(source: "output volume of (get volume settings)")?.executeAndReturnError(&error).int32Value ?? 50)
    }

    static func setOutputVolume(_ value: Int) {
        var error: NSDictionary?
        NSAppleScript(source: "set volume output volume \(max(0, min(100, value)))")?.executeAndReturnError(&error)
    }

    /// Play/pause, next and previous go to whatever is playing (as the keyboard's media keys
    /// do); volume steps the Mac's output.
    static func media(_ action: String) {
        switch action {
        case "playPause": mediaKey(16)
        case "next": mediaKey(17)
        case "previous": mediaKey(18)
        case "volumeUp": setOutputVolume(outputVolume + 8)
        case "volumeDown": setOutputVolume(outputVolume - 8)
        default: break
        }
    }

    /// Presses a hardware media key (NX_KEYTYPE_PLAY is 16, NEXT 17, PREVIOUS 18).
    private static func mediaKey(_ key: Int32) {
        for down in [true, false] {
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = Int((key << 16) | ((down ? 0xA : 0xB) << 8))
            NSEvent.otherEvent(with: .systemDefined, location: .zero, modifierFlags: flags, timestamp: 0,
                               windowNumber: 0, context: nil, subtype: 8, data1: data1, data2: -1)?
                .cgEvent?.post(tap: .cghidEventTap)
        }
    }

    // MARK: Trackpad and keyboard

    private static var dragging = false

    /// Moves the pointer by the finger's movement on the phone (already scaled there).
    static func movePointer(dx: Double, dy: Double) {
        guard AXIsProcessTrusted() else { return }
        let current = CGEvent(source: nil)?.location ?? .zero
        let bounds = NSScreen.screens.reduce(CGRect.null) { $0.union(CGRect(origin: CGPoint(x: $1.frame.minX, y: 0), size: $1.frame.size)) }
        let next = CGPoint(x: min(max(current.x + dx, bounds.minX), bounds.maxX - 1),
                           y: min(max(current.y + dy, bounds.minY), bounds.maxY - 1))
        let type: CGEventType = dragging ? .leftMouseDragged : .mouseMoved
        CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: next, mouseButton: .left)?.post(tap: .cghidEventTap)
    }

    /// "left", "right", "double", or "down"/"up" for dragging.
    static func click(_ kind: String) {
        guard AXIsProcessTrusted() else { return }
        let at = CGEvent(source: nil)?.location ?? .zero
        func post(_ type: CGEventType, _ button: CGMouseButton, clicks: Int64 = 1) {
            let e = CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: at, mouseButton: button)
            e?.setIntegerValueField(.mouseEventClickState, value: clicks)
            e?.post(tap: .cghidEventTap)
        }
        switch kind {
        case "right":
            post(.rightMouseDown, .right)
            post(.rightMouseUp, .right)
        case "double":
            post(.leftMouseDown, .left); post(.leftMouseUp, .left)
            post(.leftMouseDown, .left, clicks: 2); post(.leftMouseUp, .left, clicks: 2)
        case "down":
            dragging = true
            post(.leftMouseDown, .left)
        case "up":
            dragging = false
            post(.leftMouseUp, .left)
        default:
            post(.leftMouseDown, .left)
            post(.leftMouseUp, .left)
        }
    }

    static func scroll(dx: Double, dy: Double) {
        guard AXIsProcessTrusted() else { return }
        CGEvent(scrollWheelEvent2Source: nil, units: .pixel, wheelCount: 2,
                wheel1: Int32(dy.rounded()), wheel2: Int32(dx.rounded()), wheel3: 0)?.post(tap: .cghidEventTap)
    }

    /// Types text as if from the keyboard (any language, emoji included).
    static func type(_ text: String) {
        guard AXIsProcessTrusted() else { return }
        let source = CGEventSource(stateID: .hidSystemState)
        for chunk in text.chunked(20) {
            let units = Array(chunk.utf16)
            for down in [true, false] {
                let e = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: down)
                e?.keyboardSetUnicodeString(stringLength: units.count, unicodeString: units)
                e?.post(tap: .cghidEventTap)
            }
        }
    }

    /// Return, delete, tab, escape and the arrows.
    static func key(_ name: String) {
        guard AXIsProcessTrusted() else { return }
        let codes: [String: CGKeyCode] = ["return": 36, "delete": 51, "tab": 48, "escape": 53, "space": 49,
                                          "left": 123, "right": 124, "down": 125, "up": 126]
        guard let code = codes[name] else { return }
        let source = CGEventSource(stateID: .hidSystemState)
        for down in [true, false] {
            CGEvent(keyboardEventSource: source, virtualKey: code, keyDown: down)?.post(tap: .cghidEventTap)
        }
    }
}

private extension String {
    func chunked(_ size: Int) -> [Substring] {
        var result: [Substring] = []
        var start = startIndex
        while start < endIndex {
            let end = index(start, offsetBy: size, limitedBy: endIndex) ?? endIndex
            result.append(self[start..<end])
            start = end
        }
        return result
    }
}

/// Shown while the phone makes the Mac ring.
private struct FoundItView: View {
    let stop: () -> Void

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "speaker.wave.3.fill")
                .font(.system(size: 34, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .symbolEffect(.variableColor.iterative, options: .repeating)
            Text("Your phone is looking for this Mac").font(.headline)
            Button("Found it") { stop() }
                .buttonStyle(.borderedProminent)
                .tint(Theme.indigo)
                .controlSize(.large)
        }
        .padding(28)
        .frame(width: 360, height: 200)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Color.white.opacity(0.14)))
        .preferredColorScheme(.dark)
    }
}

/// Finds a one-time code in a message: "Your code is 482913", "OTP: 4829", "G-123456"…
enum OneTimeCode {
    private static let keywords = try! NSRegularExpression(
        pattern: "\\b(code|otp|passcode|password|pin|verification|verify|one[- ]time|security|login|2fa|authenticat)",
        options: .caseInsensitive)
    // Digits not glued to other numbers ("7:30", "12/10", "4.99", "50%"), so "G-482913" and
    // "482913." still count.
    private static let code = try! NSRegularExpression(pattern: "(?<!\\d)(?<!\\d[.,:/-])(\\d{3}[- ]\\d{3}|\\d{4,8})(?![\\d%])(?![.,:/-]\\d)")

    static func find(in text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard keywords.firstMatch(in: text, range: range) != nil,
              let match = code.firstMatch(in: text, range: range),
              let r = Range(match.range(at: 1), in: text) else { return nil }
        return text[r].filter(\.isNumber)
    }
}
