import AppKit
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "clipboard")

/// Makes ⌘V paste what was last copied on the phone. Android won't tell apps when something is
/// copied, so instead of watching the phone we catch ⌘V on the Mac: while the phone is unlocked,
/// the key press waits a moment while the phone's clipboard is fetched, then goes through.
/// Needs this Mac's Accessibility permission for AyPhone (a Mac setting; nothing on the phone).
@MainActor
final class PasteInterceptor {
    /// Marks key presses AyPhone posts itself, so they aren't caught again.
    nonisolated static let ownEventMarker: Int64 = 0x4179_5056 // "AyPV"

    /// Whether this ⌘V should fetch from the phone first.
    var shouldFetch: () -> Bool = { false }
    /// Fetches the phone's clipboard, then calls back (always, also on failure or timeout).
    var fetch: (@escaping () -> Void) -> Void = { $0() }

    private var tap: CFMachPort?
    private var source: CFRunLoopSource?
    /// Waits for the Accessibility permission when it isn't granted yet.
    private var permissionTimer: Timer?

    var isRunning: Bool { tap != nil }

    func start() {
        guard tap == nil else { return }
        guard AXIsProcessTrusted() else {
            if permissionTimer == nil {
                permissionTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated {
                        guard AXIsProcessTrusted() else { return }
                        self?.permissionTimer?.invalidate()
                        self?.permissionTimer = nil
                        self?.start()
                    }
                }
            }
            return
        }
        let mask = CGEventMask(1 << CGEventType.keyDown.rawValue)
        guard let tap = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                          eventsOfInterest: mask, callback: pasteTapCallback,
                                          userInfo: Unmanaged.passUnretained(self).toOpaque()) else {
            log.error("couldn't watch for ⌘V")
            return
        }
        self.tap = tap
        source = CFMachPortCreateRunLoopSource(nil, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)
        log.notice("⌘V fetches the phone's clipboard")
    }

    func stop() {
        permissionTimer?.invalidate()
        permissionTimer = nil
        if let tap { CGEvent.tapEnable(tap: tap, enable: false) }
        if let source { CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes) }
        tap = nil
        source = nil
    }

    fileprivate func reenable() {
        if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
    }

    /// A plain ⌘V key press: hold it back and fetch first, or let it through.
    fileprivate func handlePaste(_ event: CGEvent) -> Unmanaged<CGEvent>? {
        guard shouldFetch() else { return Unmanaged.passUnretained(event) }
        let started = Date()
        fetch {
            log.notice("phone clipboard fetched for ⌘V in \(Int(Date().timeIntervalSince(started) * 1000), privacy: .public) ms")
            Self.pressCommandV()
        }
        return nil
    }

    /// Presses ⌘V in the front app.
    static func pressCommandV() {
        let source = CGEventSource(stateID: .combinedSessionState)
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: 9, keyDown: down)
            event?.flags = .maskCommand
            event?.setIntegerValueField(.eventSourceUserData, value: ownEventMarker)
            event?.post(tap: .cgSessionEventTap)
        }
    }
}

/// Runs on the main run loop (the tap's source is added there).
private let pasteTapCallback: CGEventTapCallBack = { _, type, event, userInfo in
    guard let userInfo else { return Unmanaged.passUnretained(event) }
    let interceptor = Unmanaged<PasteInterceptor>.fromOpaque(userInfo).takeUnretainedValue()
    if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
        MainActor.assumeIsolated { interceptor.reenable() }
        return Unmanaged.passUnretained(event)
    }
    let modifiers = event.flags.intersection([.maskCommand, .maskShift, .maskAlternate, .maskControl])
    guard type == .keyDown,
          event.getIntegerValueField(.keyboardEventKeycode) == 9,
          modifiers == .maskCommand,
          event.getIntegerValueField(.keyboardEventAutorepeat) == 0,
          event.getIntegerValueField(.eventSourceUserData) != PasteInterceptor.ownEventMarker
    else { return Unmanaged.passUnretained(event) }
    return MainActor.assumeIsolated { interceptor.handlePaste(event) }
}
