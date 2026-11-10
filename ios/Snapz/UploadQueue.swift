import Foundation
import Network
#if canImport(UIKit)
import UIKit
#endif

/// One captured photo waiting to reach the server: <id>.jpg + <id>.json side by side on disk.
struct PendingUpload: Codable, Identifiable, Hashable {
    let id: String              // also the server-side client_id (idempotency key)
    var collection: String
    let takenAt: Date
    var note: String?
    var barcodes: [Barcode]
    var attempts: Int = 0
    var lastError: String?
    /// The server refused it for a reason retrying won't fix (e.g. the collection was deleted).
    var stuck: Bool = false
}

/// Durable, offline-first upload queue.
///
/// Capture never waits on the network: a shot is written to Application Support and is "taken".
/// A single drain loop then uploads oldest-first; each upload carries its id as `client_id`, so a
/// request that succeeded but whose response was lost is not duplicated on retry. Files are
/// removed only after the server acknowledged them.
@MainActor
final class UploadQueue: ObservableObject {
    @Published private(set) var items: [PendingUpload] = []
    @Published private(set) var uploading: String?
    @Published private(set) var online = true

    var api: API?
    var onUploaded: (() -> Void)?
    var onUnauthorized: (() -> Void)?

    private let dir: URL
    private var draining = false
    private var retryTask: Task<Void, Never>?
    private let monitor = NWPathMonitor()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("upload-queue", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        load()
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                guard let self else { return }
                let now = path.status == .satisfied
                let cameBack = now && !self.online
                self.online = now
                if cameBack { self.kick() }
            }
        }
        monitor.start(queue: DispatchQueue(label: "snapz.netmon"))
    }

    var pendingCount: Int { items.count }
    var stuckCount: Int { items.filter(\.stuck).count }

    func imageURL(_ id: String) -> URL { dir.appendingPathComponent("\(id).jpg") }
    private func metaURL(_ id: String) -> URL { dir.appendingPathComponent("\(id).json") }

    private func load() {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        items = files.filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(PendingUpload.self, from: Data(contentsOf: $0)) }
            .filter { FileManager.default.fileExists(atPath: imageURL($0.id).path) }
            .sorted { $0.takenAt < $1.takenAt }
    }

    private func persist(_ item: PendingUpload) {
        try? encoder.encode(item).write(to: metaURL(item.id), options: .atomic)
    }

    /// Durably records a shot. Throws only if the phone can't write it to disk.
    func enqueue(jpeg: Data, collection: String, takenAt: Date = Date(), note: String?, barcodes: [Barcode]) throws {
        let item = PendingUpload(id: UUID().uuidString.lowercased(), collection: collection, takenAt: takenAt, note: note, barcodes: barcodes)
        // Image first, sidecar second: load() ignores an image without a sidecar, never the reverse.
        try jpeg.write(to: imageURL(item.id), options: .atomic)
        try encoder.encode(item).write(to: metaURL(item.id), options: .atomic)
        items.append(item)
        kick()
    }

    func discard(_ id: String) {
        try? FileManager.default.removeItem(at: imageURL(id))
        try? FileManager.default.removeItem(at: metaURL(id))
        items.removeAll { $0.id == id }
    }

    /// Point a stuck item at a different collection and try again.
    func retarget(_ id: String, to collection: String) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        items[i].collection = collection
        items[i].stuck = false
        items[i].lastError = nil
        persist(items[i])
        kick()
    }

    func retryAll() {
        for i in items.indices where items[i].stuck {
            items[i].stuck = false
            persist(items[i])
        }
        kick()
    }

    /// Start draining if not already. Safe to call from anywhere, any number of times.
    func kick() {
        retryTask?.cancel()
        guard !draining, api != nil else { return }
        draining = true
        Task { await drain() }
    }

    private func drain() async {
        #if canImport(UIKit)
        // Ask iOS for time to finish the queue if the app is backgrounded mid-drain.
        var bg: UIBackgroundTaskIdentifier = .invalid
        bg = UIApplication.shared.beginBackgroundTask(withName: "snapz-upload") {
            UIApplication.shared.endBackgroundTask(bg)
            bg = .invalid
        }
        defer { if bg != .invalid { UIApplication.shared.endBackgroundTask(bg) } }
        #endif
        defer { draining = false; uploading = nil }

        var failures = 0
        while let api, let item = items.first(where: { !$0.stuck }) {
            uploading = item.id
            let meta: [String: Any] = [
                "source": "ios",
                "barcodes": item.barcodes.map { ["symbology": $0.symbology, "value": $0.value] },
            ]
            let metaJSON = (try? JSONSerialization.data(withJSONObject: meta)).map { String(decoding: $0, as: UTF8.self) }
            do {
                _ = try await api.upload(file: imageURL(item.id), collection: item.collection, takenAt: item.takenAt,
                                         clientId: item.id, note: item.note, metaJSON: metaJSON)
                discard(item.id)
                failures = 0
                onUploaded?()
            } catch {
                let e = error as? APIError ?? APIError(status: 0, message: error.localizedDescription)
                guard let i = items.firstIndex(where: { $0.id == item.id }) else { continue }
                items[i].attempts += 1
                items[i].lastError = e.localizedDescription
                if e.status == 401 {
                    // Bad token: nothing will upload until sign-in. Keep everything, stop quietly.
                    persist(items[i])
                    onUnauthorized?()
                    return
                }
                if e.isTransient {
                    persist(items[i])
                    failures += 1
                    // Whole-queue backoff: the server or the network is the problem, not this photo.
                    let delay = min(300.0, 5.0 * pow(2.0, Double(min(failures, 6) - 1)))
                    retryTask = Task { [weak self] in
                        try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                        if !Task.isCancelled { self?.kick() }
                    }
                    return
                }
                items[i].stuck = true
                persist(items[i])
            }
        }
    }
}
