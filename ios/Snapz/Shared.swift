import Foundation

/// What the app and its share extension have in common: the App Group container (upload queue,
/// a few settings) both can reach. Without a group (e.g. a build with no entitlements) everything
/// falls back to the process's own sandbox, and the app simply works alone.
enum Shared {
    /// `group.<bundle prefix>.snapz`, from Info.plist so the id is not baked into the source.
    static let group: String? = {
        let g = Bundle.main.object(forInfoDictionaryKey: "SnapzAppGroup") as? String
        return (g?.isEmpty ?? true) ? nil : g
    }()

    static let container: URL? = group.flatMap { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: $0) }

    /// Server URL, current vault and inbox slug are mirrored here for the extension to read.
    static let defaults: UserDefaults = group.flatMap { UserDefaults(suiteName: $0) } ?? .standard

    /// Where un-uploaded shots lived before the queue moved into the group container.
    static var legacyQueueDir: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("upload-queue", isDirectory: true)
    }

    /// One spelling per server, for comparing URLs and keying what is stored per server.
    static func serverKey(_ url: String) -> String {
        url.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
    }

    static var queueDir: URL {
        container?.appendingPathComponent("upload-queue", isDirectory: true) ?? legacyQueueDir
    }
}

/// One photo waiting to reach the server: <id>.jpg + <id>.json side by side on disk. (The image
/// keeps the .jpg name whatever its real format; the server sniffs the bytes.)
struct PendingUpload: Codable, Identifiable, Hashable {
    let id: String              // also the server-side client_id (idempotency key)
    var collection: String
    /// Vault the collection lives in; nil (shots queued by older builds) = the server's default.
    var vault: String?
    /// Scan verb + quantity (photo meta `action` / `qty`); nil = just a picture.
    var action: String?
    var qty: Int?
    let takenAt: Date
    var note: String?
    var barcodes: [Barcode]
    var attempts: Int = 0
    var lastError: String?
    /// The server refused it for a reason retrying won't fix (e.g. the collection was deleted).
    var stuck: Bool = false
    /// Photo meta `source`; nil = shot with the app's camera ("ios").
    var source: String?
    /// The server it was taken for (`Shared.serverKey`); it goes there even if the app has since
    /// been pointed elsewhere. nil (queued by an older build) = whichever server is active.
    var server: String?

    static func imageURL(_ id: String, in dir: URL = Shared.queueDir) -> URL { dir.appendingPathComponent("\(id).jpg") }
    static func metaURL(_ id: String, in dir: URL = Shared.queueDir) -> URL { dir.appendingPathComponent("\(id).json") }

    /// The `meta` query parameter this shot is uploaded with.
    var metaJSON: String? {
        var meta: [String: Any] = [
            "source": source ?? "ios",
            "barcodes": barcodes.map { b -> [String: Any] in
                var d: [String: Any] = ["symbology": b.symbology, "value": b.value]
                if let box = b.box { d["box"] = box }
                if let source = b.source { d["source"] = source }
                return d
            },
        ]
        if let action, !action.isEmpty {
            meta["action"] = action
            if let qty { meta["qty"] = qty }
        }
        return (try? JSONSerialization.data(withJSONObject: meta)).map { String(decoding: $0, as: UTF8.self) }
    }
}
