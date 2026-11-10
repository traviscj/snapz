import SwiftUI

/// Decoded-image memory cache keyed by server path. Bytes are additionally cached on disk by
/// URLSession (the server marks blobs immutable), so scrolling back is free and offline-tolerant.
final class ImageCache: @unchecked Sendable {
    static let shared = ImageCache()
    private let cache = NSCache<NSString, Box>()
    private final class Box { let image: CGImage; init(_ i: CGImage) { image = i } }

    init() { cache.totalCostLimit = 192 << 20 }

    func get(_ key: String) -> CGImage? { cache.object(forKey: key as NSString)?.image }

    func put(_ key: String, _ image: CGImage) {
        cache.setObject(Box(image), forKey: key as NSString, cost: image.bytesPerRow * image.height)
    }
}

/// An authenticated image from the server (`/blobs/...` paths need the bearer token, so
/// AsyncImage won't do).
struct RemoteImage: View {
    let path: String
    let api: API
    var maxPixel = 600
    var fill = true

    @State private var image: CGImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: fill ? .fill : .fit)
            } else {
                Rectangle().fill(.quaternary)
                if failed { Image(systemName: "exclamationmark.triangle").foregroundStyle(.secondary) }
            }
        }
        .task(id: path) {
            let key = "\(path)#\(maxPixel)"
            if let hit = ImageCache.shared.get(key) { image = hit; return }
            image = nil
            failed = false
            do {
                let data = try await api.data(path)
                let px = maxPixel
                if let cg = await Task.detached(priority: .userInitiated, operation: { ImageDecoder.decode(data, maxPixel: px) }).value {
                    ImageCache.shared.put(key, cg)
                    image = cg
                } else {
                    failed = true
                }
            } catch {
                failed = !Task.isCancelled
            }
        }
    }
}
