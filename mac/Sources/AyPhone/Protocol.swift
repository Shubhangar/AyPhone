import CryptoKit
import Foundation
import Security

/// One wire message. A flat struct with optional fields keeps the JSON trivial to
/// produce and parse on Android (see PROTOCOL.md). Nil fields are omitted when encoding.
struct Msg: Codable {
    var type: String

    var id: String?
    var key: String?
    /// auth / authOk: each side's random contribution to the session key (base64).
    var nonce: String?
    var name: String?
    var model: String?

    // Notifications
    var pkg: String?
    var app: String?
    var title: String?
    var text: String?
    var time: Double?
    var canReply: Bool?
    var actions: [String]?
    var icon: String?
    var index: Int?
    /// True for notifications re-sent on connect: list them, don't banner them.
    var silent: Bool?
    /// The phone's verdict on an updated notification: true when it carries a new incoming message.
    var alert: Bool?
    /// A call notification from any app (dialer, WhatsApp…), and whether it's ringing.
    var call: Bool?
    var callType: String?

    // Battery
    var level: Int?
    var charging: Bool?

    // URLs and files
    var url: String?
    var size: Int64?
    var mime: String?
    /// fileOffer: sent from the Files option, so the other side plays the transfer glow.
    var glow: Bool?
    var seq: Int?
    var data: String?

    // SMS
    var threadId: Int64?
    var address: String?
    var threads: [SmsThread]?
    var messages: [SmsMessage]?

    // Calls, media, find phone
    var state: String?
    var number: String?
    /// call: a second caller ringing during this call, and whether the phone's call companion
    /// (which sees every call separately) sent it.
    var waitingNumber: String?
    var waitingName: String?
    var telecom: Bool?
    var action: String?
    var artist: String?
    var playing: Bool?
    /// callSpeaker: whether the call's sound is on the phone's loudspeaker.
    var on: Bool?
    var art: String?

    // App control
    var apps: [PhoneApp]?
    var photos: [PhonePhoto]?

    // Contacts
    var contacts: [PhoneContact]?

    // Standard mirroring
    var width: Int?
    var height: Int?
    var keyFrame: Bool?
    var control: Bool?
    var maxSize: Int?
    var x: Double?
    var y: Double?
    var x2: Double?
    var y2: Double?
    var duration: Int?

    init(_ type: String) { self.type = type }
}

struct PhoneContact: Codable, Hashable {
    var name: String
    var numbers: [String]
}

/// A photo on the phone, with a small JPEG thumbnail (base64).
struct PhonePhoto: Codable, Identifiable, Hashable {
    var id: String
    var name: String
    var date: Double
    var thumb: String
    var screenshot: Bool?
}

struct PhoneApp: Codable, Identifiable, Hashable {
    var pkg: String
    var name: String
    var icon: String?
    var id: String { pkg }
}

struct SmsThread: Codable, Identifiable, Hashable {
    var id: Int64
    var address: String
    var name: String?
    var snippet: String
    var date: Double
    var unread: Bool?
}

struct SmsMessage: Codable, Identifiable, Hashable {
    var id: Int64
    var threadId: Int64
    var address: String
    var body: String
    var date: Double
    var outgoing: Bool
}

/// AES-256-GCM. CryptoKit's `combined` layout (nonce | ciphertext | tag) matches
/// what the Android side produces with `AES/GCM/NoPadding`.
enum LinkCrypto {
    static func seal(_ plain: Data, key: SymmetricKey) throws -> Data {
        try AES.GCM.seal(plain, using: key).combined!
    }

    static func open(_ box: Data, key: SymmetricKey) throws -> Data {
        try AES.GCM.open(AES.GCM.SealedBox(combined: box), using: key)
    }
}

/// One connection's encryption. Its key comes from the pairing key and both sides' random
/// nonces, so it's new every time. Nonces are a direction byte and a counter, and each frame
/// must carry the next one: a frame repeated within the session is refused too.
final class LinkSession {
    static let nonceSize = 16
    private static let fromMac: UInt8 = 0x4D, fromPhone: UInt8 = 0x50

    private let key: SymmetricKey
    private var sent: UInt64 = 0
    private var received: UInt64 = 0

    init(pairingKey: SymmetricKey, phoneNonce: Data, macNonce: Data) {
        key = HKDF<SHA256>.deriveKey(inputKeyMaterial: pairingKey, salt: phoneNonce + macNonce,
                                     info: Data("AyPhone session".utf8), outputByteCount: 32)
    }

    static func randomNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: nonceSize)
        _ = SecRandomCopyBytes(kSecRandomDefault, nonceSize, &bytes)
        return Data(bytes)
    }

    func seal(_ plain: Data) throws -> Data {
        let nonce = try AES.GCM.Nonce(data: Self.nonce(Self.fromMac, sent))
        sent += 1
        return try AES.GCM.seal(plain, using: key, nonce: nonce).combined!
    }

    func open(_ box: Data) throws -> Data {
        guard box.count > 12, box.prefix(12) == Self.nonce(Self.fromPhone, received) else {
            throw CryptoKitError.authenticationFailure
        }
        let plain = try LinkCrypto.open(box, key: key)
        received += 1
        return plain
    }

    private static func nonce(_ direction: UInt8, _ counter: UInt64) -> Data {
        var data = Data([direction, 0, 0, 0])
        withUnsafeBytes(of: counter.bigEndian) { data.append(contentsOf: $0) }
        return data
    }
}

/// The pairing key shared with the phone through the QR code.
/// Kept in AyPhone's preferences. (0.1.7 kept it in the login Keychain, but without an Apple
/// developer ID the Keychain treats every update as a different app and stops AyPhone at launch
/// with a password prompt. It moves back here once.)
enum Pairing {
    private static let defaultsKey = "pairingKey"

    static func loadOrCreateKey() -> SymmetricKey {
        if let b64 = UserDefaults.standard.string(forKey: defaultsKey), let data = Data(base64Encoded: b64) {
            return SymmetricKey(data: data)
        }
        if let data = takeFromKeychain() {
            UserDefaults.standard.set(data.base64EncodedString(), forKey: defaultsKey)
            return SymmetricKey(data: data)
        }
        return resetKey()
    }

    @discardableResult
    static func resetKey() -> SymmetricKey {
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        UserDefaults.standard.set(data.base64EncodedString(), forKey: defaultsKey)
        return key
    }

    /// The key 0.1.7 stored in the Keychain (macOS may ask once), removed from there.
    private static func takeFromKeychain() -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.ayphone.mac.pairing",
            kSecAttrAccount as String: "pairingKey",
        ]
        var read = query
        read[kSecReturnData as String] = true
        var result: CFTypeRef?
        guard SecItemCopyMatching(read as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        SecItemDelete(query as CFDictionary)
        return data
    }

    static func qrPayload(key: SymmetricKey, port: UInt16) -> String {
        let raw = key.withUnsafeBytes { Data($0) }.base64EncodedString()
        let k = raw.replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let hosts = NetworkInfo.localIPv4Addresses().joined(separator: ",")
        let name = (Host.current().localizedName ?? "Mac")
            .addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "Mac"
        return "droidlink://pair?k=\(k)&h=\(hosts)&p=\(port)&n=\(name)"
    }
}

enum NetworkInfo {
    /// IPv4 addresses of active, non-loopback interfaces (Wi-Fi, Ethernet).
    static func localIPv4Addresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { continue }
            let flags = Int32(ifa.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return result
    }
}
