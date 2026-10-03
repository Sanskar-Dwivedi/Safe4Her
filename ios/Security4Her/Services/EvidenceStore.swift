import Foundation
import AVFoundation
import CryptoKit
import UIKit
import Combine

struct EvidencePiece: Codable, Identifiable, Sendable {
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
struct EvidenceCase: Codable, Identifiable, Sendable {
    let id: String
    let ownerId: String
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
    let backup = EvidenceBackup()
    private var audio: AVAudioRecorder?
    private let capture = AVCaptureSession()
    private let video = AVCaptureMovieFileOutput()
    private let captureQueue = DispatchQueue(label: "in.security4her.camera")
    private var audioStart = Date()
    private var videoStart = Date()
    private var wantsAudio = false
    private var wantsVideo = false
    private var audioInterrupted = false
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
        Task { await recoverUnindexedSegments() }
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] notification in
            let type = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let options = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            Task { @MainActor in self?.handleAudioInterruption(type: type, options: options) }
        }
        NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in
                self?.finishAudioSegment(); self?.audioInterrupted = false
                self?.problem("Audio services restarted. A recording gap may exist.")
                self?.resumeAudioIfPossible()
            }
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
            try FileManager.default.createDirectory(at: directory(item.id), withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            try JSONEncoder().encode(item).write(to: directory(item.id).appendingPathComponent("manifest.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            cases.removeAll { $0.id == item.id }; cases.append(item); cases.sort { $0.startedAt > $1.startedAt }
            backup.enqueue(item, directory: directory(item.id))
        } catch { self.error = "Evidence could not be saved: \(error.localizedDescription)" }
    }
    func start(id: String, ownerId: String, audio enabledAudio: Bool, video enabledVideo: Bool) async {
        guard recordingId == nil else { return }
        recordingId = id; sequence = 0; error = nil
        if var previous = cases.first(where: { $0.id == id && $0.ownerId == ownerId }) {
            previous.endedAt = nil; save(previous)
        } else { save(.init(id: id, ownerId: ownerId, startedAt: Date())) }
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
        return directory(id).appendingPathComponent("\(kind)-\(UUID().uuidString).\(ext)")
    }
    private func nextAudio() {
        if reachedLimit() { stop(); return }
        guard wantsAudio, !audioInterrupted, !audioOn, let url = nextURL("audio", extension: "m4a") else { return }
        if audio != nil { finishAudioSegment() }
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
        } catch { problem(error.localizedDescription) }
    }
    private func nextVideo() {
        if reachedLimit() { stop(); return }
        guard wantsVideo, foreground, pendingVideoCase == nil, !capture.inputs.isEmpty,
              let url = nextURL("video", extension: "mov") else { return }
        videoStart = Date(); pendingVideoCase = recordingId
        captureQueue.async { [capture, video] in
            if !capture.isRunning { capture.startRunning() }
            video.startRecording(to: url, recordingDelegate: self)
        }
    }
    private func reachedLimit() -> Bool {
        guard let id = recordingId, let item = cases.first(where: { $0.id == id }) else { return false }
        return Date().timeIntervalSince(item.startedAt) >= 3600
    }
    nonisolated func fileOutput(_ output: AVCaptureFileOutput, didStartRecordingTo fileURL: URL, from connections: [AVCaptureConnection]) {
        Task { @MainActor in self.videoOn = self.recordingId != nil && self.foreground }
    }
    func setForeground(_ value: Bool) {
        foreground = value
        if value { resumeAudioIfPossible() }
        if !value {
            videoOn = false
            captureQueue.async { [capture, video] in
                if video.isRecording { video.stopRecording() }
                capture.stopRunning()
            }
        } else if recordingId != nil && wantsVideo && AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
            if capture.inputs.isEmpty { configureCamera() }
            nextVideo()
        }
    }
    func stop() {
        guard let id = recordingId else { return }
        wantsAudio = false; wantsVideo = false
        finishAudioSegment(); audioInterrupted = false
        videoOn = false
        captureQueue.async { [capture, video] in
            if video.isRecording { video.stopRecording() }
            capture.stopRunning()
        }
        if var item = cases.first(where: { $0.id == id }) { item.endedAt = Date(); save(item) }
        recordingId = nil
    }
    private func finishAudioSegment() {
        if let audio, let id = recordingId {
            audio.delegate = nil; audio.stop()
            add(id: id, url: audio.url, kind: "audio", start: audioStart)
        }
        audio = nil; audioOn = false
    }
    private func handleAudioInterruption(type: UInt?, options: UInt) {
        guard recordingId != nil else { return }
        if type == AVAudioSession.InterruptionType.began.rawValue {
            audioInterrupted = true; finishAudioSegment()
            problem("Audio interrupted by iOS. Completed segments are retained; this interval may have a recording gap.")
        } else if type == AVAudioSession.InterruptionType.ended.rawValue {
            audioInterrupted = false
            if AVAudioSession.InterruptionOptions(rawValue: options).contains(.shouldResume) { resumeAudioIfPossible() }
            else { problem("Audio remains paused after an interruption. Return to the app to retry recording.") }
        }
    }
    private func resumeAudioIfPossible() {
        guard recordingId != nil, wantsAudio, !audioInterrupted, !audioOn,
              AVCaptureDevice.authorizationStatus(for: .audio) == .authorized else { return }
        do { try AVAudioSession.sharedInstance().setActive(true); nextAudio() }
        catch { problem("Audio could not resume: \(error.localizedDescription)") }
    }
    private func problem(_ text: String) {
        error = text
        if let id = recordingId, var item = cases.first(where: { $0.id == id }) {
            if !item.problems.contains(text) { item.problems.append(text); save(item) }
        }
    }
    private func recoverUnindexedSegments() async {
        for item in cases {
            let files = (try? FileManager.default.contentsOfDirectory(at: directory(item.id),
                includingPropertiesForKeys: [.creationDateKey, .contentModificationDateKey])) ?? []
            for url in files where ["m4a", "mov"].contains(url.pathExtension) && !item.pieces.contains(where: { $0.filename == url.lastPathComponent }) {
                guard recordingId != item.id, pendingVideoCase != item.id else { break }
                // A killed recording can leave an unfinalized container. Retain it, but don't
                // advertise it as playable evidence unless AVFoundation can read it.
                let playable = (try? await AVURLAsset(url: url).load(.isPlayable)) == true
                guard recordingId != item.id, pendingVideoCase != item.id else { break }
                guard playable else { continue }
                let dates = try? url.resourceValues(forKeys: [.creationDateKey, .contentModificationDateKey])
                add(id: item.id, url: url, kind: url.pathExtension == "mov" ? "video" : "audio",
                    start: dates?.creationDate ?? item.startedAt, end: dates?.contentModificationDate, includeLocation: false)
                if var recovered = cases.first(where: { $0.id == item.id }) {
                    let note = "Playable media was recovered after interruption. Recovered timestamps come from file metadata; no location was inferred."
                    if !recovered.problems.contains(note) { recovered.problems.append(note) }
                    if let end = recovered.pieces.last?.endedAt { recovered.endedAt = max(recovered.endedAt ?? end, end) }
                    save(recovered)
                }
            }
        }
    }
    private func add(id: String, url: URL, kind: String, start: Date, end: Date? = nil, includeLocation: Bool = true) {
        guard var item = cases.first(where: { $0.id == id }), !item.pieces.contains(where: { $0.filename == url.lastPathComponent }),
              let bytes = try? Data(contentsOf: url), !bytes.isEmpty else { return }
        let hash = Self.hash(bytes)
        let chain = Self.hash(Data(((item.pieces.last?.chain ?? String(repeating: "0", count: 64)) + hash).utf8))
        item.pieces.append(.init(filename: url.lastPathComponent, kind: kind, startedAt: start, endedAt: end ?? Date(), bytes: bytes.count, sha256: hash, chain: chain, fix: includeLocation ? location?() : nil))
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
            if completed { self.nextVideo() } else { self.wantsVideo = false }
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
