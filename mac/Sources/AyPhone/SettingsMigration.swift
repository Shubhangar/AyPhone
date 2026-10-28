import Foundation

/// The Mac app's ID changed from com.droidlink.mac to com.ayphone.mac (a fresh identity, so
/// macOS's Notification Center picks up the current icon). On the first launch under the new
/// ID this copies the pairing key and settings across, so the phone stays paired.
enum SettingsMigration {
    private static let oldDomain = "com.droidlink.mac"
    private static let doneKey = "migratedFromDroidLinkMac"
    private static let keys = [
        "pairingKey", "everConnected", "clipboardSync", "mirrorNotifications", "showCallsOnMac",
        "mutedApps", "appNames", "muteAppsOnThisMac", "bluetoothCalls", "advancedFeatures",
        "wirelessAdb", "wirelessHost", "NSWindow Frame main",
    ]

    static func run() {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: doneKey), let old = UserDefaults(suiteName: oldDomain) else { return }
        for key in keys {
            guard defaults.object(forKey: key) == nil, var value = old.object(forKey: key) else { continue }
            // Drop entries left behind by testing.
            if key == "appNames", let names = value as? [String: String] {
                value = names.filter { !$0.key.hasPrefix("com.test.") }
            }
            if key == "mutedApps", let muted = value as? [String] {
                value = muted.filter { !$0.hasPrefix("com.test.") }
            }
            defaults.set(value, forKey: key)
        }
        defaults.set(true, forKey: doneKey)
    }
}
