import Darwin
import Foundation
import IOBluetooth
import os

/// AyPhoneBluetooth: publishes the "AyPhone" Bluetooth service and passes the phone's bytes
/// straight through to the app's localhost link server, which handles them like any other
/// connection (same framing, encryption and hello). It runs as its own process because inside
/// the app, macOS gives incoming channels from the phone to the app's hands-free (calls)
/// connection, which then breaks both. Started and stopped by the app (BluetoothBridge).

private let log = Logger(subsystem: "com.ayphone.mac", category: "bluetooth")

/// Must match BLUETOOTH_SERVICE in the Android app's Link.kt.
private let serviceUUID: [UInt8] = [0x6F, 0x3C, 0x1A, 0x52, 0x8B, 0x7E, 0x4D, 0x2A,
                                    0x9C, 0x1E, 0x5A, 0x7F, 0x0B, 0x3D, 0x2E, 0x91]
/// The app's localhost-only link server for Bluetooth (BluetoothBridge.port).
private let appPort: UInt16 = 47802

final class Bridge: NSObject, IOBluetoothRFCOMMChannelDelegate {
    private var record: IOBluetoothSDPServiceRecord?
    private var openNotification: IOBluetoothUserNotification?
    /// The phone's channel and the matching connection to the app.
    private var channel: IOBluetoothRFCOMMChannel?
    private var socketFD: Int32 = -1
    private var readSource: DispatchSourceRead?

    func publish() {
        let service: [String: Any] = [
            "0001 - ServiceClassIDList": [Data(serviceUUID)],
            "0004 - ProtocolDescriptorList": [
                [Data([0x01, 0x00])], // L2CAP
                // RFCOMM, clear of the system's channels (10 is hands-free). A different one each
                // launch, since macOS can hold on to a quit copy's channel for a while; the phone
                // finds it by service ID.
                [Data([0x00, 0x03]), ["DataElementType": 1, "DataElementSize": 1, "DataElementValue": freshChannel()]],
            ],
            "0005 - BrowseGroupList*": [Data([0x10, 0x02])], // public browse group
            "0100 - ServiceName*": "AyPhone",
        ]
        guard let published = IOBluetoothSDPServiceRecord.publishedServiceRecord(with: service) else {
            log.error("couldn't publish the AyPhone Bluetooth service")
            exit(1)
        }
        record = published
        var channelID: BluetoothRFCOMMChannelID = 0
        guard published.getRFCOMMChannelID(&channelID) == kIOReturnSuccess else {
            log.error("published service has no RFCOMM channel")
            exit(1)
        }
        openNotification = IOBluetoothRFCOMMChannel.register(
            forChannelOpenNotifications: self, selector: #selector(channelOpened(_:channel:)),
            withChannelID: channelID, direction: kIOBluetoothUserNotificationChannelDirectionIncoming)
        log.notice("AyPhone Bluetooth service ready on RFCOMM channel \(channelID, privacy: .public)")
    }

    /// A channel none of the last few launches used: macOS can keep refusing a quit copy's
    /// channel (the phone finds the service, then gets turned away), so picking it again by
    /// chance leaves the phone unable to connect.
    private func freshChannel() -> Int {
        let key = "bluetoothRecentChannels"
        let recent = UserDefaults.standard.array(forKey: key) as? [Int] ?? []
        let channel = Array(Set(14...30).subtracting(recent)).randomElement() ?? Int.random(in: 14...30)
        UserDefaults.standard.set(Array(([channel] + recent).prefix(5)), forKey: key)
        return channel
    }

    func stop() {
        closeCurrent()
        openNotification?.unregister()
        record?.remove()
    }

    @objc private func channelOpened(_ notification: IOBluetoothUserNotification, channel newChannel: IOBluetoothRFCOMMChannel) {
        log.notice("phone opened the AyPhone Bluetooth service")
        closeCurrent()
        guard let fd = connectToApp() else {
            log.error("AyPhone isn't listening for the Bluetooth link")
            newChannel.close()
            return
        }
        channel = newChannel
        socketFD = fd
        newChannel.setDelegate(self)
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: .main)
        source.setEventHandler { [weak self] in self?.appSent() }
        source.setCancelHandler { close(fd) }
        source.resume()
        readSource = source
    }

    private func connectToApp() -> Int32? {
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else { return nil }
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &on, socklen_t(MemoryLayout<Int32>.size))
        var sin = sockaddr_in()
        sin.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        sin.sin_family = sa_family_t(AF_INET)
        sin.sin_port = appPort.bigEndian
        sin.sin_addr.s_addr = inet_addr("127.0.0.1")
        let result = withUnsafePointer(to: &sin) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard result == 0 else { close(fd); return nil }
        return fd
    }

    /// App → phone.
    private func appSent() {
        guard let channel else { return }
        var buffer = [UInt8](repeating: 0, count: 64 * 1024)
        let n = Darwin.read(socketFD, &buffer, buffer.count)
        guard n > 0 else {
            log.notice("AyPhone closed the Bluetooth link")
            closeCurrent()
            return
        }
        // RFCOMM writes are limited to the channel's MTU.
        let mtu = max(Int(channel.getMTU()), 64)
        var offset = 0
        while offset < n {
            let chunk = min(mtu, n - offset)
            let result = buffer.withUnsafeMutableBytes { raw in
                channel.writeSync(raw.baseAddress! + offset, length: UInt16(chunk))
            }
            guard result == kIOReturnSuccess else {
                log.error("Bluetooth write failed (\(result, privacy: .public))")
                closeCurrent()
                return
            }
            offset += chunk
        }
    }

    /// Phone → app.
    func rfcommChannelData(_ rfcommChannel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        guard rfcommChannel === channel, socketFD >= 0 else { return }
        var offset = 0
        while offset < dataLength {
            let n = Darwin.write(socketFD, dataPointer + offset, dataLength - offset)
            if n < 0 && errno == EINTR { continue }
            guard n > 0 else {
                closeCurrent()
                return
            }
            offset += n
        }
    }

    func rfcommChannelClosed(_ rfcommChannel: IOBluetoothRFCOMMChannel!) {
        guard rfcommChannel === channel else { return }
        log.notice("Bluetooth link to the phone closed")
        closeCurrent()
    }

    private func closeCurrent() {
        readSource?.cancel()
        readSource = nil
        socketFD = -1
        let old = channel
        channel = nil
        old?.close()
    }
}

let bridge = Bridge()
// IOBluetooth finishes starting up on the run loop, and a service published before then never
// gets incoming channels; wait until it reports Bluetooth on, then a moment more.
_ = IOBluetoothDevice.pairedDevices()
Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { timer in
    guard IOBluetoothHostController.default()?.powerState == kBluetoothHCIPowerStateON else { return }
    timer.invalidate()
    DispatchQueue.main.asyncAfter(deadline: .now() + 1) { bridge.publish() }
}

// Release the service when the app stops us (or quits without doing so).
signal(SIGTERM, SIG_IGN)
let termSource = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
termSource.setEventHandler {
    bridge.stop()
    exit(0)
}
termSource.resume()
let parent = getppid()
Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in
    if getppid() != parent {
        bridge.stop()
        exit(0)
    }
}
RunLoop.main.run()
