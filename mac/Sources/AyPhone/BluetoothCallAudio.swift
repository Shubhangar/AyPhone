import Foundation
import IOBluetooth
import ObjectiveC
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "bluetooth")

/// Talk on regular phone calls through the Mac (experimental).
///
/// Android doesn't let apps capture cellular call audio, so it can't travel over AyPhone's
/// Wi-Fi/USB link. Instead the Mac acts as a Bluetooth hands-free device for the phone, like a
/// car kit: macOS's hands-free API answers and ends calls and moves the call audio between
/// the Mac's speakers/mic and the phone. Requires the phone to be paired with this Mac in
/// System Settings → Bluetooth.
@MainActor
final class BluetoothCallAudio: NSObject, ObservableObject {
    enum Status: Equatable {
        case off
        /// No paired phone found in System Settings → Bluetooth.
        case noPhone
        case connecting(String)
        case ready(String)
        case failed(String)
    }

    @Published private(set) var status: Status = .off {
        didSet { if status != oldValue { log.notice("status: \(String(describing: self.status), privacy: .public)") } }
    }
    /// True while the call's audio is on the Mac (the Bluetooth audio link is open).
    @Published private(set) var audioOnMac = false
    @Published private(set) var micMuted = false
    /// macOS refused to carry this phone's call audio (it rejects the audio link from apps on
    /// recent versions). Calls can still be answered and ended from the Mac; the audio stays on
    /// the phone. Cleared if the audio link ever opens.
    @Published private(set) var macAudioBlocked = UserDefaults.standard.bool(forKey: "callAudioBlocked") {
        didSet { UserDefaults.standard.set(macAudioBlocked, forKey: "callAudioBlocked") }
    }
    /// Call state as the phone reports it over Bluetooth, so calls work even when the
    /// Wi-Fi/USB link is down.
    @Published private(set) var ringing = false
    @Published private(set) var callActive = false
    @Published private(set) var callerNumber: String?
    @Published var enabled = UserDefaults.standard.bool(forKey: "bluetoothCalls") {
        didSet {
            UserDefaults.standard.set(enabled, forKey: "bluetoothCalls")
            enabled ? connect() : disconnect()
        }
    }

    /// Used to pick the right phone when several are paired.
    var preferredName: String?

    private var handsFree: IOBluetoothHandsFreeDevice?
    /// One hands-free object per phone entry, reused for every reconnect. macOS keeps delivering
    /// Bluetooth data to an object after it disconnects, so a freed one crashes the app, and
    /// two live ones for the same phone both answer when its Bluetooth link comes back and
    /// crash it too.
    private var handsFreeByPhone: [String: IOBluetoothHandsFreeDevice] = [:]
    private var retryTimer: Timer?
    /// Fires if a connect attempt gets no answer, so a dead pairing entry can't stall us forever.
    private var connectTimeout: Timer?
    /// Phones to try, best first, and which one we're on.
    private var candidates: [IOBluetoothDevice] = []
    private var candidateIndex = 0
    /// Set when the call was answered from the Mac, so its audio should stay here. Calls
    /// answered on the phone would otherwise also route to the Mac, like with any headset.
    private var keepAudioOnMac = false

    var isReady: Bool {
        if case .ready = status { return true }
        return false
    }

    func start() {
        Self.disableHandsFreeSMS()
        if enabled { connect() }
    }

    /// Stops using the current hands-free object (it stays in [handsFreeByPhone] for reuse).
    private func detachHandsFree() {
        guard let old = handsFree else { return }
        // Its late callbacks would otherwise reset the state of the connection we use next.
        old.delegate = nil
        old.disconnect()
        handsFree = nil
    }

    /// When a text arrives, macOS's hands-free code reads it from the phone over Bluetooth and
    /// crashes the app on message formats it can't decode. AyPhone gets texts over its own link,
    /// so switch that off: ignore the phone's "new message" notices and message reads.
    private static func disableHandsFreeSMS() {
        guard let cls = NSClassFromString("IOBluetoothHandsFreeDevice") else { return }
        for name in ["processMessageNotificationCMTI:", "processReadMessageCMGR:"] {
            guard let method = class_getInstanceMethod(cls, NSSelectorFromString(name)) else {
                log.notice("hands-free has no \(name, privacy: .public); nothing to switch off")
                continue
            }
            let ignore: @convention(block) (AnyObject, AnyObject?) -> Void = { _, _ in }
            method_setImplementation(method, imp_implementationWithBlock(ignore))
        }
    }

