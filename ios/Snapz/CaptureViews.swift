import PhotosUI
import SwiftUI

/// Full-screen camera. The shutter is free again as soon as the sensor hands back the JPEG;
/// barcode detection and the durable enqueue run behind it, so bursts aren't rate-limited by either.
struct CaptureView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var queue: UploadQueue
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraController()

    @State private var shooting = false
    @State private var shots = 0
    @State private var lastCodes: [Barcode] = []
    @State private var note = ""
    @State private var showNote = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var flash = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            switch camera.status {
            case .running, .idle:
                CameraPreview(layer: camera.previewLayer).ignoresSafeArea()
            case .denied:
                message("Camera access is off for Snapz.\nSettings → Snapz → Camera.")
            case .unavailable(let why):
                message("\(why)\nYou can still import from the photo library below.")
            }
            if flash { Color.white.opacity(0.5).ignoresSafeArea() }
            VStack(spacing: 8) {
                topBar
                Spacer()
                if !lastCodes.isEmpty {
                    Text(lastCodes.map { "\($0.symbology) \($0.value)" }.joined(separator: "\n"))
                        .font(.callout.monospaced().weight(.semibold)).foregroundStyle(.green)
                        .padding(.horizontal, 8).padding(.vertical, 3).background(.black.opacity(0.7), in: RoundedRectangle(cornerRadius: 4))
                }
                if showNote {
                    TextField("note for the next shots", text: $note).textFieldStyle(.roundedBorder).padding(.horizontal, 12)
                }
                bottomBar
            }
            .padding(.vertical, 8)
        }
        .hideStatusBar()
        .sensoryFeedback(.impact(weight: .light), trigger: shots)
        .task { await camera.start() }
        .onDisappear { camera.stop() }
        .onChange(of: picked) { _, items in
            guard !items.isEmpty, let collection = app.captureCollection else { return }
            picked = []
            Task { await importFromLibrary(items, into: collection) }
        }
    }

    private var topBar: some View {
        HStack {
            Button { dismiss() } label: { Image(systemName: "xmark").font(.title3.weight(.semibold)).padding(8) }
            Spacer()
            Menu {
                ForEach(app.collections) { c in
                    Button { app.captureCollection = c.slug; lastCodes = [] } label: {
                        Label("\(c.icon ?? "") \(c.slug)", systemImage: c.slug == app.captureCollection ? "checkmark" : "")
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text("→ \(app.captureCollection ?? "pick a collection")").font(.headline.monospaced())
                    Image(systemName: "chevron.down").font(.caption.weight(.bold))
                }
                .padding(.horizontal, 10).padding(.vertical, 6).background(.black.opacity(0.6), in: Capsule())
            }
            Spacer()
            Button { showNote.toggle() } label: {
                Image(systemName: note.isEmpty ? "text.bubble" : "text.bubble.fill").font(.title3).padding(8)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 6)
    }

    private var bottomBar: some View {
        HStack {
            PhotosPicker(selection: $picked, maxSelectionCount: 50, matching: .images) {
                Image(systemName: "photo.on.rectangle").font(.title2).frame(width: 64, height: 44)
            }
            Spacer()
            Button(action: shoot) {
                ZStack {
                    Circle().strokeBorder(.white, lineWidth: 4).frame(width: 74, height: 74)
                    Circle().fill(shooting ? Color.gray : Color.white).frame(width: 60, height: 60)
                }
            }
            .disabled(shooting || camera.status != .running || app.captureCollection == nil)
            Spacer()
            VStack(spacing: 0) {
                Text("\(shots)").font(.title3.monospacedDigit().weight(.bold))
                Text(queue.pendingCount == 0 ? "sent" : "\(queue.pendingCount) queued")
                    .font(.caption2.monospaced()).foregroundStyle(queue.pendingCount == 0 ? Color.green : queue.online ? Color.white : Color.orange)
            }
            .frame(width: 64)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 18)
    }

    private func message(_ text: String) -> some View {
        Text(text).multilineTextAlignment(.center).foregroundStyle(.white).padding(24)
    }

    private func shoot() {
        guard let collection = app.captureCollection else { return }
        shooting = true
        let noteNow = note.isEmpty ? nil : note
        Task {
            defer { shooting = false }
            do {
                let jpeg = try await camera.capture()
                shots += 1
                flash = true
                Task { try? await Task.sleep(nanoseconds: 60_000_000); flash = false }
                // Off the shutter path from here on.
                Task { await save(jpeg, into: collection, note: noteNow, takenAt: Date()) }
            } catch {
                app.error = error.localizedDescription
            }
        }
    }

    private func save(_ jpeg: Data, into collection: String, note: String?, takenAt: Date) async {
        let codes = await BarcodeScanner.scan(jpeg: jpeg)
        lastCodes = codes
        do {
            try queue.enqueue(jpeg: jpeg, collection: collection, takenAt: takenAt, note: note, barcodes: codes)
        } catch {
            app.error = "Could not save the photo on this phone: \(error.localizedDescription)"
        }
    }

    /// Copies picked library photos in (re-encoded to JPEG so every client can display them). The
    /// originals stay in the library; this only reads.
    private func importFromLibrary(_ items: [PhotosPickerItem], into collection: String) async {
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self),
                  let cg = ImageDecoder.decode(data, maxPixel: 4096),
                  let jpeg = Self.jpegData(cg) else { continue }
            shots += 1
            await save(jpeg, into: collection, note: note.isEmpty ? nil : note, takenAt: Date())
        }
    }

    private static func jpegData(_ image: CGImage) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? out as Data : nil
    }
}

