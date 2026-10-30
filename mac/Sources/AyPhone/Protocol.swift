import CryptoKit
import Foundation

/// One wire message. A flat struct with optional fields keeps the JSON trivial to
/// produce and parse on Android (see PROTOCOL.md). Nil fields are omitted when encoding.
struct Msg: Codable {
    var type: String

    var id: String?
    var key: String?
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

/// The pairing key shared with the phone through the QR code.
enum Pairing {
    private static let defaultsKey = "pairingKey"

    static func loadOrCreateKey() -> SymmetricKey {
        if let b64 = UserDefaults.standard.string(forKey: defaultsKey), let data = Data(base64Encoded: b64) {
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
