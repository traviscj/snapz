import SwiftUI

/// Navigation value for a photo grid. A wrapper because NavigationLink(value:) takes an optional:
/// a bare `String?` would be unwrapped to `String`, and nil ("all") would disable the link.
struct GridRoute: Hashable {
    let collection: String?
}

struct CollectionsView: View {
    @EnvironmentObject private var app: AppState

    private func inboxRow(_ inbox: PhotoCollection) -> some View {
        HStack(spacing: 6) {
            Text(inbox.icon ?? "📥").frame(width: 22)
            Text(inbox.photoCount > 0 ? "inbox · file \(inbox.photoCount)" : "inbox").fontWeight(inbox.photoCount > 0 ? .bold : .regular)
            if inbox.slug == app.captureCollection { Image(systemName: "camera.fill").font(.caption2).foregroundStyle(Color.accentColor) }
            Spacer()
            Text(inbox.photoCount > 0 ? Fmt.age(inbox.lastPhotoAt) : "empty").foregroundStyle(.secondary)
        }
        .font(.callout.monospaced())
        .accessibilityIdentifier("inbox-row")
    }

    @State private var showNew = false
    @State private var newName = ""
    @State private var newIcon = ""

    var body: some View {
        List {
            // The inbox leads, on its own: with photos waiting it opens triage, otherwise its (empty) grid.
            if let inbox = app.collections.first(where: { $0.slug == app.inboxSlug }) {
                Section {
                    Group {
                        if inbox.photoCount > 0 {
                            NavigationLink(value: TriageRoute()) { inboxRow(inbox) }
                        } else {
                            NavigationLink(value: GridRoute(collection: inbox.slug)) { inboxRow(inbox) }
                        }
                    }
                    .listRowBackground(inbox.photoCount > 0 ? Color.accentColor.opacity(0.15) : nil)
                    .swipeActions(edge: .leading) {
                        Button { app.captureCollection = inbox.slug } label: { Label("Shoot here", systemImage: "camera") }.tint(.accentColor)
                    }
                }
            }
            Section {
            NavigationLink(value: GridRoute(collection: nil)) {
                row(icon: "∗", name: "all", count: app.collections.reduce(0) { $0 + $1.photoCount },
                    bytes: app.collections.reduce(0) { $0 + $1.bytes }, last: app.collections.compactMap(\.lastPhotoAt).max(), target: false)
            }
            ForEach(app.collections.filter { $0.slug != app.inboxSlug }) { c in
                NavigationLink(value: GridRoute(collection: c.slug)) {
                    row(icon: c.icon ?? "·", name: c.slug, count: c.photoCount, bytes: c.bytes, last: c.lastPhotoAt, target: c.slug == app.captureCollection)
                }
                .swipeActions(edge: .leading) {
                    Button { app.captureCollection = c.slug } label: { Label("Shoot here", systemImage: "camera") }.tint(.accentColor)
                }
            }
            } header: {
                Text("collections").font(.caption2.monospaced()).foregroundStyle(.secondary)
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
                    Menu("Move to…") { destinations { dest, action in batch { _ = try await $0.updatePhoto($1, collection: dest, action: action?.name, qty: action?.takesQty == true ? 1 : nil) } } }.disabled(selected.isEmpty)
                    if let here = app.collections.first(where: { $0.slug == collection }), !here.actionSet.isEmpty {
                        // Bulk labeling: set the verb on the whole selection without moving it.
                        Menu("Label…") {
                            ForEach(here.actionSet) { a in
                                Button(a.title) { batch { _ = try await $0.updatePhoto($1, action: a.name, qty: a.takesQty ? 1 : nil) } }
                            }
                            Button("no verb") { batch { _ = try await $0.updatePhoto($1, action: "") } }
                        }
                        .disabled(selected.isEmpty)
                    }
                }
                ToolbarItem(placement: .secondaryAction) {
                    Menu("Copy to…") { destinations(plain: true) { dest, _ in batch { _ = try await $0.copyPhoto($1, to: dest) } } }.disabled(selected.isEmpty)
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

    /// [plain]: no verb step (copies keep whatever the destination clears or allows).
    @ViewBuilder private func destinations(plain: Bool = false, _ pick: @escaping (String, CollectionAction?) -> Void) -> some View {
        ForEach(app.collections.filter { $0.slug != collection }) { c in
            DestinationItem(collection: c, plain: plain, pick: pick)
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
                        if let a = photo.meta?.action { line("action", a + (photo.meta?.qty.map { " × \($0)" } ?? "")) }
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
                                DestinationItem(collection: c) { dest, action in
                                    act { _ = try await $0.updatePhoto(photo.token, collection: dest, action: action?.name, qty: action?.takesQty == true ? 1 : nil) }
                                }
                            }
                        }
                        if let here = app.collections.first(where: { $0.slug == photo.collection }), !here.actionSet.isEmpty {
                            Menu("Label…") {
                                ForEach(here.actionSet) { a in
                                    Button(a.title) { act { _ = try await $0.updatePhoto(photo.token, action: a.name, qty: a.takesQty ? 1 : nil) } }
                                }
                                Button("no verb") { act { _ = try await $0.updatePhoto(photo.token, action: "") } }
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

/// A move destination in a menu: a plain button, or — when the collection has an action set — a
/// submenu of its verbs, because filing there is (collection, action). Menus can't take input, so
/// a verb with a quantity files as 1; triage has a stepper.
struct DestinationItem: View {
    let collection: PhotoCollection
    var plain = false
    let pick: (String, CollectionAction?) -> Void

    var body: some View {
        let title = "\(collection.icon ?? "") \(collection.slug)"
        if plain || collection.actionSet.isEmpty {
            Button(title) { pick(collection.slug, nil) }
        } else {
            Menu(title) {
                ForEach(collection.actionSet) { a in
                    Button(a.title) { pick(collection.slug, a) }
                }
                Button("no verb") { pick(collection.slug, nil) }
            }
        }
    }
}
