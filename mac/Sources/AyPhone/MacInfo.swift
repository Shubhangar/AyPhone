import Foundation
import IOKit
import IOKit.ps

/// What the phone shows about this Mac: its model name and battery.
@MainActor
final class MacInfo {
    /// "MacBook Pro", "Mac mini"… from the device tree (falls back to "Mac").
    static let modelName: String = {
        let entry = IORegistryEntryFromPath(kIOMainPortDefault, "IODeviceTree:/product")
        defer { IOObjectRelease(entry) }
        guard entry != 0,
              let data = IORegistryEntryCreateCFProperty(entry, "product-name" as CFString, kCFAllocatorDefault, 0)?
                .takeRetainedValue() as? Data,
              let name = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .controlCharacters.union(.whitespaces)),
              !name.isEmpty
        else { return "Mac" }
        // "MacBook Pro (14-inch, 2025)" → "MacBook Pro"
        return name.components(separatedBy: " (").first ?? name
    }()

    /// The internal battery's level and whether it's charging; nil on Macs without one.
    static var battery: (level: Int, charging: Bool)? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let sources = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return nil }
        for source in sources {
            guard let d = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any],
                  d[kIOPSTypeKey] as? String == kIOPSInternalBatteryType,
                  let current = d[kIOPSCurrentCapacityKey] as? Int,
                  let max = d[kIOPSMaxCapacityKey] as? Int, max > 0 else { continue }
            let charging = (d[kIOPSIsChargingKey] as? Bool ?? false) || d[kIOPSPowerSourceStateKey] as? String == kIOPSACPowerValue && current < max
            return (current * 100 / max, charging)
        }
        return nil
    }

    /// Whether the Mac's sound output is muted.
    static var outputMuted: Bool {
        var error: NSDictionary?
        let result = NSAppleScript(source: "output muted of (get volume settings)")?.executeAndReturnError(&error)
        return result?.booleanValue ?? false
    }

    static func setOutputMuted(_ muted: Bool) {
        var error: NSDictionary?
        NSAppleScript(source: "set volume output muted \(muted)")?.executeAndReturnError(&error)
    }

    private var source: CFRunLoopSource?
    private var onChange: (() -> Void)?

    /// Calls [changed] whenever the battery level or power source changes.
    func watch(_ changed: @escaping () -> Void) {
        onChange = changed
        let context = Unmanaged.passUnretained(self).toOpaque()
        guard let source = IOPSNotificationCreateRunLoopSource({ context in
            guard let context else { return }
            let info = Unmanaged<MacInfo>.fromOpaque(context).takeUnretainedValue()
            MainActor.assumeIsolated { info.onChange?() }
        }, context)?.takeRetainedValue() else { return }
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .defaultMode)
        self.source = source
    }
}