    func connect() {
        retryTimer?.invalidate()
        guard enabled else { return }
        // IOBluetooth's first call waits on its own startup, which can need the main thread;
        // asking from the main thread can deadlock the app at launch. Ask in the background.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let paired = IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice] ?? []
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.connect(toOneOf: paired) }
            }
        }
    }

    private func connect(toOneOf paired: [IOBluetoothDevice]) {
        guard enabled else { return }
        let phones = paired.filter { $0.deviceClassMajor == BluetoothDeviceClassMajor(kBluetoothDeviceClassMajorPhone) }
        log.notice("paired devices: \(paired.map { "\($0.name ?? "?") (class \($0.deviceClassMajor))" }.joined(separator: ", "), privacy: .public)")
        let preferred = preferredName?.lowercased()
        // The same phone can appear twice (an old pairing entry); the one currently connected is
        // the live one, then name matches.
        candidates = phones.sorted { a, b in
            if a.isConnected() != b.isConnected() { return a.isConnected() }
            let am = preferred != nil && (a.name ?? "").lowercased() == preferred
            let bm = preferred != nil && (b.name ?? "").lowercased() == preferred
            return am && !bm
        }
        candidateIndex = 0
        guard !candidates.isEmpty else {
            status = .noPhone
            scheduleRetry()
            return
        }
        tryCandidate()
    }

    private func tryCandidate() {
        guard enabled, candidateIndex < candidates.count else {
            scheduleRetry()
            return
        }
        let phone = candidates[candidateIndex]
        let key = phone.addressString ?? phone.name ?? "phone"
        let existing = handsFreeByPhone[key]
        if let current = handsFree, current !== existing { detachHandsFree() }
        guard let hf = existing ?? IOBluetoothHandsFreeDevice(device: phone, delegate: self) else {
            candidateIndex += 1
            tryCandidate()
            return
        }
        handsFreeByPhone[key] = hf
        hf.delegate = self
        handsFree = hf
        if hf.isConnected {
            status = .ready(phone.name ?? "phone")
            return
        }
        status = .connecting(phone.name ?? "phone")
        log.notice("connecting hands-free to \(phone.name ?? "?", privacy: .public) (\(phone.isConnected() ? "linked" : "not linked", privacy: .public), entry \(self.candidateIndex + 1) of \(self.candidates.count, privacy: .public))")
        hf.connect()
        connectTimeout?.invalidate()
        connectTimeout = Timer.scheduledTimer(withTimeInterval: 15, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, case .connecting = self.status else { return }
                log.notice("no answer from that phone entry; trying the next")
                self.candidateIndex += 1
                self.tryCandidate()
            }
        }
    }

    func disconnect() {
        retryTimer?.invalidate()
        connectTimeout?.invalidate()
        detachHandsFree()
        audioOnMac = false
        status = .off
    }

    // MARK: Call control

    func answerOnMac() {
        log.notice("action: answer on Mac (connected: \(self.handsFree?.isConnected ?? false, privacy: .public))")
        keepAudioOnMac = true
        handsFree?.acceptCall()
        pullAudioToMac()
    }

    /// Asks the phone for the audio, then opens the Bluetooth audio link ourselves if the
    /// phone hasn't handed it over.
    private func pullAudioToMac() {
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.keepAudioOnMac, !self.audioOnMac else { return }
                log.notice("audio still on phone after answering; asking phone to move it")
                self.handsFree?.transferAudioToComputer()
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.keepAudioOnMac, !self.audioOnMac else { return }
                log.notice("phone didn't move the audio; opening the audio link directly")
                self.handsFree?.connectSCO()
            }
        }
    }

    func moveToMac() {
        log.notice("action: move audio to Mac")
        keepAudioOnMac = true
        handsFree?.transferAudioToComputer()
        pullAudioToMac()
    }

    func moveToPhone() {
        log.notice("action: move audio to phone")
        keepAudioOnMac = false
        handsFree?.transferAudioToPhone()
    }

    /// Hang up, or decline while ringing.
    func endCall() {
        log.notice("action: end/decline")
        handsFree?.endCall()
    }

    func answerOnPhone() {
        log.notice("action: answer on phone")
        keepAudioOnMac = false
        handsFree?.acceptCallOnPhone()
    }

    func toggleMute() {
        guard let hf = handsFree else { return }
        hf.isInputMuted.toggle()
        micMuted = hf.isInputMuted
    }

    /// Called when the phone reports a call over Wi-Fi/USB, to check the Bluetooth side agrees.
    func phoneReportedCall(_ state: String) {
        guard isReady else { return }
        log.notice("phone reports call '\(state, privacy: .public)' over Wi-Fi; Bluetooth ringing=\(self.ringing, privacy: .public) active=\(self.callActive, privacy: .public); asking phone for its call list")
        handsFree?.currentCallList()
    }

    private func scheduleRetry() {
        retryTimer?.invalidate()
        guard enabled else { return }
        retryTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, !self.isReady else { return }
                self.connect()
            }
        }
    }

    private func callBecameActive(_ active: Bool) {
        callActive = active
        if active {
            if !keepAudioOnMac { handsFree?.transferAudioToPhone() }
        } else {
            keepAudioOnMac = false
            micMuted = false
            callerNumber = nil
            handsFree?.isInputMuted = false
        }
    }
}

