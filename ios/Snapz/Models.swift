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
}

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
