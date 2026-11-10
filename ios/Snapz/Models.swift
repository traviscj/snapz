import Foundation

struct PhotoCollection: Codable, Identifiable, Hashable {
    var id: String { slug }
    let slug: String
    let name: String
    let description: String?
    let icon: String?
    let photoCount: Int
    let bytes: Int64
    let lastPhotoAt: Date?
}

struct Photo: Codable, Identifiable, Hashable {
    var id: String { token }
    let token: String
    let collection: String
    let sha256: String
    let contentType: String
    let bytes: Int64
    let width: Int?
    let height: Int?
    let takenAt: Date
    let note: String?
    let barcodes: [String]
    let url: String
    let thumbUrl: String
    let deletedAt: Date?
    let meta: PhotoMeta?

    /// width / height of the upright image, when the server could measure it.
    var aspect: CGFloat? {
        guard let width, let height, width > 0, height > 0 else { return nil }
        return CGFloat(width) / CGFloat(height)
    }
}

/// The slice of free-form photo meta this app understands. Decoding is deliberately forgiving:
/// meta is written by scripts too, and a surprising shape must not make a photo undecodable.
struct PhotoMeta: Codable, Hashable {
    let barcodes: [Barcode]

    init(from decoder: Decoder) throws {
        let c = try? decoder.container(keyedBy: CodingKeys.self)
        barcodes = ((try? c?.decode([Lenient<Barcode>].self, forKey: .barcodes)) ?? []).compactMap(\.value)
    }
}

struct Lenient<T: Codable & Hashable>: Codable, Hashable {
    let value: T?
    init(from decoder: Decoder) throws { value = try? T(from: decoder) }
}

struct Suggestion: Codable, Hashable {
    let collection: String
    let p: Double
}

/// A photo waiting in the inbox, with destinations ranked by P(collection | photo).
struct InboxItem: Codable, Identifiable, Hashable {
    var id: String { photo.token }
    let photo: Photo
    let suggestions: [Suggestion]
}

struct InboxResponse: Codable {
    let inbox: String
    let photos: [InboxItem]
}

struct CollectionList: Codable { let collections: [PhotoCollection] }
struct PhotoPage: Codable { let photos: [Photo]; let nextBefore: String? }
struct Me: Codable { let principal: String }
struct AuthInfo: Codable { let oidc: Bool; let principal: String? }
struct DeviceToken: Codable { let token: String; let principal: String }
struct BatchResult: Codable { let photos: [Photo] }

struct Barcode: Codable, Hashable {
    let symbology: String
    let value: String
    /// [x, y, w, h] as fractions of the upright image, origin top-left.
    var box: [Double]?
    /// "live" (decoded from the viewfinder stream) or "still" (decoded from the saved JPEG).
    var source: String?

    init(symbology: String, value: String, box: [Double]? = nil, source: String? = nil) {
        self.symbology = symbology
        self.value = value
        self.box = box
        self.source = source
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        value = try c.decode(String.self, forKey: .value)
        symbology = (try? c.decode(String.self, forKey: .symbology)) ?? "?"
        box = try? c.decode([Double].self, forKey: .box)
        source = try? c.decode(String.self, forKey: .source)
    }
}

/// Arbitrary JSON (record values are whatever a transform wrote).
enum JSONValue: Codable, Hashable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }

    /// One-line rendering for list rows: strings bare, integers without ".0", containers as JSON.
    var compact: String {
        switch self {
        case .null: return "null"
        case .bool(let v): return v ? "true" : "false"
        case .number(let v): return v == v.rounded() && abs(v) < 1e15 ? String(Int64(v)) : String(v)
        case .string(let v): return v
        case .array(let v): return "[" + v.map(\.compact).joined(separator: ", ") + "]"
        case .object(let v): return "{" + v.keys.sorted().map { "\($0): \(v[$0]!.compact)" }.joined(separator: ", ") + "}"
        }
    }

    var pretty: String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return (try? enc.encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? compact
    }
}

struct RecordNamespace: Codable, Identifiable, Hashable {
    var id: String { ns }
    let ns: String
    let count: Int
    let updatedAt: Date?
}

struct Record: Codable, Identifiable, Hashable {
    var id: String { "\(ns)/\(k)" }
    let ns: String
    let k: String
    let v: JSONValue?
    let updatedAt: Date
    let feedSyncId: Int64?
}

struct NamespaceList: Codable { let namespaces: [RecordNamespace] }
struct RecordList: Codable { let records: [Record] }

enum Fmt {
    static func bytes(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .file)
    }

    /// Compact age: 12s, 4m, 3h, 9d.
    static func age(_ d: Date?) -> String {
        guard let d else { return "" }
        let s = max(0, Date().timeIntervalSince(d))
        if s < 90 { return "\(Int(s))s" }
        if s < 5400 { return "\(Int(s / 60))m" }
        if s < 129_600 { return "\(Int(s / 3600))h" }
        return "\(Int(s / 86400))d"
    }

    static let stamp: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()
}
