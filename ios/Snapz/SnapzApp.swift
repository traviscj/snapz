import SwiftUI

@main
struct SnapzApp: App {
    @StateObject private var app = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if app.signedIn { MainView() } else { LoginView() }
            }
            .environmentObject(app)
            .environmentObject(app.queue)
            .alert("Snapz", isPresented: Binding(get: { app.error != nil }, set: { if !$0 { app.error = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(app.error ?? "")
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active, app.signedIn else { return }
                app.queue.kick()
                Task { await app.refreshCollections() }
            }
        }
    }
}

struct LoginView: View {
    @EnvironmentObject private var app: AppState
    @State private var pasted = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("https://snapz.example.net", text: $app.serverURL).rawTextEntry(url: true).font(.callout.monospaced())
                }
                Section {
                    Button {
                        busy = true
                        Task { await app.signInWithSSO(deviceName: Device.name); busy = false }
                    } label: {
                        Label("Sign in with SSO", systemImage: "person.badge.key")
                    }
                    .disabled(busy || app.baseURL == nil)
                } footer: {
                    Text("Opens the server's sign-in page (Pocket ID / passkey). This device gets its own revocable token.")
                }
                Section {
                    SecureField("snapz_…", text: $pasted).rawTextEntry().font(.callout.monospaced())
                    Button("Paste from clipboard") { pasted = Pasteboard.string ?? pasted }
                    Button("Use token") {
                        busy = true
                        Task { await app.signIn(pastedToken: pasted); busy = false }
                    }
                    .disabled(busy || pasted.isEmpty || app.baseURL == nil)
                } header: {
                    Text("Or use an API token")
                } footer: {
                    Text("Mint one in the web UI under “tokens”. Useful when SSO isn't reachable from this network.")
                }
            }
            .navigationTitle("Snapz")
        }
    }
}

struct MainView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var queue: UploadQueue
    @State private var showCamera = false
    @State private var showQueue = false
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            CollectionsView()
                .navigationTitle("Snapz")
                .inlineTitle()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { showSettings = true } label: { Image(systemName: "gearshape") }
                    }
                    ToolbarItem(placement: .primaryAction) {
                        Button { showQueue = true } label: { QueueBadge() }
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    Button { showCamera = true } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "camera.fill")
                            Text(app.captureCollection.map { "→ \($0)" } ?? "camera").font(.callout.monospaced().weight(.semibold))
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(app.collections.isEmpty)
                    .padding(.horizontal, 12).padding(.bottom, 4)
                }
        }
        .cover(isPresented: $showCamera) { CaptureView() }
        .sheet(isPresented: $showQueue) { QueueView() }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .task { await app.refreshCollections() }
    }
}

/// Upload state at a glance: idle check, pending count, or a red count when something is stuck.
struct QueueBadge: View {
    @EnvironmentObject private var queue: UploadQueue

    var body: some View {
        HStack(spacing: 3) {
            if queue.pendingCount == 0 {
                Image(systemName: "checkmark.icloud").foregroundStyle(.secondary)
            } else {
                Image(systemName: queue.stuckCount > 0 ? "exclamationmark.icloud.fill" : queue.online ? "arrow.up.circle" : "icloud.slash")
                Text("\(queue.pendingCount)").font(.caption.monospacedDigit().weight(.bold))
            }
        }
        .foregroundStyle(queue.stuckCount > 0 ? Color.red : queue.online ? Color.accentColor : Color.orange)
    }
}

struct SettingsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var queue: UploadQueue
    @Environment(\.dismiss) private var dismiss
    @State private var confirmSignOut = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Account") {
                    LabeledContent("Signed in as", value: app.principal ?? "?")
                    LabeledContent("Server", value: app.serverURL)
                    LabeledContent("Waiting to upload", value: "\(queue.pendingCount)")
                }
                .font(.callout.monospaced())
                Section {
                    Button("Sign out", role: .destructive) { confirmSignOut = true }
                } footer: {
                    Text("Revokes this device's token on the server. Photos still waiting to upload stay on the phone and go up after the next sign-in.")
                }
            }
            .navigationTitle("Settings").inlineTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .confirmationDialog("Sign out of \(app.serverURL)?", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("Sign out", role: .destructive) { Task { await app.signOut(); dismiss() } }
            }
        }
    }
}
