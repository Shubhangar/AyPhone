import CryptoKit
import Foundation
import Network
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "link")

/// TCP server the phone connects to. Advertised over Bonjour; also reachable over
/// USB through `adb reverse`. A second, localhost-only instance takes the phone's Bluetooth
/// link from the AyPhoneBluetooth helper. Everything runs on the main queue.
@MainActor
final class LinkServer {
    nonisolated static let port: UInt16 = 47800
    static let maxFrame = 16 * 1024 * 1024

    var key: SymmetricKey
    var onMessage: (Msg) -> Void = { _ in }
    var onConnectionChange: (Bool) -> Void = { _ in }

    private let port: UInt16
    /// Localhost only, no Bonjour: the Bluetooth helper's end.
    private let loopbackOnly: Bool
    private var listener: NWListener?
    /// The authenticated phone connection. A new socket first proves it has the pairing key
    /// (`auth`, answered with `authOk`), then stays pending until its first frame under the
    /// session key, the phone's `hello`, arrives. A replayed `auth` gets nowhere and doesn't
    /// push the real phone off.
    private var connection: NWConnection?
    private var session: LinkSession?
    /// When the current phone connected.
    private(set) var connectedAt: Date?
    private var pending: [ObjectIdentifier: LinkSession] = [:]

    var isConnected: Bool { connection != nil }

    /// The phone's IP address, when it's connected over Wi-Fi.
    var peerHost: String? {
        guard !peerIsLocal, case .hostPort(let host, _) = connection?.endpoint else { return nil }
        var text = "\(host)"
        if let percent = text.firstIndex(of: "%") { text = String(text[..<percent]) }
        return text.replacingOccurrences(of: "::ffff:", with: "")
    }

    /// USB connections arrive through `adb reverse`, so they come from localhost.
    var peerIsLocal: Bool {
        guard case .hostPort(let host, _) = connection?.endpoint else { return false }
        let h = "\(host)"
        return h == "127.0.0.1" || h == "::1" || h.hasSuffix("127.0.0.1")
    }

    /// Re-publishes the Bonjour service so phones still looking for us (e.g. after the Mac
    /// wakes) see a fresh announcement and reconnect right away.
    func reannounce() {
        guard let listener, !loopbackOnly else { return }
        listener.service = nil
        listener.service = NWListener.Service(name: Host.current().localizedName, type: "_droidlink._tcp")
    }

    init(key: SymmetricKey, port: UInt16 = LinkServer.port, loopbackOnly: Bool = false) {
        self.key = key
        self.port = port
        self.loopbackOnly = loopbackOnly
    }

