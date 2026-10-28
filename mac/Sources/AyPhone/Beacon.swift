import Darwin
import Foundation

/// "AyPhone is here": a small UDP broadcast every few seconds while the phone isn't connected,
/// so it can find the Mac on networks that block Bonjour. Carries only the port and the Mac's
/// name; the phone still has to pass the encrypted pairing handshake.
@MainActor
final class Beacon {
    static let port: UInt16 = 47801

    /// Whether to send right now (only while no phone is connected).
    var shouldSend: () -> Bool = { true }

    private var socketFD: Int32 = -1
    private var timer: Timer?
    private lazy var message = "AYPHONE1 \(LinkServer.port) \(Host.current().localizedName ?? "Mac")"

    func start() {
        guard timer == nil else { return }
        socketFD = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socketFD >= 0 else { return }
        var on: Int32 = 1
        setsockopt(socketFD, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size))
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
    }

    private func tick() {
        guard shouldSend() else { return }
        let bytes = Array(message.utf8)
        for address in ["255.255.255.255"] + Self.broadcastAddresses() {
            var sin = sockaddr_in()
            sin.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
            sin.sin_family = sa_family_t(AF_INET)
            sin.sin_port = Self.port.bigEndian
            guard inet_pton(AF_INET, address, &sin.sin_addr) == 1 else { continue }
            withUnsafePointer(to: &sin) { pointer in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                    _ = sendto(socketFD, bytes, bytes.count, 0, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
    }

    /// The broadcast address of each active Wi-Fi/Ethernet network (e.g. 192.168.10.255).
    private static func broadcastAddresses() -> [String] {
        var result: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return [] }
        defer { freeifaddrs(ifaddr) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            let flags = Int32(ifa.ifa_flags)
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_BROADCAST != 0, flags & IFF_LOOPBACK == 0,
                  let broadcast = ifa.ifa_dstaddr else { continue }
            let name = String(cString: ifa.ifa_name)
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(broadcast, socklen_t(broadcast.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return result
    }
}
