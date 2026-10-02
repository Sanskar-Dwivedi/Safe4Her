import Foundation
import AVFoundation
import CoreMotion
import UIKit
import Combine

@MainActor
final class Deterrents: ObservableObject {
    @Published var sirenOn = false
    @Published var torchOn = false
    @Published var callVisible = false
    @Published var talking = false
    @Published var error: String?
    private var player: AVAudioPlayer?
    private let speech = AVSpeechSynthesizer()
    private var motion = CMMotionManager()
    private var knocks: [TimeInterval] = []
    private var lastKnock = 0.0
    private var refractoryUntil = 0.0
    var onKnock: (() -> Void)?
    func startSiren() {
        do {
            try audioSession()
            player = try AVAudioPlayer(data: Self.tone(seconds: 2, siren: true))
            player?.numberOfLoops = -1; player?.volume = 1
            guard player?.play() == true else { throw AppFailure.message("The siren could not start.") }
            sirenOn = true; UIApplication.shared.isIdleTimerDisabled = true
        } catch { self.error = error.localizedDescription }
    }
    func stopSiren() {
        player?.stop(); player = nil; sirenOn = false
        setTorch(false); UIApplication.shared.isIdleTimerDisabled = false
    }
    private func audioSession() throws {
        try AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .mixWithOthers])
        try AVAudioSession.sharedInstance().setActive(true)
    }
    func ring() {
        stopSiren(); talking = false; callVisible = true
        do {
            try audioSession()
            player = try AVAudioPlayer(data: Self.tone(seconds: 3, siren: false))
            player?.numberOfLoops = -1; player?.play()
            UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
        } catch { self.error = error.localizedDescription }
    }
    func answer(_ text: String) {
        player?.stop(); talking = true
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = 0.43; utterance.voice = AVSpeechSynthesisVoice(language: "en-IN")
        speech.speak(utterance)
    }
    func endCall() { player?.stop(); speech.stopSpeaking(at: .immediate); callVisible = false; talking = false }
    func setTorch(_ on: Bool) {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { error = "This device has no available torch."; return }
        do { try device.lockForConfiguration(); device.torchMode = on ? .on : .off; device.unlockForConfiguration(); torchOn = on }
        catch { self.error = error.localizedDescription }
    }
    func monitorKnocks(_ enabled: Bool) {
        motion.stopAccelerometerUpdates(); knocks = []
        guard enabled, motion.isAccelerometerAvailable else { return }
        motion.accelerometerUpdateInterval = 0.02
        motion.startAccelerometerUpdates(to: .main) { [weak self] sample, _ in
            guard let sample else { return }
            Task { @MainActor in
                guard let self else { return }
                let now = ProcessInfo.processInfo.systemUptime
                guard now > self.refractoryUntil else { return }
                let a = sample.acceleration
                let magnitude = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
                if magnitude > 2.6 && now - self.lastKnock > 0.16 {
                    self.lastKnock = now
                    self.knocks = self.knocks.filter { now - $0 < 1.5 }; self.knocks.append(now)
                    if self.knocks.count >= 3 {
                        self.knocks = []; self.refractoryUntil = now + 8; self.onKnock?()
                    }
                }
            }
        }
    }
    private static func tone(seconds: Int, siren: Bool) -> Data {
        let rate = 22050
        var pcm = Data()
        var phase = 0.0
        for i in 0..<(rate * seconds) {
            let t = Double(i) / Double(rate)
            let frequency = siren ? 750 + 400 * sin(t * .pi * 2) : 440.0
            phase += 2 * .pi * frequency / Double(rate)
            let gate = siren || t.truncatingRemainder(dividingBy: 3) < 1.2
            var value = Int16(gate ? sin(phase) * 24000 : 0).littleEndian
            withUnsafeBytes(of: &value) { pcm.append(contentsOf: $0) }
        }
        var wave = Data("RIFF".utf8)
        func append<T: FixedWidthInteger>(_ value: T) { var little = value.littleEndian; withUnsafeBytes(of: &little) { wave.append(contentsOf: $0) } }
        append(UInt32(36 + pcm.count)); wave.append(Data("WAVEfmt ".utf8)); append(UInt32(16))
        append(UInt16(1)); append(UInt16(1)); append(UInt32(rate)); append(UInt32(rate * 2)); append(UInt16(2)); append(UInt16(16))
        wave.append(Data("data".utf8)); append(UInt32(pcm.count)); wave.append(pcm)
        return wave
    }
}