    func start() {
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let listener: NWListener
            if loopbackOnly {
                params.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
                listener = try NWListener(using: params)
            } else {
                listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: port)!)
                listener.service = NWListener.Service(name: Host.current().localizedName, type: "_droidlink._tcp")
            }
            listener.newConnectionHandler = { [weak self] conn in
                MainActor.assumeIsolated { self?.accept(conn) }
            }
            listener.stateUpdateHandler = { state in
                if case .failed(let error) = state { NSLog("AyPhone listener failed: \(error)") }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            NSLog("AyPhone could not listen on port \(self.port): \(error)")
        }
    }

    /// Drops the current phone, e.g. after the pairing key is rotated.
    func disconnect() {
        connection?.cancel()
    }

    func send(_ msg: Msg, completion: (() -> Void)? = nil) {
        guard let conn = connection, let session else { completion?(); return }
        do {
            write(try session.seal(try JSONEncoder().encode(msg)), to: conn, completion: completion)
        } catch {
            NSLog("AyPhone send failed: \(error)")
            completion?()
        }
    }

    private func write(_ box: Data, to conn: NWConnection, completion: (() -> Void)? = nil) {
        var frame = Data(capacity: box.count + 4)
        var len = UInt32(box.count).bigEndian
        withUnsafeBytes(of: &len) { frame.append(contentsOf: $0) }
        frame.append(box)
        conn.send(content: frame, completion: .contentProcessed { _ in
            MainActor.assumeIsolated { completion?() }
        })
    }

    // MARK: - Connection handling

    private func accept(_ conn: NWConnection) {
        conn.stateUpdateHandler = { [weak self, weak conn] state in
            MainActor.assumeIsolated {
                guard let self, let conn else { return }
                switch state {
                case .failed, .cancelled: self.dropped(conn)
                default: break
                }
            }
        }
        conn.start(queue: .main)
        readFrame(conn)
    }

    private func dropped(_ conn: NWConnection) {
        pending[ObjectIdentifier(conn)] = nil
        guard conn === connection else { return }
        log.notice("\(self.loopbackOnly ? "Bluetooth" : "TCP", privacy: .public): phone link closed")
        connection = nil
        session = nil
        connectedAt = nil
        onConnectionChange(false)
    }

    private func readFrame(_ conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 4, maximumLength: 4) { [weak self] header, _, _, error in
            MainActor.assumeIsolated {
                guard let self else { return }
                guard error == nil, let header, header.count == 4 else { conn.cancel(); return }
                let length = header.reduce(0) { Int($0) << 8 | Int($1) }
                guard length > 28, length <= Self.maxFrame else { conn.cancel(); return }
                conn.receive(minimumIncompleteLength: length, maximumLength: length) { body, _, _, error in
                    MainActor.assumeIsolated {
                        guard error == nil, let body, body.count == length else { conn.cancel(); return }
                        if self.handleFrame(body, from: conn) {
                            self.readFrame(conn)
                        }
                    }
                }
            }
        }
    }

    private func handleFrame(_ body: Data, from conn: NWConnection) -> Bool {
        let id = ObjectIdentifier(conn)
        // The current phone and pending ones speak with their session key; a new socket starts
        // with the pairing key.
        let sessionForConn = conn === connection ? session : pending[id]
        let opened: Data? = sessionForConn.map { try? $0.open(body) } ?? (try? LinkCrypto.open(body, key: key))
        guard let plain = opened, let msg = try? JSONDecoder().decode(Msg.self, from: plain) else {
            // Wrong key, garbage, or a repeated or recorded frame: not one of our phones.
            conn.cancel()
            return false
        }
        if sessionForConn == nil {
            // The pairing key only opens a session: a fresh one for each connection, so frames
            // recorded from an earlier one don't open here.
            guard msg.type == "auth", let phoneNonce = msg.nonce.flatMap({ Data(base64Encoded: $0) }),
                  phoneNonce.count == LinkSession.nonceSize,
                  pending.count < 8 else { conn.cancel(); return false }
            let macNonce = LinkSession.randomNonce()
            var ok = Msg("authOk"); ok.nonce = macNonce.base64EncodedString()
            guard let json = try? JSONEncoder().encode(ok), let box = try? LinkCrypto.seal(json, key: key) else {
                conn.cancel(); return false
            }
            pending[id] = LinkSession(pairingKey: key, phoneNonce: phoneNonce, macNonce: macNonce)
            log.notice("\(self.loopbackOnly ? "Bluetooth" : "TCP", privacy: .public): phone authenticated, session opening")
            write(box, to: conn)
            return true
        }
        if conn !== connection {
            guard msg.type == "hello", let newSession = pending.removeValue(forKey: id) else { conn.cancel(); return false }
            let previous = connection
            if previous != nil {
                // Clear everything from the old session (its notifications, a ringing call…);
                // the new one re-sends what's current right after connecting.
                connection = nil
                onConnectionChange(false)
            }
            connection = conn
            session = newSession
            connectedAt = Date()
            log.notice("\(self.loopbackOnly ? "Bluetooth" : "TCP", privacy: .public): phone connected")
            previous?.cancel()
            onConnectionChange(true)
        }
        onMessage(msg)
        return true
    }
}