/// What's still on the phone, why, and what to do about it.
struct QueueView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var queue: UploadQueue
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if queue.items.isEmpty {
                    ContentUnavailableView("Everything is uploaded", systemImage: "checkmark.icloud")
                }
                ForEach(queue.items) { item in
                    HStack(spacing: 8) {
                        LocalThumb(url: queue.imageURL(item.id)).frame(width: 44, height: 44).clipped()
                        VStack(alignment: .leading, spacing: 1) {
                            HStack {
                                Text("→ \(item.collection)").fontWeight(.semibold)
                                Spacer()
                                Text(state(item)).foregroundStyle(item.stuck ? Color.red : queue.uploading == item.id ? Color.accentColor : Color.secondary)
                            }
                            Text("\(Fmt.stamp.string(from: item.takenAt))  \(item.barcodes.map(\.value).joined(separator: " "))").foregroundStyle(.secondary)
                            if let e = item.lastError { Text(e).foregroundStyle(item.stuck ? Color.red : Color.orange).lineLimit(2) }
                        }
                        .font(.caption.monospaced())
                    }
                    .swipeActions {
                        Button("Discard", role: .destructive) { queue.discard(item.id) }
                    }
                    .contextMenu {
                        ForEach(app.collections) { c in
                            Button("Send to \(c.slug) instead") { queue.retarget(item.id, to: c.slug) }
                        }
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Upload queue (\(queue.pendingCount))").inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { Button("Retry all") { queue.retryAll() }.disabled(queue.items.isEmpty) }
            }
            .safeAreaInset(edge: .bottom) {
                if !queue.online {
                    Text("offline — photos are safe on this phone and upload when the server is reachable")
                        .font(.caption.monospaced()).foregroundStyle(.orange).padding(6).frame(maxWidth: .infinity).background(.bar)
                }
            }
        }
    }

    private func state(_ item: PendingUpload) -> String {
        if item.stuck { return "REFUSED" }
        if queue.uploading == item.id { return "uploading" }
        return item.attempts > 0 ? "retry \(item.attempts)" : "waiting"
    }
}

private struct LocalThumb: View {
    let url: URL
    @State private var image: CGImage?

    var body: some View {
        ZStack {
            Rectangle().fill(.quaternary)
            if let image { Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: .fill) }
        }
        .task(id: url) {
            let u = url
            image = await Task.detached { (try? Data(contentsOf: u)).flatMap { ImageDecoder.decode($0, maxPixel: 160) } }.value
        }
    }
}
