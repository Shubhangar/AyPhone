import Foundation
import UniformTypeIdentifiers

struct Transfer: Identifiable, Equatable {
    enum Direction { case toPhone, fromPhone }
    enum Status: Equatable { case queued, active, done, failed }

    var id: String
    var name: String
    var size: Int64
    var transferred: Int64 = 0
    var direction: Direction
    var status: Status = .queued
    var localURL: URL?

    var progress: Double { size > 0 ? Double(transferred) / Double(size) : 0 }
}

/// Chunked file transfer in both directions. Outgoing files are sent one at a time,
/// each chunk waiting for the previous one to be handed to the network.
@MainActor
final class FileTransfers: ObservableObject {
    static let chunkSize = 256 * 1024

    @Published private(set) var transfers: [Transfer] = []

    var send: (Msg, @escaping () -> Void) -> Void = { _, done in done() }
    var onReceived: (URL) -> Void = { _ in }

    private var outgoingQueue: [(id: String, url: URL)] = []
    private var sending = false
    private var incoming: [String: FileHandle] = [:]

    // MARK: Outgoing

    func enqueue(_ url: URL) {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        let id = UUID().uuidString
        transfers.insert(Transfer(id: id, name: url.lastPathComponent, size: size, direction: .toPhone, localURL: url), at: 0)
        outgoingQueue.append((id, url))
        pump()
    }

    private func pump() {
        guard !sending, !outgoingQueue.isEmpty else { return }
        sending = true
        let next = outgoingQueue.removeFirst()
        guard let handle = try? FileHandle(forReadingFrom: next.url) else {
            update(next.id) { $0.status = .failed }
            sending = false
            pump()
            return
        }
        update(next.id) { $0.status = .active }
        let t = transfers.first { $0.id == next.id }!
        var offer = Msg("fileOffer")
        offer.id = next.id
        offer.name = t.name
        offer.size = t.size
        offer.mime = UTType(filenameExtension: next.url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        send(offer) { [weak self] in self?.sendChunk(id: next.id, handle: handle, seq: 0) }
    }

    private func sendChunk(id: String, handle: FileHandle, seq: Int) {
        let data = (try? handle.read(upToCount: Self.chunkSize)) ?? nil
        guard let data, !data.isEmpty else {
            try? handle.close()
            var end = Msg("fileEnd"); end.id = id
            send(end) { [weak self] in
                guard let self else { return }
                self.update(id) { $0.status = .done; $0.transferred = $0.size }
                self.sending = false
                self.pump()
            }
            return
        }
        var chunk = Msg("fileChunk")
        chunk.id = id
        chunk.seq = seq
        chunk.data = data.base64EncodedString()
        send(chunk) { [weak self] in
            self?.update(id) { $0.transferred += Int64(data.count) }
            self?.sendChunk(id: id, handle: handle, seq: seq + 1)
        }
    }

    // MARK: Incoming

    func handle(_ m: Msg) {
        guard let id = m.id else { return }
        switch m.type {
        case "fileOffer":
            let url = uniqueDownloadURL(for: m.name ?? "file")
            FileManager.default.createFile(atPath: url.path, contents: nil)
            guard let handle = try? FileHandle(forWritingTo: url) else { return }
            incoming[id] = handle
            transfers.insert(Transfer(id: id, name: url.lastPathComponent, size: m.size ?? 0,
                                      direction: .fromPhone, status: .active, localURL: url), at: 0)
        case "fileChunk":
            guard let handle = incoming[id], let b64 = m.data, let data = Data(base64Encoded: b64) else { return }
            handle.write(data)
            update(id) { $0.transferred += Int64(data.count) }
        case "fileEnd":
            guard let handle = incoming.removeValue(forKey: id) else { return }
            try? handle.close()
            update(id) { $0.status = .done; $0.transferred = max($0.transferred, $0.size) }
            if let url = transfers.first(where: { $0.id == id })?.localURL { onReceived(url) }
        default:
            break
        }
    }

    func cancelIncoming() {
        for (id, handle) in incoming {
            try? handle.close()
            update(id) { $0.status = .failed }
        }
        incoming.removeAll()
        // Anything still queued for the phone can't go anywhere now.
        for item in outgoingQueue { update(item.id) { $0.status = .failed } }
        outgoingQueue.removeAll()
    }

    func clearFinished() {
        transfers.removeAll { $0.status == .done || $0.status == .failed }
    }

    private func update(_ id: String, _ change: (inout Transfer) -> Void) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        change(&transfers[i])
    }

    private func uniqueDownloadURL(for rawName: String) -> URL {
        let name = (rawName as NSString).lastPathComponent.replacingOccurrences(of: ":", with: "-")
        let dir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
        var url = dir.appendingPathComponent(name.isEmpty ? "file" : name)
        let base = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var n = 1
        while FileManager.default.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return url
    }
}
