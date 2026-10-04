import Foundation
import Combine
import CryptoKit

private struct BackupFolder: Codable, Sendable {
    var bookmark: Data
    var ownerID: String
    var name: String
}

@MainActor
final class EvidenceBackup: ObservableObject {
    @Published private(set) var status = "Automatic copies are off"
    @Published private(set) var folderName: String?
    @Published private(set) var copied: [String: Int] = [:]
    private var folder: BackupFolder? = Disk.read("evidence-backup.json")
    private var ownerID: String?
    private var pending: [String: (EvidenceCase, URL)] = [:]
    private var running = false
    private var generation = 0
    private var verifiedPieces = Set<String>()

    func activate(ownerID: String?, cases: [EvidenceCase], directory: (String) -> URL) {
        self.ownerID = ownerID
        folderName = folder?.ownerID == ownerID ? folder?.name : nil
        guard folderName != nil else { status = "Automatic copies are off for this account"; return }
        for item in cases where item.ownerId == ownerID { enqueue(item, directory: directory(item.id)) }
    }

    func choose(_ url: URL, ownerID: String) throws {
        guard url.startAccessingSecurityScopedResource() else { throw AppFailure.message("Folder access was not granted. Choose the folder again.") }
        defer { url.stopAccessingSecurityScopedResource() }
        let value = BackupFolder(bookmark: try url.bookmarkData(options: .minimalBookmark,
            includingResourceValuesForKeys: nil, relativeTo: nil), ownerID: ownerID, name: url.lastPathComponent)
        try Disk.write(value, to: "evidence-backup.json")
        generation += 1; pending.removeAll(); copied = [:]; verifiedPieces = []; folder = value; self.ownerID = ownerID
        folderName = value.name; status = "Folder selected. Completed segments will be copied."
    }

    func disable() {
        generation += 1; pending.removeAll(); verifiedPieces = []; folder = nil; folderName = nil
        Disk.remove("evidence-backup.json")
        status = "Automatic copies are off. Existing external copies remain in Files."
    }

    func enqueue(_ item: EvidenceCase, directory: URL) {
        guard let folder, folder.ownerID == item.ownerId, ownerID == item.ownerId else { return }
        pending[item.id] = (item, directory)
        drain()
    }

    private func drain() {
        guard !running, let folder, let key = pending.keys.sorted().first,
              let (item, directory) = pending.removeValue(forKey: key), item.ownerId == ownerID else { return }
        running = true
        let revision = generation
        let previouslyVerified = verifiedPieces
        status = "Copying completed segments to \(folder.name)…"
        Task {
            defer { running = false; drain() }
            do {
                let result = try await Task.detached(priority: .utility) {
                    try Self.copy(item, from: directory, to: folder, previouslyVerified: previouslyVerified)
                }.value
                guard revision == generation, self.ownerID == item.ownerId else { return }
                verifiedPieces.formUnion(result)
                copied[item.id] = item.pieces.count
                status = "\(item.pieces.count) segments and manifest copied to Files and verified on copy. Cloud upload is managed by the provider and is not confirmed."
            } catch {
                guard revision == generation else { return }
                // Manifests are the durable retry queue; foreground/retry reloads them.
                status = "Copy pending: \(error.localizedDescription) Originals remain on this iPhone. Retry or choose the folder again."
            }
        }
    }

    nonisolated private static func copy(_ item: EvidenceCase, from source: URL, to folder: BackupFolder, previouslyVerified: Set<String>) throws -> Set<String> {
        var stale = false
        let root = try URL(resolvingBookmarkData: folder.bookmark, options: .withoutUI, relativeTo: nil, bookmarkDataIsStale: &stale)
        guard !stale, root.startAccessingSecurityScopedResource() else {
            throw CocoaError(.fileReadNoPermission)
        }
        defer { root.stopAccessingSecurityScopedResource() }
        var coordinationError: NSError?
        var copyError: Error?
        var verified = Set<String>()
        NSFileCoordinator().coordinate(writingItemAt: root, options: [], error: &coordinationError) { coordinated in
            do {
                let target = coordinated.appendingPathComponent("Security4Her", isDirectory: true).appendingPathComponent(item.id, isDirectory: true)
                try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true)
                for piece in item.pieces {
                    // Never permit a manifest filename to escape its case directory.
                    guard URL(fileURLWithPath: piece.filename).lastPathComponent == piece.filename else { throw CocoaError(.fileReadInvalidFileName) }
                    let destination = target.appendingPathComponent(piece.filename)
                    let key = item.id + "/" + piece.filename + ":" + piece.sha256
                    let size = (try? destination.resourceValues(forKeys: [.fileSizeKey]))?.fileSize
                    // Check each newly completed copy once, rather than rereading an hour of video
                    // every fifteen seconds. Relaunching or choosing another folder re-verifies it.
                    if previouslyVerified.contains(key), size == piece.bytes { verified.insert(key); continue }
                    let bytes = try Data(contentsOf: source.appendingPathComponent(piece.filename), options: .mappedIfSafe)
                    guard digest(bytes) == piece.sha256 else { throw AppFailure.message("A source segment failed its integrity check.") }
                    if (try? Data(contentsOf: destination)).map(digest) != piece.sha256 {
                        try bytes.write(to: destination, options: .atomic)
                    }
                    guard digest(try Data(contentsOf: destination, options: .mappedIfSafe)) == piece.sha256 else {
                        throw AppFailure.message("A copied segment failed its integrity check.")
                    }
                    verified.insert(key)
                }
                let manifest = try JSONEncoder().encode(item)
                let destination = target.appendingPathComponent("manifest.json")
                try manifest.write(to: destination, options: .atomic)
                guard digest(try Data(contentsOf: destination)) == digest(manifest) else { throw AppFailure.message("The copied manifest did not verify.") }
            } catch { copyError = error }
        }
        if let coordinationError { throw coordinationError }
        if let copyError { throw copyError }
        return verified
    }
    nonisolated private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
