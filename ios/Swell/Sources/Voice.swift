import SwiftUI
import AVFoundation
import SwellSignal

/// Records a voice note: AAC-LC in an M4A container, mono, 16 kHz, about 32 kbps, at most 180 s (PROTOCOL.md 5a).
@MainActor final class VoiceRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    @Published var recording = false
    @Published var seconds = 0.0
    @Published var denied = false
    static let maxSeconds = 180.0
    private var recorder: AVAudioRecorder?
    private var timer: Timer?
    private(set) var url: URL?
    var onFinish: ((Data, Int) -> Void)?

    func start() {
        AVAudioApplication.requestRecordPermission { ok in
            DispatchQueue.main.async { ok ? self.begin() : { self.denied = true }() }
        }
    }

    private func begin() {
        do {
            let s = AVAudioSession.sharedInstance()
            try s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try s.setActive(true)
            let u = FileManager.default.temporaryDirectory.appendingPathComponent("voice-\(UUID().uuidString).m4a")
            let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 16000, AVNumberOfChannelsKey: 1,
                                           AVEncoderBitRateKey: 32000, AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue]
            let r = try AVAudioRecorder(url: u, settings: settings)
            r.delegate = self
            guard r.record(forDuration: Self.maxSeconds) else { return }
            recorder = r; url = u; recording = true; seconds = 0
            timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
                Task { @MainActor in
                    guard let self, let r = self.recorder else { return }
                    self.seconds = r.currentTime
                }
            }
        } catch { recording = false }
    }

    /// Stops and hands over the audio (nil when shorter than a second: an accidental tap is dropped).
    func finish(send: Bool) {
        timer?.invalidate(); timer = nil
        let t = recorder?.currentTime ?? 0
        recorder?.stop(); recording = false
        defer { if let u = url { try? FileManager.default.removeItem(at: u) }; url = nil; recorder = nil }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        guard send, t >= 1, let u = url, let d = try? Data(contentsOf: u) else { return }
        onFinish?(d, Int(t * 1000))
    }

    nonisolated func audioRecorderDidFinishRecording(_ r: AVAudioRecorder, successfully ok: Bool) {
        Task { @MainActor in if self.recording { self.finish(send: ok) } } // the 180 s limit ended it
    }
}

/// One voice note plays at a time.
@MainActor final class VoicePlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = VoicePlayer()
    @Published var playingId: String?
    @Published var progress = 0.0
    private var player: AVAudioPlayer?
    private var timer: Timer?

    func toggle(id: String, url: URL) {
        if playingId == id { stop(); return }
        stop()
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            guard p.play() else { return }
            player = p; playingId = id; progress = 0
            timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                Task { @MainActor in if let p = self?.player, p.duration > 0 { self?.progress = p.currentTime / p.duration } }
            }
        } catch { stop() }
    }

    func stop() {
        timer?.invalidate(); timer = nil; player?.stop(); player = nil; playingId = nil; progress = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    nonisolated func audioPlayerDidFinishPlaying(_ p: AVAudioPlayer, successfully ok: Bool) { Task { @MainActor in self.stop() } }

    static func duration(of url: URL) -> Double { (try? AVAudioPlayer(contentsOf: url))?.duration ?? 0 }
}

/// Play button, progress and length of a voice note.
struct VoiceBubble: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject private var player = VoicePlayer.shared
    let message: ChatMessage
    let mine: Bool
    @State private var length = 0.0

    private var tint: Color { mine ? .white : Ocean.accent }
    private var url: URL? { model.fileURL(message.fileId) }

    var body: some View {
        let playing = player.playingId == message.id
        HStack(spacing: 10) {
            Button { if let u = url { player.toggle(id: message.id, url: u) } } label: {
                Image(systemName: playing ? "pause.circle.fill" : "play.circle.fill").font(.system(size: 34)).foregroundStyle(tint)
            }.buttonStyle(.plain).disabled(url == nil).accessibilityLabel(playing ? "Pause voice note" : "Play voice note")
            VStack(alignment: .leading, spacing: 4) {
                ProgressView(value: playing ? player.progress : 0).tint(tint).frame(width: 120)
                Text(Self.clock(message.voiceMs > 0 ? Double(message.voiceMs) / 1000 : length)).font(.caption.monospacedDigit())
                    .foregroundStyle(mine ? Color.white.opacity(0.9) : Color.secondary)
            }
        }
        .task { if message.voiceMs == 0, let u = url { length = VoicePlayer.duration(of: u) } }
    }

    static func clock(_ s: Double) -> String { String(format: "%d:%02d", Int(s) / 60, Int(s) % 60) }
}
