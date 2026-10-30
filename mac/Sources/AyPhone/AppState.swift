import AppKit
import Carbon.HIToolbox
import Combine
import os

private let callLog = Logger(subsystem: "com.ayphone.mac", category: "bluetooth")
import Foundation
import SwiftUI

struct PhoneNotification: Identifiable, Equatable {
    var id: String { key }
    var key: String
    var pkg: String
    var app: String
    var title: String
    var text: String
    var time: Date
    var canReply: Bool
    var actions: [String]
    var icon: NSImage?
    var isCall = false
    var incomingCall = false
}

struct CallInfo: Equatable {
    var state: String
    var number: String?
    var name: String?
    /// A second caller ringing while this call is on (call waiting).
    var waitingNumber: String? = nil
    var waitingName: String? = nil
    var display: String { name ?? number ?? "Unknown caller" }
    var hasWaiting: Bool { waitingNumber != nil || waitingName != nil }
    var waitingDisplay: String { waitingName ?? waitingNumber ?? "Unknown caller" }
}

struct MediaInfo: Equatable {
    var app: String?
    var title: String
    var artist: String?
    var playing: Bool
    var art: NSImage?
}

/// What the floating call panel shows: a call notification from any app, or the
/// phone's own call state when no such notification is available.
struct CallPanelModel: Equatable {
    enum Kind {
        case accept, reject, neutral
        /// Panel order: answer first, end/decline last.
        var order: Int { self == .accept ? 0 : self == .neutral ? 1 : 2 }
    }
    enum Action: Equatable {
        case notification(key: String, index: Int)
        case telephony(String)
        /// Bluetooth hands-free: answerOnMac, moveToMac, moveToPhone, mute, end.
        case bluetooth(String)
    }
    struct Button: Equatable {
        let title: String
        let kind: Kind
        let action: Action
    }

    let id: String
    let caller: String
    let detail: String
    let app: String
    let icon: NSImage?
    let incoming: Bool
    let buttons: [Button]

    static func kind(for title: String) -> Kind {
        let t = title.lowercased()
        if t.contains("answer") || t.contains("accept") || t.contains("pick up") { return .accept }
        if ["decline", "reject", "end", "hang up", "dismiss", "ignore"].contains(where: t.contains) { return .reject }
        return .neutral
    }
}

/// A short-lived pill shown at the bottom of the window ("Clipboard from phone").
struct Toast: Identifiable, Equatable {
    let id = UUID()
    let text: String
    let symbol: String
}

/// Central app model: owns the server and turns wire messages into UI state.
@MainActor
final class AppState: ObservableObject {
    /// Settings are migrated from the app's old ID before anything reads them.
    static let shared: AppState = {
        SettingsMigration.run()
        return AppState()
    }()

    // Connection
    @Published private(set) var connected = false
    @Published private(set) var deviceName: String?
    @Published private(set) var deviceModel: String?
    @Published private(set) var transport: String?
    @Published private(set) var battery: Int?
    @Published private(set) var charging = false
    @Published private(set) var pairingPayload = ""

