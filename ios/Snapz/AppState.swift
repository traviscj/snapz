import AuthenticationServices
import Foundation
import SwiftUI

@MainActor
final class AppState: ObservableObject {
    @Published var serverURL: String { didSet { UserDefaults.standard.set(serverURL, forKey: "serverURL") } }
    @Published private(set) var token: String?
    @Published private(set) var principal: String?
    @Published var collections: [PhotoCollection] = []
    /// Where the camera shoots into; remembered across launches.
    @Published var captureCollection: String? { didSet { UserDefaults.standard.set(captureCollection, forKey: "captureCollection") } }
    @Published var error: String?
    /// The "snap first, file later" collection (server-configured; learned from /api/inbox).
    @Published var inboxSlug = "inbox"

    let queue = UploadQueue()
    private let webSignIn = WebSignIn()

    init() {
        serverURL = UserDefaults.standard.string(forKey: "serverURL")
            ?? (Bundle.main.object(forInfoDictionaryKey: "SnapzDefaultServer") as? String) ?? ""
        captureCollection = UserDefaults.standard.string(forKey: "captureCollection")
        token = Keychain.get("token")
        principal = UserDefaults.standard.string(forKey: "principal")
        #if DEBUG
        // Simulator/UI-test hook: `SIMCTL_CHILD_SNAPZ_DEBUG_TOKEN=… xcrun simctl launch … -serverURL http://localhost:8788`
        if let t = ProcessInfo.processInfo.environment["SNAPZ_DEBUG_TOKEN"], !t.isEmpty {
            token = t
            principal = "debug"
            // Persist, so later launches from the home screen stay signed in to the same server.
            Keychain.set("token", t)
            UserDefaults.standard.set("debug", forKey: "principal")
            UserDefaults.standard.set(serverURL, forKey: "serverURL")
        }
        #endif
        configureQueue()
    }

    var signedIn: Bool { token != nil }

    var baseURL: URL? {
        let s = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let u = URL(string: s), let scheme = u.scheme, ["http", "https"].contains(scheme), u.host != nil else { return nil }
        return u
    }

    var api: API? {
        guard let baseURL, let token else { return nil }
        return API(base: baseURL, token: token)
    }

    private func configureQueue() {
        queue.api = api
        queue.onUploaded = { [weak self] in Task { await self?.refreshCollections() } }
        queue.onUnauthorized = { [weak self] in self?.error = "The server rejected this device's token. Sign in again; queued photos are kept." }
        queue.kick()
    }

    private func adopt(token: String, principal: String) {
        Keychain.set("token", token)
        UserDefaults.standard.set(principal, forKey: "principal")
        self.token = token
        self.principal = principal
        configureQueue()
    }

    func signInWithSSO(deviceName: String) async {
        guard let baseURL else { error = "Enter the server URL first (https://…)"; return }
        do {
            let t = try await webSignIn.signIn(server: baseURL, deviceName: deviceName)
            adopt(token: t.token, principal: t.principal)
        } catch let e as ASWebAuthenticationSessionError where e.code == .canceledLogin {
            // user closed the sheet: not an error worth a banner
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Fallback when SSO isn't reachable: paste a token minted in the web UI.
    func signIn(pastedToken: String) async {
        guard let baseURL else { error = "Enter the server URL first (https://…)"; return }
        let t = pastedToken.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let me = try await API(base: baseURL, token: t).me()
            adopt(token: t, principal: me.principal)
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Revokes this device's token server-side. Queued, un-uploaded photos stay on the phone.
    func signOut() async {
        await api?.logout()
        Keychain.set("token", nil)
        token = nil
        principal = nil
        collections = []
        queue.api = nil
    }

    func refreshCollections() async {
        guard let api else { return }
        do {
            collections = try await api.collections()
            if captureCollection == nil || !collections.contains(where: { $0.slug == captureCollection }) {
                captureCollection = collections.first?.slug
            }
        } catch {
            self.error = error.localizedDescription
        }
    }
}
