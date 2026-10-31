import AppKit
import Foundation
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "adb")

/// Everything that goes through adb: automatic port forwarding when a phone is plugged in,
/// wireless debugging so the rest works without the cable, one-time grants for automatic
/// clipboard, and scrcpy for mirroring, app windows and the phone camera.
@MainActor
final class AdbManager: ObservableObject {
    static let phonePackage = "com.droidlink.app"

    @Published private(set) var adbPath: String?
    /// Serial of the plugged-in, authorized phone.
    @Published private(set) var usbSerial: String?
    /// `unauthorized` while the phone is waiting for "Allow USB debugging?".
    @Published private(set) var usbState: String?
    /// Developer-options features (they need USB debugging on the phone). Off by default:
    /// notifications, calls, files, clipboard and standard mirroring never need them, and
    /// while this is off AyPhone doesn't touch adb at all.
    @Published var advancedEnabled = UserDefaults.standard.bool(forKey: "advancedFeatures") {
        didSet {
            UserDefaults.standard.set(advancedEnabled, forKey: "advancedFeatures")
            if advancedEnabled { startTracking() } else { Task { await stopTracking() } }
        }
    }

    /// "ip:port" of the phone when adb is connected over Wi-Fi.
    @Published private(set) var wirelessSerial: String?
    /// Mirroring, app windows and the camera over Wi-Fi once the phone has been plugged in once.
    @Published var wirelessEnabled = UserDefaults.standard.object(forKey: "wirelessAdb") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(wirelessEnabled, forKey: "wirelessAdb")
            if wirelessEnabled {
                Task { await setUpWireless() }
            } else if let serial = wirelessSerial {
                Task { await Self.exec(adbPath ?? "adb", ["disconnect", serial]) }
            }
        }
    }
    /// The phone's Wi-Fi address, learned over USB or from the AyPhone link.
    private(set) var wirelessHost = UserDefaults.standard.string(forKey: "wirelessHost") {
        didSet { UserDefaults.standard.set(wirelessHost, forKey: "wirelessHost") }
    }
    private static let wirelessPort = 5555
    private var wirelessRetry: Timer?
    private var connectingWireless = false
    @Published private(set) var scrcpyPath: String?
    @Published private(set) var installingScrcpy = false
    @Published var message: String?

    private var tracker: Process?
    private var trackBuffer = ""
    private var scrcpyProcesses: [Process] = []

    init() {
        adbPath = Self.findAdb()
        scrcpyPath = Self.findScrcpy()
    }

    var hasUSBPhone: Bool { usbSerial != nil }
    /// The phone adb commands go to: the cable when plugged in, otherwise Wi-Fi.
    var targetSerial: String? { usbSerial ?? wirelessSerial }
    var hasDevice: Bool { targetSerial != nil }
    var isWireless: Bool { usbSerial == nil && wirelessSerial != nil }

    static var supportDirectory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("AyPhone", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func findAdb() -> String? {
        [
            NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
        ].first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    private static func findScrcpy() -> String? {
        [
            supportDirectory.appendingPathComponent("scrcpy/scrcpy").path,
            "/opt/homebrew/bin/scrcpy",
            "/usr/local/bin/scrcpy",
        ].first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    // MARK: - USB tracking

    /// Watches for phones being plugged in (`adb track-devices`) and forwards our port to each.
    func startTracking() {
        guard advancedEnabled, let adb = adbPath, tracker == nil else { return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: adb)
        p.arguments = ["track-devices"]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = FileHandle.nullDevice
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty else { return }
            let text = String(decoding: data, as: UTF8.self)
            Task { @MainActor in self?.consumeTrackOutput(text) }
        }
        p.terminationHandler = { [weak self] _ in
            Task { @MainActor in
                self?.tracker = nil
                try? await Task.sleep(for: .seconds(5))
                self?.startTracking() // no-op once advanced features are off
            }
        }
        do {
            try p.run()
            tracker = p
        } catch {
            message = "Could not watch for USB phones: \(error.localizedDescription)"
        }
        // Keep trying to reach the phone over Wi-Fi when the cable isn't in.
        wirelessRetry?.invalidate()
        wirelessRetry = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { Task { await self?.connectWireless() } }
        }
        Task { await connectWireless() }
    }

    /// track-devices emits messages framed by a 4-hex-digit length, each listing "serial\tstate" lines.
    private func consumeTrackOutput(_ chunk: String) {
        trackBuffer += chunk
        while trackBuffer.count >= 4, let length = Int(trackBuffer.prefix(4), radix: 16), trackBuffer.count >= 4 + length {
            let payload = String(trackBuffer.dropFirst(4).prefix(length))
            trackBuffer = String(trackBuffer.dropFirst(4 + length))
            applyDeviceList(payload)
        }
    }

    private func applyDeviceList(_ payload: String) {
        let devices = payload.split(separator: "\n").compactMap { line -> (serial: String, state: String)? in
            let parts = line.split(separator: "\t")
            return parts.count >= 2 ? (String(parts[0]), String(parts[1])) : nil
        }
        // Network devices show up as "ip:port" (or an mDNS name for Android's Wireless debugging).
        func isNetwork(_ serial: String) -> Bool { serial.contains(":") || serial.contains("._adb") }
        let usb = devices.filter { !isNetwork($0.serial) }
        let readyUSB = usb.first { $0.state == "device" }
        let readyWireless = devices.first { isNetwork($0.serial) && $0.state == "device" }

        usbState = readyUSB?.state ?? usb.first?.state
        if readyUSB?.serial != usbSerial {
            usbSerial = readyUSB?.serial
            if usbSerial != nil {
                Task {
                    await enableReverse()
                    await setUpWireless()
                }
            }
        }
        if readyWireless?.serial != wirelessSerial {
            wirelessSerial = readyWireless?.serial
        }
    }

    /// Turning advanced features off: stop watching, put the phone's debugging back to
    /// USB-only (closing the Wi-Fi debugging port AyPhone opened), and stop adb.
    private func stopTracking() async {
        tracker?.terminationHandler = nil
        tracker?.terminate()
        tracker = nil
        wirelessRetry?.invalidate()
        wirelessRetry = nil
        if let adb = adbPath {
            if let serial = wirelessSerial ?? usbSerial {
                _ = await Self.exec(adb, ["-s", serial, "usb"])
            }
            _ = await Self.exec(adb, ["kill-server"])
        }
        usbSerial = nil
        wirelessSerial = nil
        usbState = nil
    }

    /// One-off `adb devices`, in case the tracker missed a change.
    private func refreshDevices() async {
        guard let adb = adbPath else { return }
        let result = await Self.exec(adb, ["devices"])
        let lines = result.output.split(separator: "\n").dropFirst().map { $0.replacingOccurrences(of: " ", with: "\t") }
        applyDeviceList(lines.joined(separator: "\n"))
        if tracker == nil { startTracking() }
    }

    // MARK: - Wireless

    /// While the phone is on the cable: note its Wi-Fi address and switch its debugging
    /// connection to also listen on Wi-Fi (this lasts until the phone restarts).
    func setUpWireless() async {
        guard advancedEnabled, wirelessEnabled, let adb = adbPath, let usb = usbSerial else { return }
        let addr = await Self.exec(adb, ["-s", usb, "shell", "ip", "-f", "inet", "addr", "show", "wlan0"])
        if let range = addr.output.range(of: #"inet (\d+\.\d+\.\d+\.\d+)"#, options: .regularExpression) {
            wirelessHost = String(addr.output[range].dropFirst(5))
        }
        let port = await Self.exec(adb, ["-s", usb, "shell", "getprop", "service.adb.tcp.port"])
        if port.output.trimmed != String(Self.wirelessPort) {
            _ = await Self.exec(adb, ["-s", usb, "tcpip", String(Self.wirelessPort)])
            try? await Task.sleep(for: .seconds(2))
        }
        await connectWireless()
    }

    /// The AyPhone link tells us where the phone is on the network, even without the cable.
    func phoneSeen(at host: String) {
        guard advancedEnabled, host.range(of: #"^\d+\.\d+\.\d+\.\d+$"#, options: .regularExpression) != nil else { return }
        if host != wirelessHost {
            wirelessHost = host
            if let old = wirelessSerial, !old.hasPrefix(host) {
                Task { await Self.exec(adbPath ?? "adb", ["disconnect", old]) }
            }
        }
        Task { await connectWireless() }
    }

    func connectWireless() async {
        guard advancedEnabled, wirelessEnabled, wirelessSerial == nil, !connectingWireless,
              let adb = adbPath, let host = wirelessHost else { return }
        connectingWireless = true
        defer { connectingWireless = false }
        _ = await Self.exec(adb, ["connect", "\(host):\(Self.wirelessPort)"])
        // track-devices reports the new device; nothing else to do here.
    }

    // MARK: - Commands

    @discardableResult
    func adb(_ args: [String]) async -> (status: Int32, output: String) {
        guard let adb = adbPath else { return (-1, "adb not found") }
        let full = (targetSerial.map { ["-s", $0] } ?? []) + args
        return await Self.exec(adb, full)
    }

    nonisolated static func exec(_ path: String, _ args: [String]) async -> (status: Int32, output: String) {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let p = Process()
                p.executableURL = URL(fileURLWithPath: path)
                p.arguments = args
                let pipe = Pipe()
                p.standardOutput = pipe
                p.standardError = pipe
                do {
                    try p.run()
                } catch {
                    continuation.resume(returning: (-1, error.localizedDescription))
                    return
                }
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                p.waitUntilExit()
                continuation.resume(returning: (p.terminationStatus, String(decoding: data, as: UTF8.self)))
            }
        }
    }

    /// Lets the phone reach us at 127.0.0.1 over the cable.
    func enableReverse() async {
        guard let adb = adbPath, let usb = usbSerial else { return }
        let port = LinkServer.port
        let result = await Self.exec(adb, ["-s", usb, "reverse", "tcp:\(port)", "tcp:\(port)"])
        if result.status != 0 { message = "USB forwarding failed: \(result.output.trimmed)" }
    }

    /// One-time setup for automatic phone→Mac clipboard: lets the phone app notice copies
    /// (via its own log) and bring up its invisible clipboard reader from the background.
    func enableAutomaticClipboard() async -> Bool {
        guard targetSerial != nil else {
            message = "Plug your phone in with USB debugging on, then try again."
            return false
        }
        let pkg = Self.phonePackage
        let grant = await adb(["shell", "pm", "grant", pkg, "android.permission.READ_LOGS"])
        let overlay = await adb(["shell", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"])
        guard grant.status == 0, overlay.status == 0 else {
            message = "Setup failed: \((grant.output + overlay.output).trimmed)"
            return false
        }
        // The log permission only applies to new processes, so restart the app.
        await adb(["shell", "am", "force-stop", pkg])
        await adb(["shell", "am", "start", "-n", "\(pkg)/.ui.MainActivity"])
        message = "Automatic clipboard is on. If your phone asks to allow log access, tap Allow."
        return true
    }

    // MARK: - scrcpy

    /// Downloads the latest scrcpy release for this Mac into Application Support.
    func installScrcpy() async {
        installingScrcpy = true
        defer { installingScrcpy = false }
        struct Release: Decodable {
            struct Asset: Decodable {
                let name: String
                let browser_download_url: URL
            }
            let assets: [Asset]
        }
        #if arch(arm64)
        let arch = "macos-aarch64"
        #else
        let arch = "macos-x86_64"
        #endif
        do {
            let api = URL(string: "https://api.github.com/repos/Genymobile/scrcpy/releases/latest")!
            let (data, _) = try await URLSession.shared.data(from: api)
            let release = try JSONDecoder().decode(Release.self, from: data)
            guard let asset = release.assets.first(where: { $0.name.contains(arch) && $0.name.hasSuffix(".tar.gz") }) else {
                message = "No scrcpy build for this Mac was found."
                return
            }
            let (tmp, _) = try await URLSession.shared.download(from: asset.browser_download_url)
            let archive = Self.supportDirectory.appendingPathComponent("scrcpy.tar.gz")
            try? FileManager.default.removeItem(at: archive)
            try FileManager.default.moveItem(at: tmp, to: archive)
            let dest = Self.supportDirectory.appendingPathComponent("scrcpy", isDirectory: true)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
            let untar = await Self.exec("/usr/bin/tar", ["-xzf", archive.path, "-C", dest.path, "--strip-components", "1"])
            try? FileManager.default.removeItem(at: archive)
            guard untar.status == 0 else {
                message = "Could not unpack scrcpy: \(untar.output.trimmed)"
                return
            }
            scrcpyPath = Self.findScrcpy()
            message = scrcpyPath == nil ? "scrcpy unpacked, but its program wasn't found." : nil
        } catch {
            message = "Downloading scrcpy failed: \(error.localizedDescription)"
        }
    }

    func mirrorScreen(title: String) {
        launchScrcpy(["--window-title=\(title)", "--stay-awake"], feature: "Advanced mirroring")
    }

    /// Opens one app in its own Mac window on a virtual display (scrcpy 3+).
    func openAppWindow(package: String, title: String) {
        launchScrcpy(["--new-display", "--start-app=\(package)", "--window-title=\(title)", "--no-audio"], feature: "Opening apps in Mac windows")
    }

    func openCamera() {
        launchScrcpy(["--video-source=camera", "--camera-facing=front", "--no-audio", "--window-title=Phone Camera"], feature: "The phone camera")
    }

    private func launchScrcpy(_ args: [String], feature: String) {
        guard let scrcpy = scrcpyPath else {
            message = "\(feature) needs scrcpy: Pairing → Advanced → Install mirroring."
            return
        }
        // Don't rely only on background tracking (it restarts if adb does): ask adb now.
        if targetSerial == nil {
            Task {
                await refreshDevices()
                if targetSerial != nil {
                    launchScrcpy(args, feature: feature)
                } else {
                    log.notice("\(feature, privacy: .public): no debugging connection")
                    message = "\(feature) needs a debugging connection: turn on USB debugging on the phone and plug it in "
                        + "(once, if Mirror over Wi-Fi is on). For mirroring without developer settings, use Mirror screen."
                }
            }
            return
        }
        guard let serial = targetSerial else { return } // checked just above
        // Over Wi-Fi, a smaller, lighter stream keeps lag down.
        let tuning = isWireless ? ["--video-bit-rate=4M", "--max-size=1280"] : []
        let p = Process()
        p.executableURL = URL(fileURLWithPath: scrcpy)
        p.arguments = ["-s", serial] + tuning + args
        var env = ProcessInfo.processInfo.environment
        // Share one adb server with ours; two different adb versions kill each other's server.
        if let adb = adbPath { env["ADB"] = adb }
        p.environment = env
        let errors = Pipe()
        p.standardError = errors
        p.standardOutput = FileHandle.nullDevice
        p.terminationHandler = { [weak self] proc in
            let output = String(decoding: errors.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            Task { @MainActor in
                self?.scrcpyProcesses.removeAll { $0 === proc }
                if proc.terminationStatus != 0, proc.terminationReason == .exit {
                    let last = output.split(separator: "\n").last { $0.contains("ERROR") } ?? Substring(output.trimmed)
                    self?.message = "Mirroring stopped: \(last)"
                }
            }
        }
        do {
            try p.run()
            log.notice("started scrcpy for \(feature, privacy: .public) on \(serial, privacy: .public)")
            scrcpyProcesses.append(p)
        } catch {
            message = "Could not start scrcpy: \(error.localizedDescription)"
        }
    }

    func stopAll() {
        tracker?.terminationHandler = nil
        tracker?.terminate()
        wirelessRetry?.invalidate()
        scrcpyProcesses.forEach { $0.terminate() }
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var nonEmpty: String? { isEmpty ? nil : self }
}
