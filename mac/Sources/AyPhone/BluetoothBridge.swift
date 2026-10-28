import CryptoKit
import Foundation
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "bluetooth")

/// The Bluetooth fallback, for when Wi-Fi keeps the phone and Mac apart (client isolation) and
/// there's no cable. The AyPhoneBluetooth helper next to the app's executable owns the
/// "AyPhone" Bluetooth service and relays the phone's bytes to a localhost link server here.
/// It has to be a separate process: inside the app, macOS hands the phone's incoming channel
/// to the hands-free (calls) connection instead.
@MainActor
final class BluetoothBridge {
    /// The localhost link server's port (appPort in the helper).
    static let port: UInt16 = 47802

    private var process: Process?
    private var stopping = false

    func start() {
        guard process == nil, !stopping else { return }
        guard let helper = Bundle.main.executableURL?.deletingLastPathComponent().appendingPathComponent("AyPhoneBluetooth"),
              FileManager.default.isExecutableFile(atPath: helper.path) else {
            log.error("AyPhoneBluetooth helper is missing; no Bluetooth fallback")
            return
        }
        let p = Process()
        p.executableURL = helper
        p.terminationHandler = { [weak self] proc in
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, self.process === proc else { return }
                    self.process = nil
                    guard !self.stopping else { return }
                    log.error("Bluetooth helper exited (\(proc.terminationStatus, privacy: .public)); restarting")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                        MainActor.assumeIsolated { self.start() }
                    }
                }
            }
        }
        do {
            try p.run()
            process = p
        } catch {
            log.error("couldn't start the Bluetooth helper: \(error.localizedDescription, privacy: .public)")
        }
    }

    func stop() {
        stopping = true
        process?.terminate()
        process = nil
    }
}

/// One connection for the rest of the app, over whichever link is up: TCP (Wi-Fi/USB) when
/// possible, Bluetooth as the fallback.
@MainActor
final class LinkRouter {
    let tcp: LinkServer
    /// The phone's Bluetooth link, relayed by the helper.
    let bluetooth: LinkServer
    private let bridge = BluetoothBridge()

    var key: SymmetricKey {
        didSet {
            tcp.key = key
            bluetooth.key = key
        }
    }
    var onMessage: (Msg) -> Void = { _ in }
    var onConnectionChange: (Bool) -> Void = { _ in }

    init(key: SymmetricKey) {
        self.key = key
        tcp = LinkServer(key: key)
        bluetooth = LinkServer(key: key, port: BluetoothBridge.port, loopbackOnly: true)
        tcp.onMessage = { [weak self] in self?.onMessage($0) }
        bluetooth.onMessage = { [weak self] in self?.onMessage($0) }
        tcp.onConnectionChange = { [weak self] up in
            guard let self else { return }
            // Prefer Wi-Fi/USB: drop the Bluetooth fallback once TCP is up.
            if up && self.bluetooth.isConnected { self.bluetooth.disconnect() }
            self.onConnectionChange(up || self.bluetooth.isConnected)
        }
        bluetooth.onConnectionChange = { [weak self] up in
            guard let self else { return }
            if up { log.notice("phone connected over Bluetooth") }
            if up && self.tcp.isConnected {
                self.bluetooth.disconnect()
                return
            }
            // Bluetooth closing while TCP is up (we dropped it) isn't a disconnect.
            if !up && self.tcp.isConnected { return }
            self.onConnectionChange(up)
        }
    }

    var isConnected: Bool { tcp.isConnected || bluetooth.isConnected }
    var isBluetooth: Bool { !tcp.isConnected && bluetooth.isConnected }
    var peerIsLocal: Bool { tcp.peerIsLocal }
    var peerHost: String? { tcp.peerHost }

    func start() {
        tcp.start()
        bluetooth.start()
        bridge.start()
    }

    func send(_ msg: Msg, completion: (() -> Void)? = nil) {
        if tcp.isConnected {
            tcp.send(msg, completion: completion)
        } else {
            bluetooth.send(msg, completion: completion)
        }
    }

    func disconnect() {
        tcp.disconnect()
        bluetooth.disconnect()
    }

    /// Stops the Bluetooth helper, which releases the Bluetooth service.
    func stop() {
        bridge.stop()
    }

    func reannounce() {
        tcp.reannounce()
    }
}
