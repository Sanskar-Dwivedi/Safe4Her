import SwiftUI
import AVKit

struct EvidenceListView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        List {
            Section { Text("Saved privately on this iPhone. In-app deletion is locked for 24 hours. Exported ZIP files are unencrypted; uninstalling removes local evidence.").font(.callout).foregroundStyle(.secondary) }
            if model.evidence.cases.isEmpty { ContentUnavailableView("No evidence yet", systemImage: "lock.doc", description: Text("Audio and video captured during an SOS appear here.")) }
            ForEach(model.evidence.cases) { item in
                NavigationLink { EvidenceDetailView(caseID: item.id) } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.startedAt.formatted()).font(.headline)
                        Text("\(item.pieces.count) recordings · \(item.endedAt == nil ? "Recording" : "Ended")").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }.navigationTitle("Evidence")
    }
}

struct EvidenceDetailView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let caseID: String
    @State private var share: SharedFile?
    @State private var media: SharedFile?
    @State private var confirmDelete = false
    @State private var pin = ""
    @State private var integrity: String?
    @State private var exporting = false
    var body: some View {
        List {
            if let item = model.evidence.cases.first(where: { $0.id == caseID }) {
                Section("Case") {
                    Text(item.startedAt.formatted())
                    Text(item.id).font(.caption.monospaced()).textSelection(.enabled)
                    Button("Check integrity") { integrity = model.evidence.verify(item) ? "All saved fingerprints match. This does not independently establish who recorded the files." : "A file is missing or does not match its saved fingerprint." }
                    if let integrity { Text(integrity).font(.caption) }
                    Button(exporting ? "Preparing export…" : "Export unencrypted ZIP") {
                        exporting = true
                        Task {
                            do { share = SharedFile(url: try await model.evidence.export(item)) } catch { model.report(error) }
                            exporting = false
                        }
                    }.disabled(exporting || item.endedAt == nil)
                    Button("Delete case", role: .destructive) { confirmDelete = true }
                }
                if !item.problems.isEmpty { Section("Recording notes") { ForEach(item.problems, id: \.self) { Text($0).foregroundStyle(.orange) } } }
                Section("Recordings") {
                    ForEach(item.pieces) { piece in
                        Button { media = .init(url: model.evidence.file(item, piece)) } label: {
                            Label {
                                VStack(alignment: .leading) { Text(piece.kind.capitalized); Text("\(piece.startedAt.formatted(date: .omitted, time: .standard)) · \(ByteCountFormatter.string(fromByteCount: Int64(piece.bytes), countStyle: .file))").font(.caption).foregroundStyle(.secondary) }
                            } icon: { Image(systemName: piece.kind == "video" ? "video" : "waveform") }
                        }
                    }
                }
            }
        }.navigationTitle("Evidence case")
            .sheet(item: $share, onDismiss: { if let share { try? FileManager.default.removeItem(at: share.url) }; share = nil }) { ShareSheet(items: [$0.url]) }
            .sheet(item: $media) { EvidencePlayer(url: $0.url) }
            .alert("Delete evidence?", isPresented: $confirmDelete) {
                if SecureStore.hasPIN { SecureField("Safety PIN", text: $pin).keyboardType(.numberPad) }
                Button("Delete", role: .destructive) {
                    guard let item = model.evidence.cases.first(where: { $0.id == caseID }) else { return }
                    do { try model.evidence.delete(item, pin: pin); dismiss() } catch { model.report(error) }
                    pin = ""
                }
                Button("Cancel", role: .cancel) { pin = "" }
            } message: { Text("Allowed 24 hours after recording ends. Save an external copy first if needed.") }
    }
}

struct EvidencePlayer: View {
    let url: URL
    @State private var player: AVPlayer?
    var body: some View {
        VideoPlayer(player: player).onAppear { player = AVPlayer(url: url); player?.play() }.onDisappear { player?.pause(); player = nil }
    }
}
