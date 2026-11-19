import ImageIO
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// "Share → Snapz" from Photos, the screenshot editor, Safari, Files…: one or many images go
/// into a collection (the inbox unless told otherwise) without opening the app.
///
/// Every image is written into the app's upload queue first, then uploaded from here. Whatever
/// does not make it (no network, server away) is simply still in the queue, and the app sends it
/// the next time it is opened; the queue id is the server-side idempotency key, so nothing lands
/// twice whichever of the two gets there.
final class ShareViewController: UIViewController {
    private let model = ShareModel()

    override func viewDidLoad() {
        super.viewDidLoad()
        model.finish = { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) }
        model.cancel = { [weak self] in
            self?.extensionContext?.cancelRequest(withError: NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError))
        }
        let host = UIHostingController(rootView: ShareView(model: model))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)

        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
            .filter { $0.hasItemConformingToTypeIdentifier(UTType.image.identifier) }
        Task { await model.start(providers) }
    }
}

struct Staged: Identifiable {
    let id: String
    let file: URL
    let bytes: Int
    let thumb: UIImage?
    var state: State = .staged
    enum State: Equatable { case staged, sending, sent, queued(String) }
}

@MainActor
final class ShareModel: ObservableObject {
    @Published var staged: [Staged] = []
    @Published var expected = 0
    @Published var unreadable = 0
    @Published var vaults: [VaultInfo] = []
    @Published var vault: String?
    @Published var collections: [PhotoCollection] = []
    @Published var collection = "inbox"
    @Published var note = ""
    @Published var phase: Phase = .loading
    @Published var problem: String?

    enum Phase: Equatable { case loading, ready, sending, done }

    var finish: () -> Void = {}
    var cancel: () -> Void = {}

    private var api: API?
    private let staging = FileManager.default.temporaryDirectory.appendingPathComponent("share-\(UUID().uuidString)", isDirectory: true)

    var signedIn: Bool { api != nil }
    /// Which server this is going to, shown so a surprise is visible before sending.
    var serverHost: String {
        guard let base = api?.base, let host = base.host else { return "" }
        return base.port.map { "\(host):\($0)" } ?? host
    }
    var totalBytes: Int { staged.reduce(0) { $0 + $1.bytes } }
    var sentCount: Int { staged.filter { $0.state == .sent }.count }
    var queuedCount: Int { staged.filter { if case .queued = $0.state { return true } else { return false } }.count }

    func start(_ providers: [NSItemProvider]) async {
        expected = providers.count
        let d = Shared.defaults
        if let s = d.string(forKey: "serverURL"), let base = URL(string: s), base.host != nil, let token = Keychain.get("token") {
            vault = d.string(forKey: "vault")
            api = API(base: base, token: token, vault: vault)
        } else {
            problem = "Open Snapz and sign in first."
        }
        collection = remembered() ?? d.string(forKey: "inboxSlug") ?? "inbox"
        try? FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)

