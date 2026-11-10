import PhotosUI
import SwiftUI

/// Full-screen camera with a heads-up display.
///
/// The viewfinder decodes barcodes continuously and draws what it sees, so you know a code was
/// read *before* anything is saved. Two ways to take a picture:
///   - the shutter: saves the current view, tagged with whatever is locked right now;
///   - AUTO: saves by itself the moment a code it hasn't captured this session locks, then holds
///     fire until something new shows up. Point, wait for the chirp, move to the next item.
struct CaptureView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var queue: UploadQueue
    @Environment(\.dismiss) private var dismiss
    @StateObject private var camera = CameraController()
    @AppStorage("captureAuto") private var auto = true

    @State private var shooting = false
    @State private var shots = 0
    /// Code values already saved this session, oldest first. AUTO never re-fires on these.
    @State private var captured: [String] = []
    @State private var note = ""
    @State private var showNote = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var flash = false
    @State private var focusPoint: CGPoint?
    @State private var focusStamp = 0
    @State private var autoFire: Task<Void, Never>?

    private var locked: [LiveCode] { camera.liveCodes.filter(\.locked) }
    private var fresh: [LiveCode] { locked.filter { !captured.contains($0.value) } }

    var body: some View {
        ZStack {
            // Preview and HUD drawing share one full-screen coordinate space (the preview layer's).
            ZStack {
                Color.black
                switch camera.status {
                case .running, .idle:
                    CameraPreview(layer: camera.previewLayer)
                        .onTapGesture { p in
                            camera.focus(atLayerPoint: p)
                            focusPoint = p
                            focusStamp += 1
                            let stamp = focusStamp
                            Task { try? await Task.sleep(nanoseconds: 1_200_000_000); if stamp == focusStamp { focusPoint = nil } }
                        }
                    hud
                case .denied:
                    message("Camera access is off for Snapz.\nSettings → Snapz → Camera.")
                case .unavailable(let why):
                    message("\(why)\nYou can still import from the photo library below.")
                }
                if flash { Color.white.opacity(0.5) }
            }
            .ignoresSafeArea()

            VStack(spacing: 6) {
                topBar
                Spacer()
                HStack(alignment: .bottom) {
                    tape
                    Spacer()
                }
                .padding(.horizontal, 10)
                if showNote {
                    TextField("note for the next shots", text: $note).textFieldStyle(.roundedBorder).padding(.horizontal, 12)
                }
                lights
                bottomBar
            }
            .padding(.vertical, 8)
        }
        .hideStatusBar()
        .sensoryFeedback(.impact(weight: .light), trigger: shots)
        .sensoryFeedback(.selection, trigger: locked.map(\.value))
        .task { await camera.start() }
        .onDisappear { camera.stop(); autoFire?.cancel() }
        .onChange(of: fresh.map(\.value)) { _, values in scheduleAutoFire(values) }
        .onChange(of: auto) { _, on in if on { scheduleAutoFire(fresh.map(\.value)) } else { autoFire?.cancel() } }
        .onChange(of: picked) { _, items in
            guard !items.isEmpty, let collection = app.captureCollection else { return }
            picked = []
            Task { await importFromLibrary(items, into: collection) }
        }
    }

    // MARK: HUD

    /// Drawn over the preview: center reticle, an outline + label on every code in view (amber
    /// while acquiring, green once locked, dim once already captured), and the tap-to-focus box.
    private var hud: some View {
        Canvas { ctx, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            let r: CGFloat = 86, arm: CGFloat = 20
            var reticle = Path()
            for (sx, sy) in [(-1.0, -1.0), (1.0, -1.0), (1.0, 1.0), (-1.0, 1.0)] {
                let corner = CGPoint(x: c.x + sx * r, y: c.y + sy * r)
                reticle.move(to: CGPoint(x: corner.x - sx * arm, y: corner.y))
                reticle.addLine(to: corner)
                reticle.addLine(to: CGPoint(x: corner.x, y: corner.y - sy * arm))
            }
            reticle.move(to: CGPoint(x: c.x - 5, y: c.y)); reticle.addLine(to: CGPoint(x: c.x + 5, y: c.y))
            reticle.move(to: CGPoint(x: c.x, y: c.y - 5)); reticle.addLine(to: CGPoint(x: c.x, y: c.y + 5))
            ctx.stroke(reticle, with: .color(locked.isEmpty ? .white.opacity(0.55) : .green), lineWidth: 1.5)

            for code in camera.liveCodes where code.corners.count >= 3 {
                let done = captured.contains(code.value)
                let color: Color = !code.locked ? .orange : done ? .green.opacity(0.55) : .green
                var outline = Path()
                outline.addLines(code.corners)
                outline.closeSubpath()
                ctx.fill(outline, with: .color(color.opacity(0.14)))
                ctx.stroke(outline, with: .color(color), lineWidth: code.locked ? 3 : 1.5)
                let top = code.corners.min { $0.y < $1.y }!
                let minX = code.corners.map(\.x).min()!
                let label = Text("\(done ? "✓ " : "")\(code.symbology) \(code.value)").font(.system(size: 12, weight: .bold, design: .monospaced)).foregroundColor(.black)
                let resolved = ctx.resolve(label)
                let sz = resolved.measure(in: size)
                let origin = CGPoint(x: max(4, min(size.width - sz.width - 8, minX)), y: max(4, top.y - sz.height - 6))
                ctx.fill(Path(CGRect(x: origin.x - 3, y: origin.y - 1, width: sz.width + 6, height: sz.height + 2)), with: .color(color))
                ctx.draw(resolved, at: origin, anchor: .topLeading)
            }

            if let p = focusPoint {
                let box = CGRect(x: p.x - 36, y: p.y - 36, width: 72, height: 72)
                ctx.stroke(Path(box), with: .color(camera.focusing ? .yellow : .green), lineWidth: 1.5)
            }
        }
        .allowsHitTesting(false)
    }

    /// Annunciator row: lit = true right now.
    private var lights: some View {
        HStack(spacing: 5) {
            light(camera.focusing ? "AF…" : "AF", on: !camera.focusing, color: camera.focusing ? .yellow : .green)
            light(camera.lens.isEmpty ? "1x" : camera.lens.uppercased(), on: camera.lens == "macro", color: .cyan)
            light(locked.isEmpty ? (camera.liveCodes.isEmpty ? "NO CODE" : "ACQ") : "CODE \(locked.count)", on: !locked.isEmpty,
                  color: locked.isEmpty ? .orange : .green, blink: locked.isEmpty && !camera.liveCodes.isEmpty)
            light(fresh.isEmpty ? (locked.isEmpty ? "—" : "HAVE") : "NEW", on: !fresh.isEmpty, color: .green)
            light(queue.pendingCount == 0 ? "SENT" : "Q \(queue.pendingCount)", on: queue.pendingCount > 0,
                  color: queue.stuckCount > 0 ? .red : queue.online ? .white : .orange)
        }
        .padding(.horizontal, 10)
    }

    private func light(_ text: String, on: Bool, color: Color, blink: Bool = false) -> some View {
        Text(text)
            .font(.system(size: 11, weight: .bold, design: .monospaced))
            .foregroundStyle(on ? Color.black : color.opacity(blink ? 1 : 0.75))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .frame(minWidth: 46)
            .background(on ? color : Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 3))
            .overlay(RoundedRectangle(cornerRadius: 3).strokeBorder(color.opacity(on ? 1 : 0.5), lineWidth: 1))
    }

    /// Running tape of codes saved this session, newest last.
    @ViewBuilder private var tape: some View {
        if !captured.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(captured.suffix(6), id: \.self) { v in
                    Text("✓ \(v)").lineLimit(1)
                }
            }
            .font(.system(size: 11, weight: .semibold, design: .monospaced)).foregroundStyle(.green)
            .padding(.horizontal, 5).padding(.vertical, 2)
            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 3))
        }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button { auto.toggle() } label: {
                Text(auto ? "AUTO" : "MANUAL")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundStyle(auto ? Color.black : Color.white)
                    .padding(.horizontal, 8).padding(.vertical, 6)
                    .background(auto ? Color.green : Color.black.opacity(0.6), in: Capsule())
            }
            .accessibilityLabel(auto ? "Auto capture on" : "Auto capture off")
            Spacer()
            Menu {
                ForEach(app.collections) { c in
                    Button { app.captureCollection = c.slug } label: {
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
            Button { dismiss() } label: {
                Text(shots == 0 ? "Done" : "Done · \(shots)")
                    .font(.system(size: 15, weight: .bold)).foregroundStyle(.black)
                    .padding(.horizontal, 12).padding(.vertical, 6).background(Color.yellow, in: Capsule())
            }
            .accessibilityIdentifier("capture-done")
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 10)
    }

    private var bottomBar: some View {
        HStack {
            PhotosPicker(selection: $picked, maxSelectionCount: 50, matching: .images) {
                Image(systemName: "photo.on.rectangle").font(.title2).frame(width: 64, height: 44)
            }
            Spacer()
            Button { shoot(auto: false) } label: {
                ZStack {
                    Circle().strokeBorder(fresh.isEmpty ? Color.white : Color.green, lineWidth: 4).frame(width: 74, height: 74)
                    Circle().fill(shooting ? Color.gray : Color.white).frame(width: 60, height: 60)
                    if !locked.isEmpty { Image(systemName: "barcode.viewfinder").font(.title2).foregroundStyle(.black) }
                }
            }
            .disabled(shooting || camera.status != .running || app.captureCollection == nil)
            .accessibilityLabel("Shutter")
            Spacer()
            Button { showNote.toggle() } label: {
                Image(systemName: note.isEmpty ? "text.bubble" : "text.bubble.fill").font(.title2).frame(width: 64, height: 44)
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 18)
    }

    private func message(_ text: String) -> some View {
        Text(text).multilineTextAlignment(.center).foregroundStyle(.white).padding(24)
    }

    // MARK: capture

    /// AUTO: a new code just locked. Wait a beat so other codes on the same package can lock too
    /// (one photo for the UPC and the lot code, not two), then fire if it's still new.
    private func scheduleAutoFire(_ values: [String]) {
        autoFire?.cancel()
        guard auto, !values.isEmpty else { return }
        autoFire = Task {
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled, auto, !shooting, !fresh.isEmpty else { return }
            shoot(auto: true)
        }
    }

    private func shoot(auto fired: Bool) {
        guard let collection = app.captureCollection, !shooting else { return }
        shooting = true
        let noteNow = note.isEmpty ? nil : note
        Task {
            defer { shooting = false }
            do {
                let shot = try await camera.capture()
                shots += 1
                // Everything in view at the shutter is now "had", so AUTO won't re-fire on it.
                for code in shot.liveCodes where code.locked && !captured.contains(code.value) { captured.append(code.value) }
                flash = true
                Task { try? await Task.sleep(nanoseconds: 60_000_000); flash = false }
                // Off the shutter path from here on.
                Task { await save(shot, into: collection, note: noteNow, auto: fired) }
            } catch {
                app.error = error.localizedDescription
            }
        }
    }

    private func save(_ shot: CapturedShot, into collection: String, note: String?, auto fired: Bool) async {
        let still = await BarcodeScanner.scan(jpeg: shot.jpeg)
        let lockedShot = CapturedShot(jpeg: shot.jpeg, rotationAngle: shot.rotationAngle, liveCodes: shot.liveCodes.filter(\.locked))
        let codes = BarcodeScanner.merge(still: still, shot: lockedShot)
        for c in codes where !captured.contains(c.value) { captured.append(c.value) }
        do {
            try queue.enqueue(jpeg: shot.jpeg, collection: collection, takenAt: Date(), note: note, barcodes: codes)
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
            await save(CapturedShot(jpeg: jpeg, rotationAngle: 0, liveCodes: []), into: collection, note: note.isEmpty ? nil : note, auto: false)
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
