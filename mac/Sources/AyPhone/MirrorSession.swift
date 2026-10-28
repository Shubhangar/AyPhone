import AVFoundation
import AppKit
import CoreMedia
import SwiftUI
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "mirror")

/// Standard mirroring: the phone shares its screen through Android's screen-capture API (no
/// debugging needed) as H.264 over the AyPhone link; this decodes it with the hardware decoder
/// (AVSampleBufferDisplayLayer), plays the app sound, and turns mouse and keyboard input into
/// taps, swipes and text for the phone's control service.
@MainActor
final class MirrorSession: ObservableObject {
    enum Phase: Equatable {
        case idle
        /// Waiting for the user to tap "Start" on the phone's sharing prompt.
        case requesting
        case running
        case denied
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var hasPicture = false
    @Published private(set) var videoSize = CGSize(width: 9, height: 19.5)
    /// Whether the phone's control service is on (clicks and typing reach the phone).
    @Published private(set) var controlAvailable = false

    var send: (Msg) -> Void = { _ in }
    var phoneName = "Phone"
    let displayLayer = AVSampleBufferDisplayLayer()

    private var formatDescription: CMVideoFormatDescription?
    private var waitingForKeyFrame = true
    private var window: MirrorWindowController?
    private let audio = MirrorAudioPlayer()

    var isActive: Bool { phase == .requesting || phase == .running }

    func start() {
        if window == nil { window = MirrorWindowController(session: self) }
        window?.show(title: "\(phoneName) · Mirroring")
        guard !isActive else { return }
        log.notice("asking the phone to share its screen")
        phase = .requesting
        var m = Msg("mirrorStart")
        m.maxSize = 1280
        send(m)
    }

    func stop() {
        if isActive { send(Msg("mirrorStop")) }
        reset()
    }

    func windowClosed() {
        stop()
        window = nil
    }

    func connectionLost() {
        reset()
        if window != nil { phase = .failed("Your phone disconnected.") }
    }

    private func reset() {
        phase = .idle
        hasPicture = false
        formatDescription = nil
        waitingForKeyFrame = true
        displayLayer.sampleBufferRenderer.flush(removingDisplayedImage: true, completionHandler: nil)
        audio.stop()
    }

    // MARK: Incoming

    func handle(_ m: Msg) {
        switch m.type {
        case "mirrorState":
            switch m.state {
            case "requesting": phase = .requesting
            case "running":
                log.notice("phone is sharing its screen (control: \(m.control ?? false, privacy: .public))")
                // Started from the phone side: open the window.
                if window == nil {
                    window = MirrorWindowController(session: self)
                    window?.show(title: "\(phoneName) · Mirroring")
                }
                phase = .running
                if let control = m.control { controlAvailable = control }
            case "denied":
                log.notice("sharing declined on the phone")
                phase = .denied
            case "stopped": reset()
            default: phase = .failed("The phone couldn't start sharing its screen.")
            }
        case "mirrorConfig":
            guard let b64 = m.data, let data = Data(base64Encoded: b64) else { return }
            configure(data, width: m.width ?? 0, height: m.height ?? 0)
        case "mirrorFrame":
            guard let b64 = m.data, let data = Data(base64Encoded: b64) else { return }
            decode(data, keyFrame: m.keyFrame ?? false)
        case "mirrorAudio":
            guard isActive, let b64 = m.data, let data = Data(base64Encoded: b64) else { return }
            audio.play(data)
        default:
            break
        }
    }

    /// SPS/PPS from the phone's encoder describe the stream (sent again after rotation).
    private func configure(_ data: Data, width: Int, height: Int) {
        let units = Self.nalUnits(data)
        guard let sps = units.first(where: { ($0.first ?? 0) & 0x1F == 7 }),
              let pps = units.first(where: { ($0.first ?? 0) & 0x1F == 8 }) else { return }
        var description: CMFormatDescription?
        let status = sps.withUnsafeBytes { s in
            pps.withUnsafeBytes { p in
                let pointers = [s.bindMemory(to: UInt8.self).baseAddress!, p.bindMemory(to: UInt8.self).baseAddress!]
                let sizes = [sps.count, pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault, parameterSetCount: 2, parameterSetPointers: pointers,
                    parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &description)
            }
        }
        guard status == noErr, let description else {
            log.error("bad stream parameters (status \(status, privacy: .public))")
            return
        }
        log.notice("stream \(width, privacy: .public)x\(height, privacy: .public)")
        formatDescription = description
        waitingForKeyFrame = true
        if phase == .requesting { phase = .running }
        if width > 0, height > 0 {
            let size = CGSize(width: width, height: height)
            if size != videoSize {
                videoSize = size
                window?.fit(to: size)
            }
        }
    }

