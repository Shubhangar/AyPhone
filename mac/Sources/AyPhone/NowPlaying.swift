import Foundation
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "media")

/// Whether the Mac is playing something, and what: for the phone's media controls. macOS hides
/// this from apps Apple hasn't entitled, so NowPlaying.dylib (mac/NowPlaying) runs inside
/// /usr/bin/perl, which is, and reports each change as a line of JSON.
@MainActor
final class NowPlaying {
    struct State: Equatable {
        var playing = false
        var title = ""
        var artist = ""
    }

    private(set) var state = State()
    var onChange: (State) -> Void = { _ in }

    private var process: Process?
    private var input: Pipe?
    private var buffer = Data()
    private var restarts = 0

    func start() {
        guard process == nil,
              let library = Bundle.main.url(forResource: "NowPlaying", withExtension: "dylib") else { return }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/perl")
        p.arguments = ["-e", """
            use DynaLoader;
            my $l = DynaLoader::dl_load_file($ARGV[0]) or exit 1;
            my $s = DynaLoader::dl_find_symbol($l, "ayphone_now_playing") or exit 1;
            DynaLoader::dl_install_xsub("main::watch", $s);
            watch();
            """, library.path]
        let output = Pipe()
        let input = Pipe()
        p.standardOutput = output
        p.standardInput = input
        p.standardError = FileHandle.nullDevice
        output.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.received(data) } }
        }
        p.terminationHandler = { [weak self] _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.stopped() } }
        }
        do {
            try p.run()
            process = p
            self.input = input
        } catch {
            log.error("couldn't start the now-playing watcher: \(error.localizedDescription, privacy: .public)")
        }
    }

    private func received(_ data: Data) {
        buffer.append(data)
        while let newline = buffer.firstIndex(of: 0x0A) {
            let line = buffer[buffer.startIndex..<newline]
            buffer.removeSubrange(buffer.startIndex...newline)
            guard let json = try? JSONSerialization.jsonObject(with: line) as? [String: Any] else { continue }
            let new = State(playing: json["playing"] as? Bool ?? false,
                            title: json["title"] as? String ?? "",
                            artist: json["artist"] as? String ?? "")
            guard new != state else { continue }
            state = new
            onChange(new)
        }
    }

    /// The watcher went away (macOS updated, perl refused): try again a few times, slowly.
    private func stopped() {
        process = nil
        input = nil
        guard restarts < 5 else { return }
        restarts += 1
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            MainActor.assumeIsolated { self?.start() }
        }
    }
}
