import Foundation
import AVFoundation
import CryptoKit
import UIKit
import Combine

struct EvidencePiece: Codable, Identifiable {
    var id: String { filename }
    let filename: String
    let kind: String
    let startedAt: Date
    let endedAt: Date
    let bytes: Int
    let sha256: String
    let chain: String
    let fix: Fix?
}
struct EvidenceCase: Codable, Identifiable {
    let id: String
    let startedAt: Date
    var endedAt: Date?
    var pieces: [EvidencePiece] = []
    var problems: [String] = []
}

@MainActor
final class EvidenceStore: NSObject, ObservableObject, AVAudioRecorderDelegate, AVCaptureFileOutputRecordingDelegate {
    @Published var cases: [EvidenceCase] = []
    @Published var recordingId: String?
    @Published var audioOn = false
    @Published var videoOn = false
    @Published var error: String?
    private var audio: AVAudioRecorder?
    private let capture = AVCaptureSession()
    private let video = AVCaptureMovieFileOutput()
    private let captureQueue = DispatchQueue(label: "in.security4her.camera")
    private var videoTimer: Timer?
    private var audioStart = Date()
    private var videoStart = Date()
    private var wantsAudio = false
    private var wantsVideo = false
    private var foreground = true
    private var sequence = 0
    private var pendingVideoCase: String?
    var location: (() -> Fix?)?
    private var root: URL { Disk.root.appendingPathComponent("evidence", isDirectory: true) }
    override init() {
        super.init()
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        reload()
        // A killed process cannot still be recording. Close its manifest, but never invent missing media.
        for var item in cases where item.endedAt == nil {
            item.endedAt = item.pieces.last?.endedAt ?? item.startedAt
            item.problems.append("Recording was interrupted when the app stopped.")
            save(item)
        }
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.problem("Audio was interrupted by the system. Open the app to review recording status."); self?.audioOn = false }
        }
        NotificationCenter.default.addObserver(forName: .AVCaptureSessionRuntimeError, object: capture, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.problem("Camera recording was interrupted."); self?.videoOn = false }
        }
    }
    func directory(_ id: String) -> URL { root.appendingPathComponent(id, isDirectory: true) }
    func file(_ item: EvidenceCase, _ piece: EvidencePiece) -> URL { directory(item.id).appendingPathComponent(piece.filename) }
    private func reload() {
        cases = ((try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []).compactMap {
            guard let data = try? Data(contentsOf: $0.appendingPathComponent("manifest.json")) else { return nil }
            return try? JSONDecoder().decode(EvidenceCase.self, from: data)
        }.sorted { $0.startedAt > $1.startedAt }
    }
    private func save(_ item: EvidenceCase) {
        do {
            try FileManager.default.createDirectory(at: directory(item.id), withIntermediateDirectories: true)
            try JSONEncoder().encode(item).write(to: directory(item.id).appendingPathComponent("manifest.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            cases.removeAll { $0.id == item.id }; cases.append(item); cases.sort { $0.startedAt > $1.startedAt }
        } catch { self.error = "Evidence could not be saved: \(error.localizedDescription)" }
    }
    func start(id: String, audio enabledAudio: Bool, video enabledVideo: Bool) async {
        guard recordingId == nil else { return }
        recordingId = id; sequence = 0
        save(.init(id: id, startedAt: Date()))
        wantsAudio = enabledAudio; wantsVideo = enabledVideo
        do {
            try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            if enabledAudio {
                let granted = await AVCaptureDevice.requestAccess(for: .audio)
                guard recordingId == id else { return }
                if granted { nextAudio() } else { problem("Microphone permission denied.") }
            }
            if enabledVideo {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                guard recordingId == id else { return }
                if granted && foreground { configureCamera(); nextVideo() }
                else { problem(granted ? "Video requires the app to be visible." : "Camera permission denied.") }
            }
        } catch { problem(error.localizedDescription) }
    }
    private func nextURL(_ kind: String, extension ext: String) -> URL? {
        guard let id = recordingId else { return nil }
        sequence += 1
        return directory(id).appendingPathComponent(String(format: "%@-%05d.%@", kind, sequence, ext))
    }
    private func nextAudio() {
        guard wantsAudio, let url = nextURL("audio", extension: "m4a") else { return }
        do {
            audioStart = Date()
            let recorder = try AVAudioRecorder(url: url, settings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 22050, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32000])
            recorder.delegate = self; audio = recorder
            audioOn = recorder.record(forDuration: 15)
            if !audioOn { problem("Audio recording could not start.") }
        } catch { problem(error.localizedDescription) }
    }
    private func configureCamera() {
        guard capture.inputs.isEmpty else { return }
        capture.beginConfiguration(); capture.sessionPreset = .medium
        defer { capture.commitConfiguration() }
        do {
            guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) else { throw AppFailure.message("No back camera available.") }
            let input = try AVCaptureDeviceInput(device: device)
            guard capture.canAddInput(input), capture.canAddOutput(video) else { throw AppFailure.message("Camera cannot be configured.") }
            capture.addInput(input); capture.addOutput(video)
            // Audio is captured separately, avoiding competing microphone capture inputs.
            video.maxRecordedDuration = CMTime(seconds: 30, preferredTimescale: 600)
            captureQueue.async { [capture] in capture.startRunning() }
        } catch { problem(error.localizedDescription) }
    }
    private func nextVideo() {
        guard wantsVideo, foreground, !capture.inputs.isEmpty, !video.isRecording,
              let url = nextURL("video", extension: "mov") else { return }
        videoStart = Date(); pendingVideoCase = recordingId
        video.startRecording(to: url, recordingDelegate: self); videoOn = true
    }
    func setForeground(_ value: Bool) {
        foreground = value
        if !value {
            if video.isRecording { video.stopRecording() }
            videoOn = false
            captureQueue.async { [capture] in capture.stopRunning() }
        } else if recordingId != nil && wantsVideo {
            if capture.inputs.isEmpty { configureCamera() }
            else { captureQueue.async { [capture] in capture.startRunning() } }
            nextVideo()
        }
    }
    func stop() {
        guard let id = recordingId else { return }
        wantsAudio = false; wantsVideo = false
        if let audio {
            audio.delegate = nil; audio.stop()
            add(id: id, url: audio.url, kind: "audio", start: audioStart)
        }
        audio = nil; audioOn = false
        if video.isRecording { video.stopRecording() }
        videoOn = false
        captureQueue.async { [capture] in capture.stopRunning() }
        if var item = cases.first(where: { $0.id == id }) { item.endedAt = Date(); save(item) }
        recordingId = nil
    }
    private func problem(_ text: String) {
        error = text
        if let id = recordingId, var item = cases.first(where: { $0.id == id }) {
            if !item.problems.contains(text) { item.problems.append(text); save(item) }
        }
    }
    private func add(id: String, url: URL, kind: String, start: Date) {
        guard var item = cases.first(where: { $0.id == id }), !item.pieces.contains(where: { $0.filename == url.lastPathComponent }),
              let bytes = try? Data(contentsOf: url), !bytes.isEmpty else { return }
        let hash = Self.hash(bytes)
        let chain = Self.hash(Data(((item.pieces.last?.chain ?? String(repeating: "0", count: 64)) + hash).utf8))
        item.pieces.append(.init(filename: url.lastPathComponent, kind: kind, startedAt: start, endedAt: Date(), bytes: bytes.count, sha256: hash, chain: chain, fix: location?()))
        save(item)
    }
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor in
            guard let id = self.recordingId, recorder === self.audio else { return }
            if flag { self.add(id: id, url: recorder.url, kind: "audio", start: self.audioStart) }
            else { self.problem("An audio segment did not finish successfully.") }
            self.audioOn = false
            if flag { self.nextAudio() }
        }
    }
    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                               from connections: [AVCaptureConnection], error: Error?) {
        let completed = error == nil || (error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool == true
        Task { @MainActor in
            if let id = self.pendingVideoCase, completed { self.add(id: id, url: outputFileURL, kind: "video", start: self.videoStart) }
            else if let error { self.problem(error.localizedDescription) }
            self.videoOn = false; self.pendingVideoCase = nil
            self.nextVideo()
        }
    }
    static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    func verify(_ item: EvidenceCase) -> Bool {
        var previous = String(repeating: "0", count: 64)
        for piece in item.pieces {
            guard let data = try? Data(contentsOf: file(item, piece)), Self.hash(data) == piece.sha256,
                  Self.hash(Data((previous + piece.sha256).utf8)) == piece.chain else { return false }
            previous = piece.chain
        }
        return true
    }
    func delete(_ item: EvidenceCase, pin: String) throws {
        guard item.id != recordingId, pendingVideoCase != item.id, let end = item.endedAt, Date().timeIntervalSince(end) >= 86400 else { throw AppFailure.message("Evidence is locked while recording and for 24 hours afterwards.") }
        guard SecureStore.acceptsPIN(pin) else { throw AppFailure.message("Incorrect safety PIN.") }
        try FileManager.default.removeItem(at: directory(item.id)); reload()
    }
    func export(_ item: EvidenceCase) async throws -> URL {
        guard recordingId != item.id, pendingVideoCase != item.id else { throw AppFailure.message("Stop recording and wait for the last segment before exporting.") }
        let caseDirectory = directory(item.id)
        let manifestData = try JSONEncoder().encode(item)
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent("security4her-\(item.id).zip")
        return try await Task.detached(priority: .userInitiated) {
            let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: temporary, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: temporary) }
            let manifest = temporary.appendingPathComponent("manifest.json")
            try manifestData.write(to: manifest, options: .completeFileProtection)
            let readme = temporary.appendingPathComponent("README.txt")
            try Data("Security4Her iPhone evidence\nSHA-256 fingerprints are in manifest.json. Hashes do not independently prove authenticity. Video and audio are separate. Times are device-reported. This ZIP is not encrypted.\n".utf8).write(to: readme, options: .completeFileProtection)
            var entries: [(String, URL)] = [("manifest.json", manifest), ("README.txt", readme)]
            entries += item.pieces.map { ($0.filename, caseDirectory.appendingPathComponent($0.filename)) }
            try StoredZIP.archive(entries, to: destination)
            return destination
        }.value
    }
}