    private func decode(_ data: Data, keyFrame: Bool) {
        guard let formatDescription else { return }
        if waitingForKeyFrame && !keyFrame { return }
        let renderer = displayLayer.sampleBufferRenderer
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            log.error("decoder needs a reset: \(String(describing: renderer.error), privacy: .public)")
            renderer.flush()
            waitingForKeyFrame = true
            send(Msg("mirrorKeyframe"))
            return
        }
        waitingForKeyFrame = false
        guard let sample = Self.sampleBuffer(fromAnnexB: data, format: formatDescription) else { return }
        renderer.enqueue(sample)
        if !hasPicture {
            hasPicture = true
            log.notice("first picture on screen")
        }
    }

    // MARK: H.264 plumbing

    /// Splits an Annex B stream (units separated by 00 00 01 / 00 00 00 01) into NAL units.
    static func nalUnits(_ data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var starts: [(code: Int, payload: Int)] = []
        var i = 0
        while i + 3 <= bytes.count {
            if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                starts.append((i, i + 3))
                i += 3
            } else if i + 4 <= bytes.count, bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 0, bytes[i + 3] == 1 {
                starts.append((i, i + 4))
                i += 4
            } else {
                i += 1
            }
        }
        return starts.enumerated().compactMap { k, start in
            let end = k + 1 < starts.count ? starts[k + 1].code : bytes.count
            return end > start.payload ? Data(bytes[start.payload..<end]) : nil
        }
    }

    /// Repackages Annex B into the length-prefixed (AVCC) form CoreMedia expects.
    static func sampleBuffer(fromAnnexB data: Data, format: CMFormatDescription) -> CMSampleBuffer? {
        var avcc = Data()
        for unit in nalUnits(data) {
            let type = (unit.first ?? 0) & 0x1F
            if type == 7 || type == 8 { continue } // parameter sets travel separately
            var length = UInt32(unit.count).bigEndian
            avcc.append(Data(bytes: &length, count: 4))
            avcc.append(unit)
        }
        guard !avcc.isEmpty else { return nil }
        var block: CMBlockBuffer?
        let status = avcc.withUnsafeBytes { raw -> OSStatus in
            let created = CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: avcc.count,
                blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
                dataLength: avcc.count, flags: 0, blockBufferOut: &block)
            guard created == noErr, let block else { return created }
            return CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: block,
                                                 offsetIntoDestination: 0, dataLength: avcc.count)
        }
        guard status == noErr, let block else { return nil }
        var sample: CMSampleBuffer?
        var size = avcc.count
        guard CMSampleBufferCreateReady(allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
                                        sampleCount: 1, sampleTimingEntryCount: 0, sampleTimingArray: nil,
                                        sampleSizeEntryCount: 1, sampleSizeArray: &size,
                                        sampleBufferOut: &sample) == noErr, let sample else { return nil }
        // No timestamps: show each frame the moment it arrives.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }
        return sample
    }

    // MARK: Control (normalized 0–1 screen coordinates)

    func tap(x: Double, y: Double, long: Bool) {
        var m = Msg("mirrorTap"); m.x = x; m.y = y; m.action = long ? "long" : "tap"
        send(m)
    }

    func swipe(from a: CGPoint, to b: CGPoint, durationMs: Int) {
        var m = Msg("mirrorSwipe"); m.x = a.x; m.y = a.y; m.x2 = b.x; m.y2 = b.y; m.duration = durationMs
        send(m)
    }

    func key(_ name: String) {
        var m = Msg("mirrorKey"); m.action = name
        send(m)
    }

    func type(_ text: String) {
        var m = Msg("mirrorText"); m.text = text
        send(m)
    }
}

/// Plays the phone's 48 kHz 16-bit stereo PCM, dropping chunks if they pile up so sound
/// stays in step with the picture.
@MainActor
final class MirrorAudioPlayer {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
    private var attached = false
    private var pending = 0

    func play(_ data: Data) {
        if !attached {
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            attached = true
        }
        if !engine.isRunning {
            do { try engine.start() } catch { return }
            player.play()
        }
        guard pending < 8 else { return } // about 320 ms queued: skip to catch up
        let frames = data.count / 4
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let channels = buffer.floatChannelData else { return }
        buffer.frameLength = AVAudioFrameCount(frames)
        data.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            for i in 0..<frames {
                channels[0][i] = Float(Int16(littleEndian: samples[2 * i])) / 32768
                channels[1][i] = Float(Int16(littleEndian: samples[2 * i + 1])) / 32768
            }
        }
        pending += 1
        player.scheduleBuffer(buffer) { [weak self] in
            Task { @MainActor in self?.pending -= 1 }
        }
    }

    func stop() {
        player.stop()
        engine.stop()
        pending = 0
    }
}
