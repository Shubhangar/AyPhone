import AppKit
import UserNotifications

/// What macOS currently allows us to show.
enum NotificationPermission: Equatable {
    case unknown, notDetermined, denied
    /// Granted. `persistent` is true when the user picked the "Alerts" style, which stays on screen.
    case allowed(persistent: Bool)
}

/// Shows phone notifications as native macOS notifications carrying the phone's own
/// buttons (Reply, Mark as read, …). Calls use the floating call panel instead.
@MainActor
final class MacNotifier: NSObject, UNUserNotificationCenterDelegate {
    weak var state: AppState?

    /// Categories are built per set of buttons, since each app offers different ones.
    private var categories: [String: UNNotificationCategory] = [:]

    /// UNUserNotificationCenter crashes when the process isn't a bundled .app
    /// (for example under `swift run`), so everything is a no-op there.
    private var center: UNUserNotificationCenter? {
        Bundle.main.bundleIdentifier == nil ? nil : UNUserNotificationCenter.current()
    }

    func setUp() {
        guard let center else {
            NSLog("AyPhone: not running as an .app bundle; macOS notifications are disabled. Use scripts/build-app.sh.")
            return
        }
        center.delegate = self
        let reveal = UNNotificationAction(identifier: "reveal", title: "Show in Finder", options: [])
        categories["file"] = UNNotificationCategory(identifier: "file", actions: [reveal], intentIdentifiers: [])
        center.setNotificationCategories(Set(categories.values))
        requestPermission()
    }

    func requestPermission() {
        center?.requestAuthorization(options: [.alert, .sound, .badge]) { granted, error in
            if let error { NSLog("AyPhone: notification permission error: \(error.localizedDescription)") }
            NSLog("AyPhone: notification permission granted=\(granted)")
            Task { @MainActor in self.refreshPermission() }
        }
    }

    func refreshPermission() {
        guard let center else {
            state?.notificationPermission = .denied
            return
        }
        center.getNotificationSettings { settings in
            let permission: NotificationPermission
            switch settings.authorizationStatus {
            case .notDetermined: permission = .notDetermined
            case .denied: permission = .denied
            default: permission = .allowed(persistent: settings.alertStyle == .alert)
            }
            Task { @MainActor in self.state?.notificationPermission = permission }
        }
    }

    /// Posts a banner. `replacing` clears that conversation's previous banner first, so a new
    /// message presents again instead of silently updating the old one.
    func show(_ n: PhoneNotification, replacing: Bool = false) {
        guard let center else { return }
        if replacing { center.removeDeliveredNotifications(withIdentifiers: [n.key]) }
        let content = UNMutableNotificationContent()
        content.title = n.title.isEmpty ? n.app : n.title
        content.subtitle = n.title.isEmpty ? "" : n.app
        content.body = n.text
        content.sound = .default
        content.threadIdentifier = n.pkg
        content.categoryIdentifier = category(for: n)
        content.userInfo = ["phone": true]
        if let attachment = iconAttachment(n.icon) { content.attachments = [attachment] }
        let request = UNNotificationRequest(identifier: n.key, content: content, trigger: nil)
        // Wait for any newly registered category to land before posting, so the buttons show.
        center.getNotificationCategories { _ in center.add(request) }
    }

    /// Registers (once) a category with this notification's buttons: Reply, the app's own actions, Dismiss.
    private func category(for n: PhoneNotification) -> String {
        let id = "phone|\(n.canReply ? "r" : "")|" + n.actions.joined(separator: "|")
        if categories[id] == nil {
            var actions: [UNNotificationAction] = []
            if n.canReply {
                actions.append(UNTextInputNotificationAction(identifier: "reply", title: "Reply", options: [],
                                                             textInputButtonTitle: "Send", textInputPlaceholder: "Message"))
            }
            for (i, title) in n.actions.enumerated() where !title.isEmpty {
                actions.append(UNNotificationAction(identifier: "act.\(i)", title: title, options: []))
            }
            actions.append(UNNotificationAction(identifier: "dismiss", title: "Dismiss", options: []))
            // customDismissAction tells us when the banner is cleared on the Mac, so the phone can follow.
            categories[id] = UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [],
                                                    options: [.customDismissAction])
            center?.setNotificationCategories(Set(categories.values))
        }
        return id
    }

    /// macOS always shows the posting app's (AyPhone's) icon on a banner, so the phone app's own
    /// icon (WhatsApp, Gmail…) goes in as an image attachment, shown as the banner's thumbnail.
    private func iconAttachment(_ icon: NSImage?) -> UNNotificationAttachment? {
        guard let icon, let tiff = icon.tiffRepresentation,
              let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) else { return nil }
        // The system moves attachment files into its own store, so each banner needs its own copy.
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("AyPhoneIcons", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(UUID().uuidString + ".png")
        guard (try? png.write(to: url)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "icon", url: url, options: nil)
    }

    func remove(key: String) {
        center?.removeDeliveredNotifications(withIdentifiers: [key])
    }

    func removeAllPhoneNotifications() {
        center?.removeAllDeliveredNotifications()
    }

    func fileReceived(_ url: URL) {
        let content = UNMutableNotificationContent()
        content.title = "File received"
        content.body = url.lastPathComponent
        content.categoryIdentifier = "file"
        content.userInfo = ["path": url.path]
        center?.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let id = response.notification.request.identifier
        let action = response.actionIdentifier
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        let info = response.notification.request.content.userInfo
        let path = info["path"] as? String
        let isPhone = info["phone"] as? Bool ?? false
        Task { @MainActor in
            defer { completionHandler() }
            guard let state = self.state else { return }
            switch action {
            case "reply":
                if let typed, !typed.isEmpty { state.reply(to: id, text: typed) }
            case "dismiss":
                state.dismiss(id)
            case UNNotificationDismissActionIdentifier:
                // Cleared on the Mac (swiped away or "Clear") → clear it on the phone too.
                if isPhone { state.dismiss(id) }
            case "reveal":
                if let path { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)]) }
            case let a where a.hasPrefix("act."):
                if let index = Int(a.dropFirst(4)) { state.runAction(id, index: index) }
            default:
                NSApp.activate(ignoringOtherApps: true)
            }
        }
    }
}
