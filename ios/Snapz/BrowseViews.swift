import SwiftUI

/// Navigation value for a photo grid. A wrapper because NavigationLink(value:) takes an optional:
/// a bare `String?` would be unwrapped to `String`, and nil ("all") would disable the link.
struct GridRoute: Hashable {
    let collection: String?
}

struct CollectionsView: View {
    @EnvironmentObject private var app: AppState
    @State private var showNew = false
    @State private var newName = ""
    @State private var newIcon = ""

    var body: some View {
        List {
            if let inbox = app.collections.first(where: { $0.slug == app.inboxSlug }), inbox.photoCount > 0 {
                NavigationLink(value: TriageRoute()) {
                    HStack(spacing: 6) {
                        Text("📥").frame(width: 22)
                        Text("file \(inbox.photoCount) from inbox").fontWeight(.bold)
                        Spacer()
                        Text(Fmt.age(inbox.lastPhotoAt)).foregroundStyle(.secondary)
                    }
                    .font(.callout.monospaced())
                }
                .listRowBackground(Color.accentColor.opacity(0.15))
            }
            NavigationLink(value: GridRoute(collection: nil)) {
                row(icon: "∗", name: "all", count: app.collections.reduce(0) { $0 + $1.photoCount },
                    bytes: app.collections.reduce(0) { $0 + $1.bytes }, last: app.collections.compactMap(\.lastPhotoAt).max(), target: false)
            }
            ForEach(app.collections) { c in
                NavigationLink(value: GridRoute(collection: c.slug)) {
                    row(icon: c.icon ?? "·", name: c.slug, count: c.photoCount, bytes: c.bytes, last: c.lastPhotoAt, target: c.slug == app.captureCollection)
                }
                .swipeActions(edge: .leading) {
                    Button { app.captureCollection = c.slug } label: { Label("Shoot here", systemImage: "camera") }.tint(.accentColor)
                }
            }
            Section {
                NavigationLink(value: RecordsRoute(ns: nil)) {
                    HStack(spacing: 6) {
                        Text("⧉").frame(width: 22)
                        Text("records")
                        Spacer()
                        Text("derived data").foregroundStyle(.secondary)
                    }
                    .font(.callout.monospaced())
                }
            }
        }
        .listStyle(.plain)
        .environment(\.defaultMinListRowHeight, 30)
        .navigationDestination(for: GridRoute.self) { PhotoGridView(collection: $0.collection) }
        .navigationDestination(for: RecordsRoute.self) { RecordsView(ns: $0.ns) }
        .navigationDestination(for: TriageRoute.self) { _ in TriageView() }
        .refreshable { await app.refreshCollections() }
        .toolbar {
            ToolbarItem(placement: .secondaryAction) {
                Button { newName = ""; newIcon = ""; showNew = true } label: { Label("New collection", systemImage: "plus") }
            }
        }
        .alert("New collection", isPresented: $showNew) {
            TextField("name", text: $newName).rawTextEntry()
            TextField("icon (emoji, optional)", text: $newIcon)
            Button("Create") {
                Task {
                    guard let api = app.api, !newName.isEmpty else { return }
                    do {
                        let c = try await api.createCollection(name: newName, icon: newIcon)
                        await app.refreshCollections()
                        app.captureCollection = c.slug
                    } catch { app.error = error.localizedDescription }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .overlay {
            if app.collections.isEmpty {
                ContentUnavailableView("No collections", systemImage: "square.stack", description: Text("Create one with + to start shooting into it."))
            }
        }
    }

    /// One dense line: icon, slug, [camera target], count, size, age of newest photo.
    private func row(icon: String, name: String, count: Int, bytes: Int64, last: Date?, target: Bool) -> some View {
        HStack(spacing: 6) {
            Text(icon).frame(width: 22)
            Text(name).fontWeight(target ? .bold : .regular).lineLimit(1)
            if target { Image(systemName: "camera.fill").font(.caption2).foregroundStyle(Color.accentColor) }
            Spacer(minLength: 4)
            Text("\(count)").frame(minWidth: 36, alignment: .trailing)
            Text(Fmt.bytes(bytes)).foregroundStyle(.secondary).frame(width: 72, alignment: .trailing)
            Text(Fmt.age(last)).foregroundStyle(.secondary).frame(width: 34, alignment: .trailing)
        }
        .font(.callout.monospaced())
    }
}

struct PhotoGridView: View {
    let collection: String?

    @EnvironmentObject private var app: AppState
    @State private var photos: [Photo] = []
    @State private var next: String?
    @State private var loading = false
    @State private var loaded = false
    @State private var selecting = false
    @State private var selected = Set<String>()
    @State private var open: Photo?

    private let columns = [GridItem(.adaptive(minimum: 88), spacing: 1)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 1) {
                ForEach(photos) { p in
                    tile(p)
                        .onTapGesture {
                            if selecting { if !selected.insert(p.token).inserted { selected.remove(p.token) } } else { open = p }
                        }
                        .onAppear { if p.id == photos.last?.id { Task { await load(reset: false) } } }
                }
            }
        }
        .navigationTitle(collection ?? "all").inlineTitle()
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button(selecting ? "Done" : "Select") { selecting.toggle(); selected.removeAll() }
            }
            if selecting {
                ToolbarItem(placement: .status) { Text("\(selected.count) selected").font(.caption.monospaced()) }
                ToolbarItem(placement: .secondaryAction) {
                    Menu("Move to…") { destinations { dest in batch { _ = try await $0.updatePhoto($1, collection: dest) } } }.disabled(selected.isEmpty)
                }
                ToolbarItem(placement: .secondaryAction) {
                    Menu("Copy to…") { destinations { dest in batch { _ = try await $0.copyPhoto($1, to: dest) } } }.disabled(selected.isEmpty)
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button("Delete", role: .destructive) { batch { _ = try await $0.deletePhoto($1) } }.disabled(selected.isEmpty)
                }
            }
        }
        .refreshable { await load(reset: true) }
        .task { if !loaded { await load(reset: true) } }
        .sheet(item: $open) { p in
            PhotoDetailView(photo: p) { Task { await load(reset: true) } }
        }
        .overlay {
            if loaded && photos.isEmpty { ContentUnavailableView("No photos", systemImage: "photo.on.rectangle") }
        }
    }

    @ViewBuilder private func tile(_ p: Photo) -> some View {
        if let api = app.api {
            Color.clear.aspectRatio(1, contentMode: .fit)
                .overlay { RemoteImage(path: p.thumbUrl, api: api, maxPixel: 300) }
                .clipped()
                .overlay(alignment: .topLeading) {
                    if let code = p.barcodes.first {
                        Text(code).font(.system(size: 8, design: .monospaced)).foregroundStyle(.green).padding(.horizontal, 2).background(.black.opacity(0.65))
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if p.note != nil { Image(systemName: "text.bubble.fill").font(.system(size: 9)).foregroundStyle(.yellow).padding(2) }
                }
                .overlay {
                    if selected.contains(p.token) {
                        Rectangle().strokeBorder(Color.accentColor, lineWidth: 3)
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.white, Color.accentColor).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing).padding(3)
                    }
                }
                .contentShape(Rectangle())
                .accessibilityElement()
                .accessibilityLabel("photo \(p.token)")
                .accessibilityIdentifier("tile")
                .accessibilityAddTraits(.isButton)
        }
    }

    @ViewBuilder private func destinations(_ pick: @escaping (String) -> Void) -> some View {
        ForEach(app.collections.filter { $0.slug != collection }) { c in
            Button("\(c.icon ?? "") \(c.slug)") { pick(c.slug) }
        }
    }

    private func batch(_ op: @escaping (API, String) async throws -> Void) {
        guard let api = app.api else { return }
        let tokens = Array(selected)
        Task {
            var failed = 0
            for t in tokens { do { try await op(api, t) } catch { failed += 1 } }
            if failed > 0 { app.error = "\(failed) of \(tokens.count) failed" }
            selecting = false
            selected.removeAll()
            await load(reset: true)
            await app.refreshCollections()
        }
    }

    private func load(reset: Bool) async {
        guard let api = app.api, !loading, reset || next != nil else { return }
        loading = true
        defer { loading = false; loaded = true }
        do {
            let page = try await api.photos(collection: collection, before: reset ? nil : next)
            photos = reset ? page.photos : photos + page.photos
            next = page.nextBefore
        } catch {
            app.error = error.localizedDescription
        }
    }
}