// Stream the stored ZIP method so long video cases do not occupy their entire size in RAM.
enum StoredZIP {
    private static let crcTable: [UInt32] = (0..<256).map { value in
        var crc = UInt32(value)
        for _ in 0..<8 { crc = crc & 1 == 1 ? (crc >> 1) ^ 0xedb88320 : crc >> 1 }
        return crc
    }
    static func archive(_ entries: [(String, URL)], to destination: URL) throws {
        FileManager.default.createFile(atPath: destination.path, contents: nil, attributes: [.protectionKey: FileProtectionType.complete])
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }
        var central = Data()
        func put<T: FixedWidthInteger>(_ value: T, in data: inout Data) { var v = value.littleEndian; withUnsafeBytes(of: &v) { data.append(contentsOf: $0) } }
        guard entries.count < 65535 else { throw AppFailure.message("Too many recordings for one export.") }
        for (name, url) in entries {
            let input = try FileHandle(forReadingFrom: url)
            defer { try? input.close() }
            let size = try input.seekToEnd()
            try input.seek(toOffset: 0)
            let offset = try output.offset()
            guard size < UInt32.max, offset < UInt32.max else { throw AppFailure.message("Export is too large for ZIP.") }
            let filename = Data(name.utf8)
            var crc: UInt32 = 0xffffffff
            while let chunk = try input.read(upToCount: 65536), !chunk.isEmpty {
                for byte in chunk { crc = (crc >> 8) ^ crcTable[Int((crc ^ UInt32(byte)) & 0xff)] }
            }
            crc ^= 0xffffffff
            var header = Data()
            put(UInt32(0x04034b50), in: &header); put(UInt16(20), in: &header); put(UInt16(0x800), in: &header)
            put(UInt16(0), in: &header); put(UInt16(0), in: &header); put(UInt16(33), in: &header)
            put(crc, in: &header); put(UInt32(size), in: &header); put(UInt32(size), in: &header)
            put(UInt16(filename.count), in: &header); put(UInt16(0), in: &header); header.append(filename)
            try output.write(contentsOf: header)
            try input.seek(toOffset: 0)
            while let chunk = try input.read(upToCount: 65536), !chunk.isEmpty { try output.write(contentsOf: chunk) }
            put(UInt32(0x02014b50), in: &central); put(UInt16(20), in: &central); put(UInt16(20), in: &central)
            put(UInt16(0x800), in: &central); put(UInt16(0), in: &central); put(UInt16(0), in: &central); put(UInt16(33), in: &central)
            put(crc, in: &central); put(UInt32(size), in: &central); put(UInt32(size), in: &central)
            put(UInt16(filename.count), in: &central)
            for _ in 0..<4 { put(UInt16(0), in: &central) }
            put(UInt32(0), in: &central); put(UInt32(offset), in: &central); central.append(filename)
        }
        let offset = try output.offset()
        guard offset + UInt64(central.count) < UInt32.max else { throw AppFailure.message("Export exceeds ZIP size limit.") }
        try output.write(contentsOf: central)
        var footer = Data()
        put(UInt32(0x06054b50), in: &footer); put(UInt16(0), in: &footer); put(UInt16(0), in: &footer)
        put(UInt16(entries.count), in: &footer); put(UInt16(entries.count), in: &footer)
        put(UInt32(central.count), in: &footer); put(UInt32(offset), in: &footer); put(UInt16(0), in: &footer)
        try output.write(contentsOf: footer)
    }
}
