import AuthenticationServices
import CryptoKit
import Foundation

/// SSO sign-in. The server runs the whole OIDC dance (Pocket ID, passkeys and all) inside the
/// system web sheet and finishes by redirecting to snapz://auth?code=<one-time code>, which we
/// trade — together with the PKCE verifier only this app instance knows — for a device token.
@MainActor
final class WebSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func signIn(server: URL, deviceName: String) async throws -> DeviceToken {
        let verifier = Self.randomURLSafe(48)
        let challenge = Self.base64url(Data(SHA256.hash(data: Data(verifier.utf8))))
        let api = API(base: server, token: nil)
        let start = api.url("/auth/login", ["client": "ios", "challenge": challenge])

        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let s = ASWebAuthenticationSession(url: start, callbackURLScheme: "snapz") { url, error in
                if let url { cont.resume(returning: url) } else { cont.resume(throwing: error ?? APIError(status: 0, message: "sign-in cancelled")) }
            }
            s.presentationContextProvider = self
            // Share Safari's cookies: an existing Pocket ID session makes this a single tap.
            s.prefersEphemeralWebBrowserSession = false
            self.session = s
            if !s.start() { cont.resume(throwing: APIError(status: 0, message: "could not start the sign-in sheet")) }
        }
        session = nil
        guard let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "code" })?.value else {
            throw APIError(status: 0, message: "sign-in returned no code")
        }
        return try await api.exchangeDeviceToken(code: code, verifier: verifier, name: deviceName)
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { ASPresentationAnchor() }
    }

    static func base64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    static func randomURLSafe(_ bytes: Int) -> String {
        var b = [UInt8](repeating: 0, count: bytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes, &b)
        return base64url(Data(b))
    }
}