    // Features
    @Published private(set) var notifications: [PhoneNotification] = []
    @Published private(set) var threads: [SmsThread] = []
    @Published private(set) var messages: [Int64: [SmsMessage]] = [:]
    @Published var selectedThread: Int64?
    @Published private(set) var call: CallInfo?
    /// Whether the current call's sound is on the phone's loudspeaker (the phone reports it).
    @Published private(set) var speakerOn = false
    /// The phone reported the call's sound route, so it can steer this call (any app's).
    private var phoneControlsCallAudio = false
    @Published private(set) var media: MediaInfo?
    @Published private(set) var apps: [PhoneApp] = []
    @Published private(set) var appIcons: [String: NSImage] = [:]
    @Published private(set) var contacts: [PhoneContact] = []
    @Published private(set) var ringing = false
    @Published private(set) var toast: Toast?
    @Published var notificationPermission: NotificationPermission = .unknown
    @Published var showCallsOnMac = UserDefaults.standard.object(forKey: "showCallsOnMac") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showCallsOnMac, forKey: "showCallsOnMac") }
    }
    /// Android packages whose notifications never banner on the Mac.
    @Published private(set) var mutedApps = Set(UserDefaults.standard.stringArray(forKey: "mutedApps") ?? []) {
        didSet { UserDefaults.standard.set(Array(mutedApps), forKey: "mutedApps") }
    }
    /// Remembered app names, so muted apps can be listed even when none of their notifications are showing.
    @Published private(set) var appNames = UserDefaults.standard.dictionary(forKey: "appNames") as? [String: String] ?? [:] {
        didSet { UserDefaults.standard.set(appNames, forKey: "appNames") }
    }
    /// Skip phone notifications from apps that are also installed on this Mac (Teams, Slack…).
    @Published var muteAppsOnThisMac = UserDefaults.standard.bool(forKey: "muteAppsOnThisMac") {
        didSet { UserDefaults.standard.set(muteAppsOnThisMac, forKey: "muteAppsOnThisMac") }
    }
    /// The call the user closed the panel for (it stays hidden until that call ends).
    @Published private var hiddenCallPanelID: String?
    /// Set by the panel's ✕: hide the call panel, whichever kind, until this call is over.
    @Published private var callPanelDismissed = false
    @Published var clipboardSyncEnabled = UserDefaults.standard.object(forKey: "clipboardSync") as? Bool ?? true {
        didSet { UserDefaults.standard.set(clipboardSyncEnabled, forKey: "clipboardSync") }
    }
    @Published var mirrorNotifications = UserDefaults.standard.object(forKey: "mirrorNotifications") as? Bool ?? true {
        didSet { UserDefaults.standard.set(mirrorNotifications, forKey: "mirrorNotifications") }
    }

    let server: LinkRouter
    let files = FileTransfers()
    let adb = AdbManager()
    let bluetooth = BluetoothCallAudio()
    let mirror = MirrorSession()
    private let beacon = Beacon()
    private var bluetoothObserver: AnyCancellable?
    private let clipboard = ClipboardSync()
    private let notifier = MacNotifier()
    private let callPanelController = CallPanelController()
    private var lastFrameAt = Date()
    private var watchdog: Timer?
    /// ⌃⌥V anywhere: Paste from phone.
    private var pasteHotKey: HotKey?
    private let pasteInterceptor = PasteInterceptor()
    /// "unlocked", "locked" or "off", as the phone reports it; nil while disconnected.
    private var phoneScreen: String?
    private var phoneUnlockedAt = Date.distantPast
    /// The last Mac copy the phone didn't get (sync off, disconnected, or a password).
    private var unsyncedMacCopyAt = Date.distantPast
    /// Waiting for the phone's clipboard before letting ⌘V through.
    private var pasteFetchDone: (() -> Void)?
    private var pasteFetchID = 0

    /// ⌘V pastes what was last copied on the phone (needs Accessibility on this Mac).
    @Published var pasteFetchEnabled = UserDefaults.standard.object(forKey: "pasteFetch") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(pasteFetchEnabled, forKey: "pasteFetch")
            pasteFetchEnabled ? pasteInterceptor.start() : pasteInterceptor.stop()
        }
    }

    /// Fetch on ⌘V only while the phone can share its clipboard (unlocked), and not when the
    /// Mac's newest copy never reached the phone: the phone's clipboard would be the older one.
    private var shouldFetchOnPaste: Bool {
        pasteFetchEnabled && connected && clipboardSyncEnabled && phoneScreen == "unlocked"
            && pasteFetchDone == nil && unsyncedMacCopyAt < phoneUnlockedAt
    }

    /// The first time a phone connects, explain ⌘V paste and bring up macOS's Accessibility
    /// request (apps can't switch it on themselves). Asked once; the overview has it after that.
    private func askForPastePermissionOnce() {
        let key = "pastePermissionAsked"
        guard pasteFetchEnabled, clipboardSyncEnabled, !AXIsProcessTrusted(),
              !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        // Let the connection settle and the window draw first.
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            let alert = NSAlert()
            alert.messageText = "Paste what you copy on your phone with ⌘V"
            alert.informativeText = "Copy something on \(self.phoneName), then press ⌘V on this Mac to paste it. "
                + "For that, macOS needs you to allow AyPhone under Accessibility: click Continue, then Open System Settings, "
                + "and switch AyPhone on.\n\nThis is a setting on this Mac only. Nothing changes on your phone."
            alert.addButton(withTitle: "Continue")
            alert.addButton(withTitle: "Not now")
            NSApp.activate(ignoringOtherApps: true)
            guard alert.runModal() == .alertFirstButtonReturn else { return }
            _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
        }
    }

    private func fetchPhoneClipboard(then done: @escaping () -> Void) {
        pasteFetchID += 1
        let id = pasteFetchID
        pasteFetchDone = done
        clipboardRequestedAt = Date()
        server.send(Msg("clipboardRequest"))
        // Never hold ⌘V for long: paste what the Mac has if the phone is slow to answer.
        DispatchQueue.main.asyncAfter(deadline: .now() + (server.isBluetooth ? 1.5 : 1.0)) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.pasteFetchID == id, let done = self.pasteFetchDone else { return }
                self.pasteFetchDone = nil
                done()
            }
        }
    }
    private var toastTask: Task<Void, Never>?
    /// Contact names keyed by the last 9 digits of each number, so +1 555…, 0555… and 555… all match.
    private var nameByNumber: [String: String] = [:]
    /// A caller name from the phone's call notification that arrived before the call state did.
    private var pendingCallerName: (name: String, at: Date)?

    private init() {
        server = LinkRouter(key: Pairing.loadOrCreateKey())
    }

    var phoneName: String { deviceName ?? "Phone" }

    /// Set by the menu bar icon view; opens (or brings forward) the main window.
    var openMainWindow: (() -> Void)?

    func showMainWindow() {
        openMainWindow?()
        NSApp.activate(ignoringOtherApps: true)
    }

    func start() {
        pairingPayload = Pairing.qrPayload(key: server.key, port: LinkServer.port)
        server.onMessage = { [weak self] in self?.handle($0) }
        server.onConnectionChange = { [weak self] in self?.connectionChanged($0) }
        server.start()
        // Helps the phone find the Mac on networks that block Bonjour.
        beacon.shouldSend = { [weak self] in !(self?.connected ?? true) }
        beacon.start()

        files.send = { [weak self] msg, done in self?.server.send(msg, completion: done) }
        mirror.send = { [weak self] in self?.server.send($0) }
        files.onReceived = { [weak self] url in
            self?.notifier.fileReceived(url)
            self?.showToast("Received \(url.lastPathComponent)", symbol: "arrow.down.circle.fill")
        }

        clipboard.onLocalChange = { [weak self] text in
            guard let self else { return }
            guard self.connected, self.clipboardSyncEnabled else {
                self.unsyncedMacCopyAt = Date()
                return
            }
            var m = Msg("clipboard"); m.text = text
            self.server.send(m)
        }
        clipboard.onPrivateChange = { [weak self] in self?.unsyncedMacCopyAt = Date() }
        clipboard.start()
        pasteInterceptor.shouldFetch = { [weak self] in
            guard let self else { return false }
            // A copy made just before ⌘V may not have been noticed yet.
            self.clipboard.poll()
            return self.shouldFetchOnPaste
        }
        pasteInterceptor.fetch = { [weak self] done in
            guard let self else { return done() }
            self.fetchPhoneClipboard(then: done)
        }
        if pasteFetchEnabled { pasteInterceptor.start() }
        pasteHotKey = HotKey(keyCode: kVK_ANSI_V, modifiers: controlKey | optionKey) { [weak self] in
            MainActor.assumeIsolated { self?.pasteFromPhone() }
        }

        notifier.state = self
        notifier.setUp()
        callPanelController.start(state: self)
        // Bluetooth status feeds the call panel, which watches this object.
        bluetoothObserver = bluetooth.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
            // A call that only Bluetooth knew about may have just ended.
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.resetCallPanelIfNoCall() } }
        }
        bluetooth.start()
        adb.startTracking()

        // Pick up permission changes made in System Settings.
        NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.notifier.refreshPermission() }
        }

        // After sleep, drop the stale socket and re-announce so the phone reconnects within seconds.
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { self?.didWake() }
        }

        // The phone pings every 15s, but Android pauses it in deep sleep (screen off, idle) and
        // the pings stop while the link itself is fine. So when it goes quiet, ping it: incoming
        // data wakes the phone and it answers. Only a phone that misses several pings is gone.
        watchdog = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.connected else { return }
                let silent = Date().timeIntervalSince(self.lastFrameAt)
                if silent > 75 {
                    callLog.notice("no answer from the phone for \(Int(silent), privacy: .public)s; dropping the link")
                    self.server.disconnect()
                } else if silent > 20 {
                    self.server.send(Msg("ping"))
                }
            }
        }
    }

    private func didWake() {
        server.disconnect()
        server.reannounce()
        Task {
            try? await Task.sleep(for: .seconds(2))
            await adb.enableReverse()
        }
    }

    func showToast(_ text: String, symbol: String) {
        withAnimation(.spring(response: 0.4, dampingFraction: 0.8)) { toast = Toast(text: text, symbol: symbol) }
        toastTask?.cancel()
        toastTask = Task {
            try? await Task.sleep(for: .seconds(2.4))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.3)) { toast = nil }
        }
    }

    func refreshPairingPayload() {
        pairingPayload = Pairing.qrPayload(key: server.key, port: LinkServer.port)
    }

    func resetPairing() {
        server.key = Pairing.resetKey()
        server.disconnect()
        refreshPairingPayload()
    }

    // MARK: - Incoming

    private func connectionChanged(_ isConnected: Bool) {
        let wasConnected = connected
        withAnimation(.spring(response: 0.5, dampingFraction: 0.8)) { connected = isConnected }
        lastFrameAt = Date()
        if !isConnected {
            phoneScreen = nil
            call = nil
            media = nil
            ringing = false
            transport = nil
            notifications.removeAll()
            notifier.removeAllPhoneNotifications()
            files.cancelIncoming()
            mirror.connectionLost()
            if wasConnected { showToast("\(phoneName) disconnected", symbol: "iphone.gen3.slash") }
        }
    }

    private func handle(_ m: Msg) {
        lastFrameAt = Date()
        switch m.type {
        case "hello":
            deviceName = m.name
            deviceModel = m.model
            bluetooth.preferredName = m.name
            mirror.phoneName = m.name ?? "Phone"
            transport = server.isBluetooth ? "Bluetooth" : (server.peerIsLocal ? "USB" : "Wi-Fi")
            if let host = server.peerHost { adb.phoneSeen(at: host) }
            var ok = Msg("helloOk"); ok.name = Host.current().localizedName ?? "Mac"
            server.send(ok)
            server.send(Msg("getContacts"))
            requestThreads()
            // The app list (with icons) is heavy; it loads once Wi-Fi or USB is back.
            if !server.isBluetooth { requestApps() }
            showToast("\(phoneName) connected · \(transport ?? "")", symbol: "checkmark.circle.fill")
            askForPastePermissionOnce()
        case "ping":
            server.send(Msg("pong"))
        case "battery":
            withAnimation(.smooth) {
                battery = m.level
                charging = m.charging ?? false
            }
        case "notification":
            receivedNotification(m)
        case "notificationRemoved":
            guard let key = m.key else { return }
            withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { notifications.removeAll { $0.key == key } }
            notifier.remove(key: key)
            if hiddenCallPanelID == key { hiddenCallPanelID = nil }
            resetCallPanelIfNoCall()
        case "clipboard":
            guard let text = m.text else { return }
            let requested = clipboardRequestedAt.map { Date().timeIntervalSince($0) < 10 } ?? false
            clipboardRequestedAt = nil
            guard clipboardSyncEnabled || requested else { return }
            // For ⌘V, only replace the Mac's copy with something new from the phone.
            clipboard.setFromPhone(text, onlyIfNew: pasteFetchDone != nil)
            if let done = pasteFetchDone {
                // Fetched for ⌘V, which goes through right after.
                pasteFetchDone = nil
                done()
            } else if requested && AXIsProcessTrusted() {
                PasteInterceptor.pressCommandV()
            } else {
                showToast(requested ? "Phone clipboard copied · press ⌘V" : "Clipboard from phone", symbol: "doc.on.clipboard.fill")
            }
        case "clipboardUnavailable":
            clipboardRequestedAt = nil
            if let done = pasteFetchDone {
                // ⌘V: just paste what the Mac has, quietly.
                pasteFetchDone = nil
                done()
                return
            }
            switch m.action {
            case "locked": explain("Unlock your phone", "Android only shares the clipboard while the phone is unlocked. Unlock it, then try Paste from phone again.")
            case "overlay": explain("Allow “Display over other apps”", "On the phone, open AyPhone → Setup and allow Display over other apps. That's what lets your Mac fetch the clipboard.")
            case "empty": showToast("Phone clipboard is empty", symbol: "doc.on.clipboard")
            default: showToast("Couldn't read the phone's clipboard", symbol: "exclamationmark.triangle.fill")
            }
        case "phoneScreen":
            phoneScreen = m.action
            if m.action == "unlocked" { phoneUnlockedAt = Date() }
        case "url":
            if let s = m.url, let url = URL(string: s), ["http", "https"].contains(url.scheme?.lowercased()) {
                NSWorkspace.shared.open(url)
            }
        case "fileOffer", "fileChunk", "fileEnd":
            files.handle(m)
        case "mirrorState", "mirrorConfig", "mirrorFrame", "mirrorAudio":
            mirror.handle(m)
        case "smsThreads":
            threads = withContactNames(m.threads ?? [])
        case "contacts":
            contacts = m.contacts ?? []
            var map: [String: String] = [:]
            for c in contacts {
                for n in c.numbers { if let key = Self.numberKey(n) { map[key] = c.name } }
            }
            nameByNumber = map
            threads = withContactNames(threads)
            if let call, call.name == nil, let name = contactName(for: call.number) {
                self.call = CallInfo(state: call.state, number: call.number, name: name)
            }
        case "callerInfo":
            receivedCallerHint(m.name)
        case "smsMessages":
            if let id = m.threadId {
                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) { messages[id] = m.messages ?? [] }
            }
        case "smsChanged":
            requestThreads()
            if let id = selectedThread { requestMessages(thread: id) }
        case "call":
            receivedCall(m)
        case "callSpeaker":
            speakerOn = m.on ?? false
            phoneControlsCallAudio = true
        case "media":
            withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) {
                if let title = m.title, !title.isEmpty {
                    let art = m.art.flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) }
                    media = MediaInfo(app: m.app, title: title, artist: m.artist, playing: m.playing ?? false, art: art)
                } else {
                    media = nil
                }
            }
        case "apps":
            let list = m.apps ?? []
            var icons: [String: NSImage] = [:]
            for app in list {
                if let b64 = app.icon, let data = Data(base64Encoded: b64), let image = NSImage(data: data) {
                    icons[app.pkg] = image
                }
            }
            appIcons = icons
            apps = list.map { PhoneApp(pkg: $0.pkg, name: $0.name, icon: nil) }
        default:
            break
        }
    }

    private func receivedNotification(_ m: Msg) {
        guard let key = m.key else { return }
        let icon = m.icon.flatMap { Data(base64Encoded: $0) }.flatMap { NSImage(data: $0) }
        let isCall = m.call ?? false
        var title = m.title ?? ""
        // Call notifications often show just the number; name it from contacts when we can.
        if isCall, !title.contains(where: \.isLetter), let name = contactName(for: title) { title = name }
        let n = PhoneNotification(
            key: key, pkg: m.pkg ?? "", app: m.app ?? m.pkg ?? "Android",
            title: title, text: m.text ?? "",
            time: Date(timeIntervalSince1970: (m.time ?? Date().timeIntervalSince1970 * 1000) / 1000),
            canReply: m.canReply ?? false, actions: m.actions ?? [], icon: icon,
            isCall: isCall, incomingCall: m.callType == "incoming")
        let isUpdate = notifications.contains { $0.key == key }
        withAnimation(.spring(response: 0.45, dampingFraction: 0.82)) {
            notifications.removeAll { $0.key == key }
            notifications.insert(n, at: 0)
        }
        // Calls get the floating call panel rather than a banner that slides away.
        // Chat apps update one notification per conversation, so each new message arrives as an
        // update; the phone flags real new messages (not refreshes or our own reply) with `alert`.
        if appNames[n.pkg] != n.app, !n.pkg.isEmpty { appNames[n.pkg] = n.app }
        let shouldAlert = m.alert ?? (!isUpdate && m.silent != true)
        if mirrorNotifications && !isCall && shouldAlert && !isMuted(n.pkg) { notifier.show(n, replacing: isUpdate) }
    }

    private func receivedCall(_ m: Msg) {
        let state = m.state ?? "idle"
        if state == "idle" {
            pendingCallerName = nil
            if hiddenCallPanelID == "telephony" || hiddenCallPanelID == "waiting" { hiddenCallPanelID = nil }
            speakerOn = false
            withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) { call = nil }
            resetCallPanelIfNoCall()
            return
        }
        // Android may report the same state twice, the second time without a number.
        var number = m.number ?? (call?.state == state ? call?.number : nil)
        let sameCaller = number == nil || Self.numberKey(number!) == call?.number.flatMap(Self.numberKey)
        var name = m.name ?? contactName(for: number) ?? (sameCaller ? call?.name : nil)
        if name == nil, let pending = pendingCallerName, Date().timeIntervalSince(pending.at) < 15 {
            name = pending.name
        }
        var newState = state
        var waitingNumber = m.waitingNumber
        var waitingName = m.waitingName ?? contactName(for: m.waitingNumber)
        // Android's plain call-state report (without the call companion) names whichever call
        // changed last, so another number while on a call is a second caller, not the person
        // you're talking to: keep the call as it was. (No waiting panel from these: the phone
        // could only hang up its current call, not decline the second one.)
        if m.telecom != true, let current = call, current.state == "offhook", !sameCaller {
            (newState, number, name) = ("offhook", current.number, current.name)
        }
        let newCall = CallInfo(state: newState, number: number, name: name,
                               waitingNumber: waitingNumber, waitingName: waitingName)
        if !newCall.hasWaiting, hiddenCallPanelID == "waiting" { hiddenCallPanelID = nil }
        if call?.state != state { bluetooth.phoneReportedCall(state) }
        withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) { call = newCall }
    }

    // MARK: - Contacts

    static func numberKey(_ number: String) -> String? {
        let digits = number.filter(\.isNumber)
        return digits.count >= 6 ? String(digits.suffix(9)) : nil
    }

    func contactName(for number: String?) -> String? {
        guard let number, let key = Self.numberKey(number) else { return nil }
        return nameByNumber[key]
    }

    private func withContactNames(_ list: [SmsThread]) -> [SmsThread] {
        list.map { t in
            var t = t
            if t.name == nil { t.name = contactName(for: t.address) }
            return t
        }
    }

    /// The dialer's notification title is the caller's name, or their number when unsaved.
    private func receivedCallerHint(_ title: String?) {
        guard let title = title?.trimmed, !title.isEmpty else { return }
        let name = title.contains(where: \.isLetter) ? title : contactName(for: title)
        let number = title.contains(where: \.isLetter) ? nil : title
        guard let call else {
            if let name { pendingCallerName = (name, Date()) }
            return
        }
        // During a call, the dialer's notification may be about a second caller instead.
        guard call.name == nil, !call.hasWaiting, call.state == "ringing" || call.number == nil,
              name != nil || call.number == nil else { return }
        var updated = call
        updated.number = call.number ?? number
        updated.name = name
        withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) { self.call = updated }
    }

    // MARK: - Call panel

    var callPanel: CallPanelModel? {
        guard showCallsOnMac, !callPanelDismissed else { return nil }
        // A second caller while on a call gets its own incoming panel; Answer puts the current
        // call on hold, as on the phone.
        if let call, call.hasWaiting, hiddenCallPanelID != "waiting" {
            return CallPanelModel(id: "waiting", caller: call.waitingDisplay,
                                  detail: "Incoming call · you're on a call with \(call.display)",
                                  app: "Phone", icon: nil, incoming: true,
                                  buttons: [.init(title: "Decline", kind: .reject, action: .telephony("decline")),
                                            .init(title: "Answer", kind: .accept, action: .telephony("answer"))])
        }
        // Regular calls with the Mac connected as a Bluetooth hands-free device get Mac audio controls.
        if bluetooth.isReady, hiddenCallPanelID != "telephony", let call = call ?? bluetoothCall {
            return bluetoothCallPanel(call)
        }
        if let n = notifications.first(where: { $0.isCall && !isMuted($0.pkg) }) {
            guard n.key != hiddenCallPanelID else { return nil }
            var buttons = n.actions.enumerated().filter { !$0.element.isEmpty }.map { i, title in
                CallPanelModel.Button(title: title, kind: CallPanelModel.kind(for: title),
                                      action: .notification(key: n.key, index: i))
            }
            // A regular phone call in progress (the dialer's notification): the phone's speaker
            // too, unless the dialer already offers it.
            if call?.state == "offhook" || phoneControlsCallAudio, !n.incomingCall,
               !buttons.contains(where: { $0.title.localizedCaseInsensitiveContains("speaker") }) {
                buttons.insert(speakerButton, at: 0)
            }
            return CallPanelModel(id: n.key, caller: n.title.isEmpty ? n.app : n.title, detail: n.text,
                                  app: n.app, icon: n.icon, incoming: n.incomingCall, buttons: buttons)
        }
        if let call, hiddenCallPanelID != "telephony" {
            let ringing = call.state == "ringing"
            let buttons: [CallPanelModel.Button] = ringing
                ? [.init(title: "Decline", kind: .reject, action: .telephony("decline")),
                   .init(title: "Answer", kind: .accept, action: .telephony("answer"))]
                : [speakerButton,
                   .init(title: "Mute", kind: .neutral, action: .telephony("mute")),
                   .init(title: "End", kind: .reject, action: .telephony("end"))]
            return CallPanelModel(id: "telephony", caller: call.display,
                                  detail: ringing ? "Incoming call" : "Audio is on your phone",
                                  app: "Phone", icon: nil, incoming: ringing, buttons: buttons)
        }
        return nil
    }

    /// Puts the phone's call sound on its loudspeaker (or back to the earpiece), so a call
    /// answered from the Mac can be heard without picking the phone up.
    private var speakerButton: CallPanelModel.Button {
        .init(title: speakerOn ? "Speaker off" : "Speaker", kind: .neutral, action: .telephony("speaker"))
    }

    /// A call known only from the Bluetooth link (the Wi-Fi/USB link may be down).
    private var bluetoothCall: CallInfo? {
        guard bluetooth.ringing || bluetooth.callActive else { return nil }
        let number = bluetooth.callerNumber
        return CallInfo(state: bluetooth.ringing ? "ringing" : "offhook", number: number, name: contactName(for: number))
    }

    private func bluetoothCallPanel(_ call: CallInfo) -> CallPanelModel {
        typealias B = CallPanelModel.Button
        let buttons: [B]
        let detail: String
        if bluetooth.macAudioBlocked && !bluetooth.audioOnMac {
            // macOS won't carry this phone's call audio, so don't offer to move it here.
            if call.state == "ringing" {
                buttons = [B(title: "Decline", kind: .reject, action: .bluetooth("end")),
                           B(title: "Answer", kind: .accept, action: .bluetooth("answerOnPhone"))]
                detail = "Incoming call · you'll talk on your phone"
            } else {
                buttons = [speakerButton, B(title: "End", kind: .reject, action: .bluetooth("end"))]
                detail = "Audio is on your phone (macOS doesn't let apps take call audio)"
            }
        } else if call.state == "ringing" {
            buttons = [B(title: "Decline", kind: .reject, action: .bluetooth("end")),
                       B(title: "On phone", kind: .neutral, action: .bluetooth("answerOnPhone")),
                       B(title: "Answer on Mac", kind: .accept, action: .bluetooth("answerOnMac"))]
            detail = "Incoming call"
        } else if bluetooth.audioOnMac {
            buttons = [B(title: bluetooth.micMuted ? "Unmute" : "Mute", kind: .neutral, action: .bluetooth("mute")),
                       B(title: "Move to phone", kind: .neutral, action: .bluetooth("moveToPhone")),
                       B(title: "End", kind: .reject, action: .bluetooth("end"))]
            detail = "Talking on this Mac"
        } else {
            buttons = [speakerButton,
                       B(title: "Move to Mac", kind: .neutral, action: .bluetooth("moveToMac")),
                       B(title: "End", kind: .reject, action: .bluetooth("end"))]
            detail = "Audio is on your phone"
        }
        return CallPanelModel(id: "telephony", caller: call.display, detail: detail, app: "Phone",
                              icon: nil, incoming: call.state == "ringing", buttons: buttons)
    }

    func performCallButton(_ button: CallPanelModel.Button) {
        callLog.notice("call button: \(button.title, privacy: .public)")
        if button.kind == .reject, let id = callPanel?.id {
            // Declining a waiting call leaves the current one on: back to its panel straight away.
            if id == "waiting" { hiddenCallPanelID = "waiting" } else { closePanelForEndedCall(id) }
        }
        switch button.action {
        case .notification(let key, let index): runAction(key, index: index)
        case .telephony(let action): callAction(action)
        case .bluetooth(let action):
            switch action {
            case "answerOnMac": bluetooth.answerOnMac()
            case "moveToMac": bluetooth.moveToMac()
            case "moveToPhone": bluetooth.moveToPhone()
            case "answerOnPhone": bluetooth.answerOnPhone()
            case "mute": bluetooth.toggleMute()
            default: bluetooth.endCall()
            }
        }
    }

    // MARK: - Muting apps

    /// Android package → bundle IDs of the same app on the Mac.
    private static let macCounterparts: [String: [String]] = [
        "com.microsoft.teams": ["com.microsoft.teams2", "com.microsoft.teams"],
        "com.whatsapp": ["net.whatsapp.WhatsApp", "desktop.WhatsApp"],
        "com.whatsapp.w4b": ["net.whatsapp.WhatsApp", "desktop.WhatsApp"],
        "com.Slack": ["com.tinyspeck.slackmacgap"],
        "org.telegram.messenger": ["ru.keepcoder.Telegram", "org.telegram.desktop"],
        "com.discord": ["com.hnc.Discord"],
        "com.microsoft.office.outlook": ["com.microsoft.Outlook"],
        "com.skype.raider": ["com.skype.skype"],
        "us.zoom.videomeetings": ["us.zoom.xos"],
        "org.thoughtcrime.securesms": ["org.whispersystems.signal-desktop"],
        "com.facebook.orca": ["com.facebook.archon.developerID", "com.facebook.archon"],
        "com.google.android.apps.dynamite": ["com.google.Chat"],
        "com.linkedin.android": ["com.linkedin.LinkedIn"],
    ]
    private var onThisMacCache: [String: Bool] = [:]

    func isOnThisMac(_ pkg: String) -> Bool {
        if let cached = onThisMacCache[pkg] { return cached }
        let found = (Self.macCounterparts[pkg] ?? []).contains {
            NSWorkspace.shared.urlForApplication(withBundleIdentifier: $0) != nil
        }
        onThisMacCache[pkg] = found
        return found
    }

    func isMuted(_ pkg: String) -> Bool {
        mutedApps.contains(pkg) || (muteAppsOnThisMac && isOnThisMac(pkg))
    }

    func setMuted(_ pkg: String, _ muted: Bool) {
        if muted { mutedApps.insert(pkg) } else { mutedApps.remove(pkg) }
        if muted { notifications.filter { $0.pkg == pkg }.forEach { notifier.remove(key: $0.key) } }
    }

    /// Apps seen from the phone that also exist on this Mac.
    var appsAlsoOnThisMac: [(pkg: String, name: String)] {
        appNames.filter { isOnThisMac($0.key) }.map { ($0.key, $0.value) }.sorted { $0.name < $1.name }
    }

    func hideCallPanel(_ id: String) {
        callLog.notice("call panel closed with ✕ (\(id, privacy: .public))")
        hiddenCallPanelID = id
        // Ignoring a waiting call shouldn't hide the call you're on.
        if id == "waiting" { return }
        callPanelDismissed = true
    }

    /// Ending a call can take the calling app several seconds to confirm (WhatsApp takes 5), so
    /// the panel goes as soon as End/Decline is clicked. If the call is somehow still going a
    /// while later, it comes back.
    private func closePanelForEndedCall(_ id: String) {
        hiddenCallPanelID = id
        callPanelDismissed = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.callPanelDismissed, self.hiddenCallPanelID == id else { return }
                let stillOn = self.call != nil || self.bluetooth.ringing || self.bluetooth.callActive
                    || self.notifications.contains(where: { $0.isCall && $0.key == id })
                if stillOn {
                    callLog.notice("call still going after End; showing the panel again")
                    self.hiddenCallPanelID = nil
                    self.callPanelDismissed = false
                }
            }
        }
    }

    /// Lets the panel show again once no call is ringing or in progress.
    private func resetCallPanelIfNoCall() {
        let anyCall = call != nil || bluetooth.ringing || bluetooth.callActive || notifications.contains(where: \.isCall)
        if !anyCall { phoneControlsCallAudio = false }
        if !anyCall && callPanelDismissed { callPanelDismissed = false }
    }

    func requestNotificationPermission() {
        if notificationPermission == .denied {
            openNotificationSettings()
        } else {
            notifier.requestPermission()
        }
    }

    func openNotificationSettings() {
        let id = Bundle.main.bundleIdentifier ?? "com.ayphone.mac"
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(id)") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: - Outgoing actions

    func reply(to key: String, text: String) {
        var m = Msg("notificationReply"); m.key = key; m.text = text
        server.send(m)
        showToast("Reply sent", symbol: "paperplane.fill")
    }

    func dismiss(_ key: String) {
        var m = Msg("notificationDismiss"); m.key = key
        server.send(m)
        withAnimation(.spring(response: 0.4, dampingFraction: 0.85)) { notifications.removeAll { $0.key == key } }
        notifier.remove(key: key)
    }

    func runAction(_ key: String, index: Int) {
        var m = Msg("notificationAction"); m.key = key; m.index = index
        server.send(m)
    }

    func clearAllNotifications() {
        for n in notifications { dismiss(n.key) }
    }

    func sendClipboardNow() {
        guard let text = NSPasteboard.general.string(forType: .string) else { return }
        var m = Msg("clipboard"); m.text = text
        server.send(m)
        showToast("Clipboard sent to phone", symbol: "doc.on.clipboard.fill")
    }

    func sendURL(_ url: String) {
        var m = Msg("url"); m.url = url
        server.send(m)
        showToast("Link sent to phone", symbol: "safari.fill")
    }

    func sendFiles(_ urls: [URL]) {
        let files = urls.filter { !$0.hasDirectoryPath }
        guard !files.isEmpty else { return }
        guard connected else {
            showToast("Phone not connected", symbol: "exclamationmark.triangle.fill")
            return
        }
        for url in files { self.files.enqueue(url) }
        showToast(files.count == 1 ? "Sending \(files[0].lastPathComponent)" : "Sending \(files.count) files",
                  symbol: "arrow.up.circle.fill")
    }

    func requestThreads() {
        server.send(Msg("getSmsThreads"))
    }

    func requestMessages(thread: Int64) {
        var m = Msg("getSmsMessages"); m.threadId = thread
        server.send(m)
    }

    func sendSms(to address: String, text: String) {
        var m = Msg("smsSend"); m.address = address; m.text = text
        server.send(m)
    }

    func callAction(_ action: String) {
        var m = Msg("callAction"); m.action = action
        server.send(m)
    }

    func mediaAction(_ action: String) {
        var m = Msg("mediaAction"); m.action = action
        server.send(m)
    }

    func toggleFindPhone() {
        ringing.toggle()
        var m = Msg("findPhone"); m.action = ringing ? "start" : "stop"
        server.send(m)
    }

    /// When "Paste from phone" last asked for the clipboard.
    private var clipboardRequestedAt: Date?

    /// Paste from phone (⌃⌥V): fetch what was last copied on the phone. Android blocks reading
    /// it in the background, so the phone briefly brings up an invisible screen to read it. No
    /// accessibility service needed on the phone. Pastes into the front app if this Mac allows
    /// AyPhone to (Accessibility); otherwise it's on the clipboard, ready for ⌘V.
    func pasteFromPhone() {
        guard connected else {
            explain("Phone not connected", "Paste from phone works once AyPhone on your phone is connected to this Mac.")
            return
        }
        clipboardRequestedAt = Date()
        server.send(Msg("clipboardRequest"))
    }

    private func explain(_ title: String, _ message: String) {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.addButton(withTitle: "OK")
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    func requestApps() {
        guard !server.isBluetooth else {
            explainNeedsWiFi("Your phone's apps")
            return
        }
        server.send(Msg("getApps"))
    }

    /// Says why a feature can't run while the phone is on the Bluetooth fallback. A dialog
    /// rather than a toast, so it's seen even when clicked from the menu bar.
    func explainNeedsWiFi(_ feature: String) {
        let alert = NSAlert()
        alert.messageText = "\(feature) needs Wi-Fi or USB"
        alert.informativeText = "\(phoneName) is connected over Bluetooth right now, which is too slow for this. "
            + "That usually means this Wi-Fi doesn't let devices reach each other (common on office, hotel and guest networks).\n\n"
            + "Connect both devices to a Wi-Fi that allows it, like your home network, or plug the phone in with a USB cable. "
            + "AyPhone switches over by itself. Notifications, messages, calls and the clipboard keep working over Bluetooth."
        alert.alertStyle = .informational
        alert.addButton(withTitle: "OK")
        NSApp.activate(ignoringOtherApps: true)
        alert.runModal()
    }

    func launchOnPhone(_ app: PhoneApp) {
        var m = Msg("launchApp"); m.pkg = app.pkg
        server.send(m)
        showToast("Opening \(app.name) on phone", symbol: "iphone.gen3")
    }

    func openInWindow(_ app: PhoneApp) {
        adb.openAppWindow(package: app.pkg, title: app.name)
    }

    /// Standard mirroring: no debugging needed; the phone asks to allow sharing each time.
    func mirrorScreen() {
        guard connected else {
            showToast("Phone not connected", symbol: "exclamationmark.triangle.fill")
            return
        }
        guard !server.isBluetooth else {
            explainNeedsWiFi("Screen mirroring")
            return
        }
        mirror.start()
    }

    /// Advanced mirroring through scrcpy (needs USB or wireless debugging).
    func mirrorScreenAdvanced() {
        adb.mirrorScreen(title: phoneName)
    }

    func openCamera() {
        adb.openCamera()
    }
}