// IOBluetooth calls its delegates on the main thread.
extension BluetoothCallAudio: IOBluetoothHandsFreeDeviceDelegate {
    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, connected status: NSNumber!) {
        MainActor.assumeIsolated {
            let name = device?.device?.name ?? "phone"
            log.notice("hands-free connected callback, result \(status?.int32Value ?? -1, privacy: .public)")
            connectTimeout?.invalidate()
            if status?.int32Value == kIOReturnSuccess {
                // A retry still pending from an earlier drop would tear this connection down.
                retryTimer?.invalidate()
                self.status = .ready(name)
            } else {
                self.status = .failed("Couldn't connect to \(name) over Bluetooth")
                scheduleRetry()
            }
        }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, disconnected status: NSNumber!) {
        MainActor.assumeIsolated {
            log.notice("hands-free disconnected, result \(status?.int32Value ?? -1, privacy: .public)")
            audioOnMac = false
            if enabled {
                self.status = .connecting(device?.device?.name ?? "phone")
                scheduleRetry()
            }
        }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionOpened status: NSNumber!) {
        MainActor.assumeIsolated {
            log.notice("call audio link opened (result \(status?.int32Value ?? -1, privacy: .public), keep on Mac: \(self.keepAudioOnMac, privacy: .public))")
            guard status?.int32Value == kIOReturnSuccess else {
                log.notice("macOS refused the call audio; it stays on the phone")
                keepAudioOnMac = false
                macAudioBlocked = true
                return
            }
            macAudioBlocked = false
            audioOnMac = true
            // The phone pushed audio here (e.g. a call answered on the phone): send it back.
            if callActive && !keepAudioOnMac { handsFree?.transferAudioToPhone() }
        }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFree!, scoConnectionClosed status: NSNumber!) {
        MainActor.assumeIsolated {
            log.notice("call audio link closed")
            audioOnMac = false
        }
    }

    /// 0 none, 1 incoming (ringing), 2 outgoing dialing, 3 outgoing alerting.
    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, callSetupMode: NSNumber!) {
        MainActor.assumeIsolated {
            log.notice("call setup mode: \(callSetupMode?.intValue ?? -1, privacy: .public)")
            ringing = callSetupMode?.intValue == 1
            if !ringing && !callActive { callerNumber = nil }
        }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, incomingCallFrom number: String!) {
        MainActor.assumeIsolated {
            log.notice("incoming call (number \(number?.isEmpty == false ? "present" : "hidden", privacy: .public))")
            ringing = true
            if let number, !number.isEmpty { callerNumber = number }
        }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, currentCall: [AnyHashable: Any]!) {
        MainActor.assumeIsolated {
            log.notice("phone call list entry: status=\(String(describing: currentCall?["IOBluetoothHandsFreeCallStatus"]), privacy: .public) direction=\(String(describing: currentCall?["IOBluetoothHandsFreeCallDirection"]), privacy: .public)")
        }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, ringAttempt: NSNumber!) {
        MainActor.assumeIsolated {
            log.notice("ring from phone")
            ringing = true
        }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, isServiceAvailable: NSNumber!) {
        MainActor.assumeIsolated { log.notice("phone network service: \(isServiceAvailable?.boolValue ?? false, privacy: .public)") }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, signalStrength: NSNumber!) {
        MainActor.assumeIsolated { log.notice("phone signal: \(signalStrength?.intValue ?? -1, privacy: .public)") }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, batteryCharge: NSNumber!) {
        MainActor.assumeIsolated { log.notice("phone battery: \(batteryCharge?.intValue ?? -1, privacy: .public)") }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, unhandledResultCode: String!) {
        MainActor.assumeIsolated { log.notice("phone sent: \(unhandledResultCode ?? "", privacy: .public)") }
    }

    nonisolated func handsFree(_ device: IOBluetoothHandsFreeDevice!, isCallActive: NSNumber!) {
        MainActor.assumeIsolated {
            log.notice("call active: \(isCallActive?.boolValue ?? false, privacy: .public)")
            callBecameActive(isCallActive?.boolValue ?? false)
        }
    }
}