        async let destinations: Void = loadDestinations()
        // One at a time: an extension has little memory, and a full-size photo is big.
        for p in providers {
            if let s = await Self.stage(p, into: staging) { staged.append(s) } else { unreadable += 1 }
        }
        await destinations
        phase = .ready
    }

    private func remembered() -> String? { Shared.defaults.string(forKey: "shareCollection.\(vault ?? "")") }

    private func loadDestinations() async {
        guard let api else { return }
        // Best effort: without the server the photos still queue for the remembered collection.
        if let l = try? await api.with(vault: nil).vaults() {
            vaults = l.vaults
            if vault == nil || !l.vaults.contains(where: { $0.slug == vault }) { vault = l.default }
        }
        await loadCollections()
    }

    private func loadCollections() async {
        guard let api else { return }
        if let c = try? await api.with(vault: vault).collections() {
            collections = c
            if !c.contains(where: { $0.slug == collection }) {
                let inbox = Shared.defaults.string(forKey: "inboxSlug") ?? "inbox"
                collection = c.contains(where: { $0.slug == inbox }) ? inbox : (c.first?.slug ?? inbox)
            }
        }
    }

    func pick(vault v: String) async {
        guard v != vault else { return }
        vault = v
        collections = []
        collection = remembered() ?? Shared.defaults.string(forKey: "inboxSlug") ?? "inbox"
        await loadCollections()
    }

    func drop(_ id: String) {
        guard let i = staged.firstIndex(where: { $0.id == id }) else { return }
        try? FileManager.default.removeItem(at: staged[i].file)
        staged.remove(at: i)
    }

    func abandon() {
        try? FileManager.default.removeItem(at: staging)
        cancel()
    }

    func send() async {
        guard let api, !staged.isEmpty else { return }
        phase = .sending
        Shared.defaults.set(collection, forKey: "shareCollection.\(vault ?? "")")
        let dir = Shared.queueDir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let encoder = JSONEncoder()
        let text = note.trimmingCharacters(in: .whitespacesAndNewlines)
        let now = Date()

        // 1. Everything into the durable queue (image first, sidecar second, like the app does).
        var items: [PendingUpload] = []
        for (n, s) in staged.enumerated() {
            // Distinct, ordered timestamps keep a multi-select in the order it was picked.
            let item = PendingUpload(id: s.id, collection: collection, vault: vault, takenAt: now.addingTimeInterval(Double(n) / 1000),
                                     note: text.isEmpty ? nil : text, barcodes: [], source: "ios-share",
                                     server: Shared.defaults.string(forKey: "serverURL").map(Shared.serverKey))
            do {
                try FileManager.default.moveItem(at: s.file, to: PendingUpload.imageURL(item.id, in: dir))
                try encoder.encode(item).write(to: PendingUpload.metaURL(item.id, in: dir), options: .atomic)
                items.append(item)
            } catch {
                staged[n].state = .queued("could not save: \(error.localizedDescription)")
            }
        }

        // 2. Send what we can right now.
        var serverDown = false
        for item in items {
            guard let i = staged.firstIndex(where: { $0.id == item.id }) else { continue }
            if serverDown { staged[i].state = .queued("waiting for the server"); continue }
            staged[i].state = .sending
            do {
                _ = try await api.with(vault: item.vault).upload(file: PendingUpload.imageURL(item.id, in: dir), collection: item.collection,
                                                                 takenAt: item.takenAt, clientId: item.id, note: item.note, metaJSON: item.metaJSON)
                try? FileManager.default.removeItem(at: PendingUpload.imageURL(item.id, in: dir))
                try? FileManager.default.removeItem(at: PendingUpload.metaURL(item.id, in: dir))
                staged[i].state = .sent
            } catch {
                let e = error as? APIError ?? APIError(status: 0, message: error.localizedDescription)
                staged[i].state = .queued(e.localizedDescription)
                // Not this photo's fault: don't make the rest each wait out a timeout.
                if e.isTransient || e.status == 401 { serverDown = true }
            }
        }
        try? FileManager.default.removeItem(at: staging)
        phase = .done
        if queuedCount == 0 {
            try? await Task.sleep(nanoseconds: 600_000_000)
            finish()
        }
    }

    // MARK: reading what was shared

    /// Copies one shared image into [dir]. JPEG and PNG are kept byte for byte; anything else
    /// (HEIC from Photos, WebP, GIF…) becomes a JPEG, because that is what the server can thumbnail.
    nonisolated private static func stage(_ p: NSItemProvider, into dir: URL) async -> Staged? {
        let id = UUID().uuidString.lowercased()
        let out = dir.appendingPathComponent(id)
        guard await copy(p, to: out) else { return nil }
        guard let src = CGImageSourceCreateWithURL(out as CFURL, nil), let type = CGImageSourceGetType(src) as String? else {
            try? FileManager.default.removeItem(at: out)
            return nil
        }
        var file = out
        if type != UTType.jpeg.identifier && type != UTType.png.identifier {
            let jpg = dir.appendingPathComponent("\(id).jpg")
            // Decoding through the thumbnail API bounds memory; 4096 px is plenty for a non-camera image.
            guard let full = downsampled(src, max: 4096),
                  let dest = CGImageDestinationCreateWithURL(jpg as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
                try? FileManager.default.removeItem(at: out)
                return nil
            }
            CGImageDestinationAddImage(dest, full, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
            guard CGImageDestinationFinalize(dest) else {
                try? FileManager.default.removeItem(at: out)
                return nil
            }
            try? FileManager.default.removeItem(at: out)
            file = jpg
        }
        let bytes = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        let thumbSrc = CGImageSourceCreateWithURL(file as CFURL, nil)
        return Staged(id: id, file: file, bytes: bytes, thumb: thumbSrc.flatMap { downsampled($0, max: 240) }.map { UIImage(cgImage: $0) })
    }

    nonisolated private static func downsampled(_ src: CGImageSource, max: Int) -> CGImage? {
        CGImageSourceCreateThumbnailAtIndex(src, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max,
        ] as CFDictionary)
    }

    /// File representation when the source app has one (no decoding); else the raw data; else
    /// the in-memory image some apps hand over (the screenshot editor), written as PNG.
    nonisolated private static func copy(_ p: NSItemProvider, to out: URL) async -> Bool {
        let viaFile: Bool = await withCheckedContinuation { k in
            _ = p.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                // The file is only valid inside this callback.
                guard let url else { k.resume(returning: false); return }
                k.resume(returning: (try? FileManager.default.copyItem(at: url, to: out)) != nil)
            }
        }
        if viaFile { return true }
        let viaData: Bool = await withCheckedContinuation { k in
            _ = p.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, _ in
                guard let data else { k.resume(returning: false); return }
                k.resume(returning: (try? data.write(to: out, options: .atomic)) != nil)
            }
        }
        if viaData { return true }
        guard p.canLoadObject(ofClass: UIImage.self) else { return false }
        return await withCheckedContinuation { k in
            _ = p.loadObject(ofClass: UIImage.self) { obj, _ in
                guard let data = (obj as? UIImage)?.pngData() else { k.resume(returning: false); return }
                k.resume(returning: (try? data.write(to: out, options: .atomic)) != nil)
            }
        }
    }
}

