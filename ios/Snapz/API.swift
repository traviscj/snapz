import Foundation

struct APIError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { status == 0 ? message : "HTTP \(status): \(message)" }
    /// Worth retrying later without the user doing anything.
    var isTransient: Bool { status == 0 || status == 408 || status == 429 || status >= 500 }
}

/// Thin async client for the snapz JSON API. Value type: build one per (server, token).
struct API {
    let base: URL
    let token: String?

    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 30
        c.timeoutIntervalForResource = 300
        c.waitsForConnectivity = false
        // Blobs are immutable per token and served with a year of max-age: let the disk cache keep them.
        c.urlCache = URLCache(memoryCapacity: 32 << 20, diskCapacity: 512 << 20)
        return URLSession(configuration: c)
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        let frac = ISO8601DateFormatter()
        frac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        // The server prints java.time.Instant: fractional seconds appear only when non-zero.
        d.dateDecodingStrategy = .custom { dec in
            let s = try dec.singleValueContainer().decode(String.self)
            if let v = frac.date(from: s) ?? plain.date(from: s) { return v }
            throw DecodingError.dataCorrupted(.init(codingPath: dec.codingPath, debugDescription: "bad date \(s)"))
        }
        return d
    }()

    func url(_ path: String, _ query: [String: String?] = [:]) -> URL {
        var c = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        // appendingPathComponent would escape a '?' already in server-provided paths like /blobs/x?size=thumb
        if let q = path.firstIndex(of: "?") {
            c = URLComponents(url: base.appendingPathComponent(String(path[..<q])), resolvingAgainstBaseURL: false)!
            c.percentEncodedQuery = String(path[path.index(after: q)...])
        }
        let items = query.compactMap { k, v in v.map { URLQueryItem(name: k, value: $0) } }
        if !items.isEmpty { c.queryItems = (c.queryItems ?? []) + items }
        // URLComponents leaves '+' alone, which servers read as a space.
        c.percentEncodedQuery = c.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return c.url!
    }

    func request(_ method: String, _ path: String, query: [String: String?] = [:]) -> URLRequest {
        var r = URLRequest(url: url(path, query))
        r.httpMethod = method
        if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        return r
    }

    private func check(_ data: Data, _ resp: URLResponse) throws {
        guard let http = resp as? HTTPURLResponse else { throw APIError(status: 0, message: "no response") }
        guard (200..<300).contains(http.statusCode) else {
            throw APIError(status: http.statusCode, message: String(decoding: data.prefix(300), as: UTF8.self))
        }
    }

    private func send(_ r: URLRequest) async throws -> Data {
        do {
            let (data, resp) = try await API.session.data(for: r)
            try check(data, resp)
            return data
        } catch let e as APIError {
            throw e
        } catch {
            throw APIError(status: 0, message: error.localizedDescription)
        }
    }

    func get<T: Decodable>(_ path: String, query: [String: String?] = [:]) async throws -> T {
        try API.decoder.decode(T.self, from: try await send(request("GET", path, query: query)))
    }

    func send<T: Decodable>(_ method: String, _ path: String, json: [String: Any]? = nil) async throws -> T {
        var r = request(method, path)
        if let json {
            r.httpBody = try JSONSerialization.data(withJSONObject: json)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return try API.decoder.decode(T.self, from: try await send(r))
    }

    func data(_ path: String) async throws -> Data {
        try await send(request("GET", path))
    }

    // MARK: endpoints

    func me() async throws -> Me { try await get("/api/me") }
    func authInfo() async throws -> AuthInfo { try await get("/auth/info") }

    func collections() async throws -> [PhotoCollection] {
        let l: CollectionList = try await get("/api/collections")
        return l.collections
    }

    func createCollection(name: String, icon: String?) async throws -> PhotoCollection {
        var body: [String: Any] = ["name": name]
        if let icon, !icon.isEmpty { body["icon"] = icon }
        return try await send("POST", "/api/collections", json: body)
    }

    func photos(collection: String?, before: String? = nil, limit: Int = 120) async throws -> PhotoPage {
        try await get("/api/photos", query: ["collection": collection, "before": before, "limit": String(limit)])
    }

    func updatePhoto(_ token: String, note: String? = nil, collection: String? = nil) async throws -> Photo {
        var body: [String: Any] = [:]
        if let note { body["note"] = note }
        if let collection { body["collection"] = collection }
        return try await send("PATCH", "/api/photos/\(token)", json: body)
    }

    func copyPhoto(_ token: String, to collection: String) async throws -> Photo {
        try await send("POST", "/api/photos/\(token)/copy", json: ["collection": collection])
    }

    func deletePhoto(_ token: String) async throws -> Photo {
        try await send("DELETE", "/api/photos/\(token)")
    }

    /// Streams the file as the request body. `clientId` makes a retried upload land exactly once.
    func upload(file: URL, collection: String, takenAt: Date, clientId: String, note: String?, metaJSON: String?) async throws -> Photo {
        var r = request("POST", "/api/photos", query: [
            "collection": collection,
            "taken_at": String(Int64(takenAt.timeIntervalSince1970 * 1000)),
            "client_id": clientId,
            "note": (note?.isEmpty ?? true) ? nil : note,
            "meta": metaJSON,
        ])
        r.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        r.timeoutInterval = 120
        do {
            let (data, resp) = try await API.session.upload(for: r, fromFile: file)
            try check(data, resp)
            return try API.decoder.decode(Photo.self, from: data)
        } catch let e as APIError {
            throw e
        } catch {
            throw APIError(status: 0, message: error.localizedDescription)
        }
    }

    func exchangeDeviceToken(code: String, verifier: String, name: String) async throws -> DeviceToken {
        try await send("POST", "/auth/device-token", json: ["code": code, "verifier": verifier, "name": name])
    }

    func logout() async {
        let _: [String: Bool]? = try? await send("POST", "/auth/logout")
    }
}