/// Outlines what was detected in a photo, drawn in the image's own coordinate space. Dashed =
/// decoded live in the viewfinder (the box is approximate); solid = found in the saved still.
struct DetectionBoxes: View {
    let barcodes: [Barcode]

    var body: some View {
        GeometryReader { geo in
            ForEach(barcodes.filter { $0.box?.count == 4 }, id: \.self) { b in
                let box = b.box!
                let rect = CGRect(x: box[0] * geo.size.width, y: box[1] * geo.size.height, width: box[2] * geo.size.width, height: box[3] * geo.size.height)
                Rectangle()
                    .strokeBorder(Color.green, style: StrokeStyle(lineWidth: 2, dash: b.source == "live" ? [5, 3] : []))
                    .frame(width: max(rect.width, 6), height: max(rect.height, 6))
                    .overlay(alignment: .topLeading) {
                        Text("\(b.symbology) \(b.value)")
                            .font(.system(size: 9, weight: .bold, design: .monospaced)).foregroundStyle(.black)
                            .padding(.horizontal, 2).background(Color.green).fixedSize()
                            .alignmentGuide(.top) { $0[.bottom] }
                    }
                    .position(x: rect.midX, y: rect.midY)
            }
        }
        .allowsHitTesting(false)
    }
}

struct PhotoDetailView: View {
    let photo: Photo
    var onChange: () -> Void

    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var note = ""
    @State private var zoom: CGFloat = 1
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    if let api = app.api {
                        RemoteImage(path: photo.url, api: api, maxPixel: 2400, fill: false)
                            .aspectRatio(photo.aspect, contentMode: .fit)
                            .overlay { DetectionBoxes(barcodes: photo.meta?.barcodes ?? []) }
                            .frame(maxWidth: .infinity, minHeight: photo.aspect == nil ? 240 : 0)
                            .scaleEffect(zoom)
                            .gesture(MagnifyGesture().onChanged { zoom = max(1, min(6, $0.magnification)) }.onEnded { _ in withAnimation { zoom = 1 } })
                            .zIndex(1)
                    }
                    Grid(alignment: .leading, horizontalSpacing: 10, verticalSpacing: 1) {
                        line("taken", "\(Fmt.stamp.string(from: photo.takenAt))  (\(Fmt.age(photo.takenAt)))")
                        line("collection", photo.collection)
                        line("size", "\(photo.width.map(String.init) ?? "?")×\(photo.height.map(String.init) ?? "?")  \(Fmt.bytes(photo.bytes))")
                        if !photo.barcodes.isEmpty { line("barcodes", photo.barcodes.joined(separator: " ")) }
                        line("token", photo.token)
                    }
                    .font(.caption.monospaced())
                    .padding(.horizontal, 10)
                    TextField("note", text: $note, axis: .vertical).lineLimit(1...5).textFieldStyle(.roundedBorder).font(.callout).padding(.horizontal, 10)
                    if note != (photo.note ?? "") {
                        Button("Save note") { act { _ = try await $0.updatePhoto(photo.token, note: note) } }.padding(.horizontal, 10)
                    }
                }
            }
            .navigationTitle(photo.collection).inlineTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Menu("Move to…") {
                            ForEach(app.collections.filter { $0.slug != photo.collection }) { c in
                                Button("\(c.icon ?? "") \(c.slug)") { act { _ = try await $0.updatePhoto(photo.token, collection: c.slug) } }
                            }
                        }
                        Menu("Copy to…") {
                            ForEach(app.collections) { c in
                                Button("\(c.icon ?? "") \(c.slug)") { act { _ = try await $0.copyPhoto(photo.token, to: c.slug) } }
                            }
                        }
                        Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete = true }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .confirmationDialog("Move this photo to the trash?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { act { _ = try await $0.deletePhoto(photo.token) } }
            } message: {
                Text("It can be restored from the web UI's trash.")
            }
        }
        .onAppear { note = photo.note ?? "" }
    }

    private func line(_ k: String, _ v: String) -> some View {
        GridRow {
            Text(k).foregroundStyle(.secondary)
            Text(v).textSelection(.enabled).lineLimit(1)
        }
    }

    private func act(_ op: @escaping (API) async throws -> Void) {
        guard let api = app.api else { return }
        Task {
            do {
                try await op(api)
                onChange()
                await app.refreshCollections()
                dismiss()
            } catch { app.error = error.localizedDescription }
        }
    }
}
