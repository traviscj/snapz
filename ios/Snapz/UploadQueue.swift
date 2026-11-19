import Foundation
import Network
#if canImport(UIKit)
import UIKit
#endif

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

    /// The active server's client.
    var api: API?
    /// Key of the active server; new shots are bound to it.
    var server: String?
    /// Client for a saved server by key, nil while not signed in there.
    var apiFor: ((String) -> API?)?
    var onUploaded: (() -> Void)?
    var onUnauthorized: (() -> Void)?

    /// client id -> server photo token for shots uploaded this launch, so a shot can still be
    /// relabelled or thrown away after it has left the phone (the capture review does both).
    private var uploadedTokens: [String: String] = [:]
    /// Shots discarded while their upload was in flight: deleted on the server once it lands.
    private var doomed = Set<String>()

    private let dir: URL
    private var draining = false
    private var retryTask: Task<Void, Never>?
    private let monitor = NWPathMonitor()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init() {
        dir = Shared.queueDir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        adoptLegacyQueue()
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

    /// Shots queued by a build that kept the queue in the app's own sandbox move to the shared one.
    private func adoptLegacyQueue() {
        let old = Shared.legacyQueueDir
        guard old != dir, let files = try? FileManager.default.contentsOfDirectory(at: old, includingPropertiesForKeys: nil), !files.isEmpty else { return }
        // Images before sidecars: a sidecar without its image is ignored, never the reverse.
        for f in files.sorted(by: { $0.pathExtension != "json" && $1.pathExtension == "json" }) {
            try? FileManager.default.moveItem(at: f, to: dir.appendingPathComponent(f.lastPathComponent))
        }
    }

    private func onDisk() -> [PendingUpload] {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
        return files.filter { $0.pathExtension == "json" }
            .compactMap { try? decoder.decode(PendingUpload.self, from: Data(contentsOf: $0)) }
            .filter { FileManager.default.fileExists(atPath: imageURL($0.id).path) }
            .sorted { $0.takenAt < $1.takenAt }
    }

    private func load() { items = onDisk() }

    /// The share extension writes into the same directory (and uploads what it can itself):
    /// pick up what it left behind, forget what it already sent.
    func rescan() {
        let disk = onDisk()
        let known = Set(items.map(\.id)), present = Set(disk.map(\.id))
        items.removeAll { !present.contains($0.id) && $0.id != uploading }
        items.append(contentsOf: disk.filter { !known.contains($0.id) })
        items.sort { $0.takenAt < $1.takenAt }
        kick()
    }

    private func persist(_ item: PendingUpload) {
        try? encoder.encode(item).write(to: metaURL(item.id), options: .atomic)
    }

    /// Durably records a shot. Throws only if the phone can't write it to disk.
    @discardableResult
    func enqueue(jpeg: Data, collection: String, vault: String?, action: String? = nil, qty: Int? = nil, takenAt: Date = Date(), note: String?, barcodes: [Barcode]) throws -> String {
        let item = PendingUpload(id: UUID().uuidString.lowercased(), collection: collection, vault: vault, action: action, qty: qty, takenAt: takenAt, note: note, barcodes: barcodes, server: server)
        // Image first, sidecar second: load() ignores an image without a sidecar, never the reverse.
        try jpeg.write(to: imageURL(item.id), options: .atomic)
        try encoder.encode(item).write(to: metaURL(item.id), options: .atomic)
        items.append(item)
        kick()
        return item.id
    }

    /// Change a shot's verb wherever it currently is: still queued (edit the sidecar), mid-upload
    /// (fixed up when it lands), or already on the server (PATCH).
    func revise(_ id: String, action: String?, qty: Int?) async throws {
        if let i = items.firstIndex(where: { $0.id == id }) {
            items[i].action = action
            items[i].qty = qty
            persist(items[i])
        } else if let token = uploadedTokens[id], let api {
            _ = try await api.updatePhoto(token, action: action ?? "", qty: qty)
        }
    }

    /// Throw a shot away wherever it currently is. On the server that is the trash (restorable).
    func remove(_ id: String) async throws {
        if items.contains(where: { $0.id == id }) {
            if uploading == id { doomed.insert(id) }
            discard(id)
        } else if let token = uploadedTokens[id], let api {
            _ = try await api.deletePhoto(token)
        }
    }

    func discard(_ id: String) {
        try? FileManager.default.removeItem(at: imageURL(id))
        try? FileManager.default.removeItem(at: metaURL(id))
        items.removeAll { $0.id == id }
    }

    /// Point a stuck item at a different collection (in [vault]) and try again.
    func retarget(_ id: String, to collection: String, vault: String?) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        items[i].collection = collection
        items[i].vault = vault
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
        guard !draining, api != nil || apiFor != nil else { return }
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
        // Servers that refused our token during this run: their photos wait for a sign-in.
        var refused = Set<String>()
        func client(_ item: PendingUpload) -> API? {
            guard let key = item.server, key != server else { return api }
            return apiFor?(key)
        }
        while let item = items.first(where: { !$0.stuck && !refused.contains($0.server ?? server ?? "") && client($0) != nil }), let api = client(item) {
            uploading = item.id
            guard FileManager.default.fileExists(atPath: imageURL(item.id).path) else {
                discard(item.id)    // the share extension already sent it
                continue
            }
            do {
                // Each shot goes to the vault it was taken for, whatever is selected now.
                let photo = try await api.with(vault: item.vault).upload(file: imageURL(item.id), collection: item.collection, takenAt: item.takenAt,
                                         clientId: item.id, note: item.note, metaJSON: item.metaJSON)
                uploadedTokens[item.id] = photo.token
                // The shot may have been edited or discarded while its bytes were on the wire.
                if doomed.remove(item.id) != nil {
                    _ = try? await api.deletePhoto(photo.token)
                } else if let now = items.first(where: { $0.id == item.id }), now.action != item.action || now.qty != item.qty {
                    _ = try? await api.updatePhoto(photo.token, action: now.action ?? "", qty: now.qty)
                }
                discard(item.id)
                failures = 0
                onUploaded?()
            } catch {
                let e = error as? APIError ?? APIError(status: 0, message: error.localizedDescription)
                guard let i = items.firstIndex(where: { $0.id == item.id }) else { continue }
                items[i].attempts += 1
                items[i].lastError = e.localizedDescription
                if e.status == 401 {
                    // Bad token: nothing goes to that server until sign-in. Keep everything; other servers carry on.
                    persist(items[i])
                    refused.insert(item.server ?? server ?? "")
                    if item.server == nil || item.server == server { onUnauthorized?() }
                    continue
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