struct ShareView: View {
    @ObservedObject var model: ShareModel

    private let mono = Font.system(.caption, design: .monospaced)

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            if let problem = model.problem {
                Text(problem).font(.callout).foregroundStyle(.orange)
            }
            thumbs
            if model.phase == .done {
                outcome
            } else {
                TextField("note (optional, applies to all)", text: $model.note)
                    .font(.callout)
                    .textFieldStyle(.roundedBorder)
                    .disabled(model.phase == .sending)
                destination
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Color(.systemBackground))
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button("Cancel") { model.abandon() }
                .disabled(model.phase == .sending || model.phase == .done)
            Spacer()
            Text(summary).font(mono).foregroundStyle(.secondary).accessibilityIdentifier("share-summary")
            Spacer()
            if model.phase == .done {
                Button("Done") { model.finish() }.bold()
            } else {
                Button("Send") { Task { await model.send() } }
                    .bold()
                    .disabled(model.phase != .ready || model.staged.isEmpty || !model.signedIn)
                    .accessibilityIdentifier("share-send")
            }
        }
    }

    private var summary: String {
        switch model.phase {
        case .loading: return "reading \(model.staged.count + model.unreadable)/\(model.expected)"
        case .ready:
            let skipped = model.unreadable > 0 ? " · \(model.unreadable) unreadable" : ""
            return "\(model.staged.count) img · \(ByteCountFormatter.string(fromByteCount: Int64(model.totalBytes), countStyle: .file))\(skipped)"
        case .sending: return "sending \(model.sentCount + model.queuedCount + 1)/\(model.staged.count)"
        case .done: return "\(model.sentCount) sent · \(model.queuedCount) queued"
        }
    }

    private var thumbs: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(model.staged) { s in
                    ZStack(alignment: .topTrailing) {
                        Group {
                            if let t = s.thumb { Image(uiImage: t).resizable().scaledToFill() } else { Color.gray.opacity(0.3) }
                        }
                        .frame(width: 72, height: 96)
                        .clipped()
                        .overlay(alignment: .bottom) { badge(s.state) }
                        if model.phase == .ready {
                            Button { model.drop(s.id) } label: {
                                Image(systemName: "xmark.circle.fill").symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.6))
                            }
                            .padding(2)
                        }
                    }
                }
            }
        }
        .frame(height: 96)
    }

    @ViewBuilder private func badge(_ state: Staged.State) -> some View {
        switch state {
        case .staged: EmptyView()
        case .sending: ProgressView().tint(.white).frame(maxWidth: .infinity).padding(2).background(.black.opacity(0.55))
        case .sent: Text("SENT").font(.caption2.bold()).foregroundStyle(.white).frame(maxWidth: .infinity).background(Color.green.opacity(0.85))
        case .queued: Text("QUEUED").font(.caption2.bold()).foregroundStyle(.black).frame(maxWidth: .infinity).background(Color.orange.opacity(0.9))
        }
    }

    private var destination: some View {
        VStack(alignment: .leading, spacing: 4) {
            if model.vaults.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 4) {
                        ForEach(model.vaults) { v in
                            chip("\(v.icon ?? "") \(v.slug)", on: v.slug == model.vault) { Task { await model.pick(vault: v.slug) } }
                        }
                    }
                }
            }
            Text("→ \(model.vault.map { "\($0)/" } ?? "")\(model.collection)  @\(model.serverHost)").font(mono.bold()).accessibilityIdentifier("share-dest")
            ScrollView {
                FlowRows(spacing: 4) {
                    if model.collections.isEmpty {
                        chip(model.collection, on: true) {}
                    }
                    ForEach(model.collections) { c in
                        chip("\(c.icon ?? "") \(c.slug) \(c.photoCount)", on: c.slug == model.collection) { model.collection = c.slug }
                    }
                }
            }
        }
        .disabled(model.phase == .sending)
    }

    private func chip(_ label: String, on: Bool, _ tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            Text(label.trimmingCharacters(in: .whitespaces))
                .font(mono)
                .padding(.horizontal, 7)
                .padding(.vertical, 4)
                .background(on ? Color.accentColor : Color(.secondarySystemBackground))
                .foregroundStyle(on ? Color.white : Color.primary)
                .clipShape(RoundedRectangle(cornerRadius: 4))
        }
        .buttonStyle(.plain)
    }

    private var outcome: some View {
        VStack(alignment: .leading, spacing: 3) {
            if model.queuedCount > 0 {
                Text("\(model.queuedCount) could not be sent now. They are in the Snapz upload queue and go up the next time the app is open and the server is reachable.")
                    .font(.callout)
                ForEach(model.staged) { s in
                    if case .queued(let why) = s.state {
                        Text(why).font(mono).foregroundStyle(.orange).lineLimit(2)
                    }
                }
            } else {
                Text("→ \(model.vault.map { "\($0)/" } ?? "")\(model.collection)").font(mono.bold()).foregroundStyle(.green)
            }
        }
    }
}

/// Left-to-right wrapping rows (chips).
struct FlowRows: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews)
        return CGSize(width: proposal.width ?? rows.width, height: rows.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (i, p) in arrange(width: bounds.width, subviews).points.enumerated() {
            subviews[i].place(at: CGPoint(x: bounds.minX + p.x, y: bounds.minY + p.y), proposal: .unspecified)
        }
    }

    private func arrange(width: CGFloat, _ subviews: Subviews) -> (points: [CGPoint], width: CGFloat, height: CGFloat) {
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0, maxX: CGFloat = 0
        var points: [CGPoint] = []
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += rowH + spacing; rowH = 0 }
            points.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowH = max(rowH, size.height)
            maxX = max(maxX, x - spacing)
        }
        return (points, maxX, y + rowH)
    }
}
