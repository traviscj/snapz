import AuthenticationServices
import Foundation
import SwiftUI

@MainActor
final class AppState: ObservableObject {
    @Published var serverURL: String { didSet { UserDefaults.standard.set(serverURL, forKey: "serverURL"); Shared.defaults.set(serverURL, forKey: "serverURL") } }
    @Published private(set) var token: String?
    @Published private(set) var principal: String?
    @Published var collections: [PhotoCollection] = []
    @Published private(set) var vaults: [VaultInfo] = []
    /// The vault being browsed and shot into; nil until the server has been asked (= its default).
    @Published private(set) var vault: String? { didSet { UserDefaults.standard.set(vault, forKey: "vault"); Shared.defaults.set(vault, forKey: "vault") } }
    /// Where the camera shoots into; remembered across launches.
    @Published var captureCollection: String? { didSet { UserDefaults.standard.set(captureCollection, forKey: "captureCollection") } }
    @Published var error: String?
    /// The "snap first, file later" collection (server-configured; learned from /api/inbox).
    @Published var inboxSlug = "inbox" { didSet { Shared.defaults.set(inboxSlug, forKey: "inboxSlug") } }

    let queue = UploadQueue()
    private let webSignIn = WebSignIn()

    init() {
        serverURL = UserDefaults.standard.string(forKey: "serverURL")
            ?? (Bundle.main.object(forInfoDictionaryKey: "SnapzDefaultServer") as? String) ?? ""
        Self.followMovedServer(&serverURL)
        captureCollection = UserDefaults.standard.string(forKey: "captureCollection")
        vault = UserDefaults.standard.string(forKey: "vault")
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
        // The share extension reads these (didSet does not fire for assignments made in init).
        Shared.defaults.set(serverURL, forKey: "serverURL")
        Shared.defaults.set(vault, forKey: "vault")
        configureQueue()
    }

    var signedIn: Bool { token != nil }

    /// The server moved (SnapzLegacyServers lists where it used to be): point a saved URL at the new
    /// home. The token stays: device tokens live in the server's database, which moved with it.
    private static func followMovedServer(_ url: inout String) {
        let norm = { (s: String) in s.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() }
        guard let target = Bundle.main.object(forInfoDictionaryKey: "SnapzDefaultServer") as? String, !norm(target).isEmpty,
              let legacy = Bundle.main.object(forInfoDictionaryKey: "SnapzLegacyServers") as? String,
              legacy.split(separator: " ").map({ norm(String($0)) }).contains(norm(url)) else { return }
        url = target
        UserDefaults.standard.set(target, forKey: "serverURL")
    }

    var baseURL: URL? {
        let s = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let u = URL(string: s), let scheme = u.scheme, ["http", "https"].contains(scheme), u.host != nil else { return nil }
        return u
    }

    var api: API? {
        guard let baseURL, let token else { return nil }
        return API(base: baseURL, token: token, vault: vault)
    }

    var currentVault: VaultInfo? { vaults.first { $0.slug == vault } }

    func selectVault(_ slug: String) async {
        guard slug != vault else { return }
        vault = slug
        collections = []
        captureCollection = UserDefaults.standard.string(forKey: "captureCollection.\(slug)")
        configureQueue()
        await refreshCollections()
    }

    /// Learns the vault list, and falls back to the server's default when the remembered one is gone.
    func refreshVaults() async {
        guard let api else { return }
        do {
            let l = try await api.with(vault: nil).vaults()
            vaults = l.vaults
            if vault == nil || !l.vaults.contains(where: { $0.slug == vault }) {
                vault = l.vaults.contains(where: { $0.slug == l.default }) ? l.default : l.vaults.first?.slug
                configureQueue()
            }
        } catch {
            self.error = error.localizedDescription
        }
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

    /// A server someone asked the app to use via a `snapz://<host>` link; nothing changes until confirmed.
    @Published var offeredServer: URL?

    /// `snapz://snapz.example.net` (optionally `:port`, and `?insecure=1` for plain http on a LAN)
    /// offers to point the app at that server. `snapz://auth…` is the sign-in callback, not this.
    func consider(link: URL) {
        guard link.scheme?.lowercased() == "snapz", let host = link.host, !host.isEmpty, host.lowercased() != "auth" else { return }
        let insecure = URLComponents(url: link, resolvingAgainstBaseURL: false)?.queryItems?.contains { $0.name == "insecure" && $0.value == "1" } ?? false
        var c = URLComponents()
        c.scheme = insecure ? "http" : "https"
        c.host = host
        c.port = link.port
        guard let target = c.url else { return }
        if let baseURL, baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() == target.absoluteString.lowercased() {
            error = "Already using \(target.absoluteString)."
            return
        }
        offeredServer = target
    }

    /// Leave the current server (its device token is revoked there) and point at [server]; the
    /// sign-in screen comes up for it. Photos still waiting to upload stay queued and go to the
    /// new server once signed in.
    func switchServer(to server: URL) async {
        if signedIn { await signOut() }
        serverURL = server.absoluteString
        UserDefaults.standard.removeObject(forKey: "captureCollection")
        captureCollection = nil
    }

    /// Revokes this device's token server-side. Queued, un-uploaded photos stay on the phone.
    func signOut() async {
        await api?.logout()
        Keychain.set("token", nil)
        token = nil
        principal = nil
        collections = []
        vaults = []
        vault = nil
        queue.api = nil
    }

    func refreshCollections() async {
        if vaults.isEmpty { await refreshVaults() }
        guard let api else { return }
        do {
            collections = try await api.collections()
            if captureCollection == nil || !collections.contains(where: { $0.slug == captureCollection }) {
                captureCollection = collections.first?.slug
            }
            if let vault, let c = captureCollection { UserDefaults.standard.set(c, forKey: "captureCollection.\(vault)") }
        } catch {
            self.error = error.localizedDescription
        }
    }
}
