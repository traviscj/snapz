import XCTest

/// Smoke test against a running dev server. Needs:
///   TEST_RUNNER_SNAPZ_URL=http://localhost:8788 TEST_RUNNER_SNAPZ_TOKEN=<token> xcodebuild test …
/// and a collection named `pantry` with at least one photo. Screenshots go to
/// TEST_RUNNER_SNAPZ_SHOTS (a host directory; simulator only) when set.
final class SnapzUITests: XCTestCase {
    private var app: XCUIApplication!
    private let env = ProcessInfo.processInfo.environment

    override func setUpWithError() throws {
        continueAfterFailure = false
        guard let url = env["SNAPZ_URL"], let token = env["SNAPZ_TOKEN"] else {
            throw XCTSkip("set TEST_RUNNER_SNAPZ_URL and TEST_RUNNER_SNAPZ_TOKEN")
        }
        app = XCUIApplication()
        app.launchEnvironment["SNAPZ_DEBUG_TOKEN"] = token
        app.launchArguments += ["-serverURL", url]
        app.launch()
    }

    private func shot(_ name: String) {
        guard let dir = env["SNAPZ_SHOTS"] else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }

    func testBrowseDetailAndCamera() throws {
        // collections list, loaded from the server
        let pantry = app.staticTexts["pantry"]
        XCTAssertTrue(pantry.waitForExistence(timeout: 10), "collections did not load")
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "vault-menu").firstMatch.waitForExistence(timeout: 5), "vault switcher missing")
        shot("1-collections")

        // grid
        pantry.tap()
        let tile = app.descendants(matching: .any).matching(identifier: "tile").firstMatch
        if !tile.waitForExistence(timeout: 10) { shot("2-grid-missing"); print("HIERARCHY", app.debugDescription); XCTFail("no photo tiles in pantry") }
        sleep(2) // let thumbnails land for the screenshot
        shot("2-grid")

        // detail sheet
        tile.tap()
        XCTAssertTrue(app.staticTexts["token"].waitForExistence(timeout: 5), "detail sheet did not open")
        sleep(2)
        shot("3-detail")
        XCTAssertTrue(app.textFields.firstMatch.exists || app.textViews.firstMatch.exists, "note field missing")
        app.buttons["Close"].tap()

        // select mode
        app.buttons["Select"].tap()
        tile.tap()
        XCTAssertTrue(app.staticTexts["1 selected"].waitForExistence(timeout: 3))
        shot("4-select")
        app.buttons["Done"].tap()

        // back, then the camera cover (the simulator has no camera: expect the explanation, not a crash)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        let camera = app.buttons.matching(NSPredicate(format: "label BEGINSWITH '→' OR label CONTAINS 'camera'")).firstMatch
        XCTAssertTrue(camera.waitForExistence(timeout: 5))
        camera.tap()
        let alert = app.alerts.firstMatch            // camera permission prompt, if the sim shows one
        if alert.waitForExistence(timeout: 2) { alert.buttons.element(boundBy: 1).tap() }
        sleep(2)
        shot("5-capture")
        let done = app.buttons["capture-done"]
        XCTAssertTrue(done.exists, "capture view chrome missing")
        XCTAssertTrue(app.buttons["Shutter"].exists)
        done.tap()

        // inbox triage (only when something is waiting): look, don't file
        let triage = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'inbox · file'")).firstMatch
        if triage.waitForExistence(timeout: 3) {
            triage.tap()
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'file-'")).firstMatch.waitForExistence(timeout: 8), "no filing choices")
            sleep(2)
            shot("8-triage")
            // A destination with an action set asks for the verb before filing. Look, then back out:
            // this test must not file anything.
            let pantry = app.buttons["file-pantry"]
            if pantry.exists {
                pantry.tap()
                if app.buttons["verb-consume"].waitForExistence(timeout: 3) {
                    shot("9-triage-verb")
                    app.buttons["pantry"].tap()
                    XCTAssertTrue(app.buttons["file-pantry"].waitForExistence(timeout: 3), "back from the verb step")
                }
            }
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }

        // records browser
        app.staticTexts["records"].tap()
        XCTAssertTrue(app.navigationBars["records"].waitForExistence(timeout: 5))
        sleep(1)
        shot("6-records")
        if app.staticTexts["order_items"].exists {
            app.staticTexts["order_items"].tap()
            sleep(2)
            shot("7-order-items")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.staticTexts["pantry"].waitForExistence(timeout: 5))
    }
}

/// Share → Snapz from the Photos app (the simulator ships with sample photos).
/// Same environment as above; SNAPZ_SHARE_COUNT (default 2) photos go to the inbox.
final class SnapzShareUITests: XCTestCase {
    private let env = ProcessInfo.processInfo.environment

    private func shot(_ name: String) {
        guard let dir = env["SNAPZ_SHOTS"] else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }

    func testShareFromPhotos() throws {
        continueAfterFailure = false
        guard let url = env["SNAPZ_URL"], let token = env["SNAPZ_TOKEN"] else {
            throw XCTSkip("set TEST_RUNNER_SNAPZ_URL and TEST_RUNNER_SNAPZ_TOKEN")
        }
        // Launching the app once signs it in and publishes server + token to the extension.
        let app = XCUIApplication()
        app.launchEnvironment["SNAPZ_DEBUG_TOKEN"] = token
        app.launchArguments += ["-serverURL", url]
        app.launch()
        XCTAssertTrue(app.staticTexts["pantry"].waitForExistence(timeout: 10), "collections did not load")
        app.terminate()

        let photos = XCUIApplication(bundleIdentifier: "com.apple.mobileslideshow")
        photos.launch()
        for label in ["Continue", "Not Now", "OK"] where photos.buttons[label].waitForExistence(timeout: 2) { photos.buttons[label].tap() }
        shot("s1-photos")
        let select = photos.buttons["Select"]
        if !select.waitForExistence(timeout: 8) { print("HIERARCHY", photos.debugDescription); XCTFail("Photos has no Select button") }
        select.tap()
        let cells = photos.images.matching(NSPredicate(format: "label CONTAINS 'Photo' OR label CONTAINS 'photo'"))
        let n = Int(env["SNAPZ_SHARE_COUNT"] ?? "") ?? 2
        var picked = 0
        for i in 0..<min(cells.count, 12) where picked < n {
            let c = cells.element(boundBy: i)
            if c.isHittable { c.tap(); picked += 1 }
        }
        XCTAssertEqual(picked, n, "could not select \(n) photos")
        shot("s2-selected")
        let share = photos.buttons["Share"]
        if !share.waitForExistence(timeout: 5) { print("HIERARCHY", photos.debugDescription); XCTFail("no Share button") }
        share.tap()
        shot("s3-sheet")
        let snapz = photos.descendants(matching: .any).matching(NSPredicate(format: "label == 'Snapz'")).firstMatch
        if !snapz.waitForExistence(timeout: 10) { shot("s3-no-snapz"); print("HIERARCHY", photos.debugDescription); XCTFail("Snapz is not in the share sheet") }
        snapz.tap()

        let send = photos.buttons["share-send"]
        if !send.waitForExistence(timeout: 15) { shot("s4-no-ext"); print("HIERARCHY", photos.debugDescription); XCTFail("extension UI did not appear") }
        let ready = NSPredicate(format: "isEnabled == true")
        expectation(for: ready, evaluatedWith: send)
        waitForExpectations(timeout: 30)
        shot("s4-extension")
        print("SHARE-DEST", photos.staticTexts["share-dest"].label)
        send.tap()
        sleep(1)
        shot("s5-sending")
        // All sent: the sheet closes by itself.
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: photos.staticTexts["share-summary"])
        waitForExpectations(timeout: 60)
        shot("s6-after")
    }
}

/// A `snapz://<host>` link offers to repoint the app; nothing changes unless confirmed.
final class SnapzLinkUITests: XCTestCase {
    func testServerLinkOffersToSwitch() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-serverURL", "http://localhost:1"]
        app.launch()
        XCUIDevice.shared.system.open(URL(string: "snapz://snapz.example.net")!)
        // iOS may ask before handing a custom-scheme link to the app.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.buttons["Open"].waitForExistence(timeout: 3) { springboard.buttons["Open"].tap() }
        // The made-up server is unreachable; if a token is left over in the Keychain the app says so first.
        if app.alerts["Snapz"].waitForExistence(timeout: 4) { app.alerts["Snapz"].buttons["OK"].tap() }
        let alert = app.alerts.matching(NSPredicate(format: "label CONTAINS 'Point Snapz at'")).firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 10), "no offer shown")
        XCTAssertTrue(alert.label.contains("https://snapz.example.net"), alert.label)
        if let dir = ProcessInfo.processInfo.environment["SNAPZ_SHOTS"] {
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("link-offer.png"))
        }
        alert.buttons["Cancel"].tap()
        // The sign-in callback shares the scheme and must not be mistaken for a server.
        XCUIDevice.shared.system.open(URL(string: "snapz://auth?code=x")!)
        if springboard.buttons["Open"].waitForExistence(timeout: 3) { springboard.buttons["Open"].tap() }
        XCTAssertFalse(app.alerts.matching(NSPredicate(format: "label CONTAINS 'Point Snapz at'")).firstMatch.waitForExistence(timeout: 3), "auth callback treated as a server link")
        // Confirming switches the server shown on the sign-in screen.
        XCUIDevice.shared.system.open(URL(string: "snapz://other.example.net:8443")!)
        if springboard.buttons["Open"].waitForExistence(timeout: 3) { springboard.buttons["Open"].tap() }
        let second = app.alerts.matching(NSPredicate(format: "label CONTAINS 'Point Snapz at'")).firstMatch
        XCTAssertTrue(second.waitForExistence(timeout: 10))
        (second.buttons["Add"].exists ? second.buttons["Add"] : second.buttons["Switch"]).tap()
        // Never signed in there: the sign-in screen comes up for it.
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        XCTAssertEqual(field.value as? String, "https://other.example.net:8443")
    }
}

/// Two saved servers: add the second by link, sign in there, switch back without signing in again.
/// Needs TEST_RUNNER_SNAPZ_URL/_TOKEN (a server with a `pantry` collection) and
/// TEST_RUNNER_SNAPZ_URL2/_TOKEN2 (another server, on another port of localhost).
final class SnapzServersUITests: XCTestCase {
    func testAddSecondServerAndSwitchBack() throws {
        continueAfterFailure = false
        let env = ProcessInfo.processInfo.environment
        guard let url = env["SNAPZ_URL"], let token = env["SNAPZ_TOKEN"], let url2 = env["SNAPZ_URL2"], let token2 = env["SNAPZ_TOKEN2"],
              let port1 = URL(string: url)?.port, let u2 = URL(string: url2), let port2 = u2.port else {
            throw XCTSkip("set TEST_RUNNER_SNAPZ_URL, _TOKEN, _URL2, _TOKEN2")
        }
        func shot(_ name: String) {
            guard let dir = env["SNAPZ_SHOTS"] else { return }
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
        let app = XCUIApplication()
        app.launchEnvironment["SNAPZ_DEBUG_TOKEN"] = token
        app.launchArguments += ["-serverURL", url]
        app.launch()
        XCTAssertTrue(app.staticTexts["pantry"].waitForExistence(timeout: 10), "first server did not load")

        // Add the second server by link; it has never been signed in to, so the sign-in screen comes up.
        XCUIDevice.shared.system.open(URL(string: "snapz://\(u2.host!):\(port2)?insecure=1")!)
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        if springboard.buttons["Open"].waitForExistence(timeout: 3) { springboard.buttons["Open"].tap() }
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 10), "no offer shown")
        shot("servers-1-offer")
        let offer = app.alerts.firstMatch
        (offer.buttons["Add"].exists ? offer.buttons["Add"] : offer.buttons["Switch"]).tap()
        // The Keychain outlives a reinstall, so a simulator that has run this before may already hold
        // the second server's token; otherwise sign in with the pasted-token form.
        let secure = app.secureTextFields.firstMatch
        if secure.waitForExistence(timeout: 4) {
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "\(port1)")).firstMatch.exists, "first server not offered on the sign-in screen")
            secure.tap()
            secure.typeText(token2)
            app.buttons["Use token"].tap()
            // Typing into a secure field makes iOS offer to save it as a password.
            for host in [app, springboard] where host.buttons["Not Now"].waitForExistence(timeout: 3) { host.buttons["Not Now"].tap(); break }
        }
        XCTAssertTrue(app.staticTexts["inbox"].waitForExistence(timeout: 10), "second server did not load")
        XCTAssertFalse(app.staticTexts["pantry"].exists, "still showing the first server's collections")

        // Back to the first one from the title menu: no sign-in.
        let menu = app.descendants(matching: .any).matching(identifier: "vault-menu").firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        menu.tap()
        shot("servers-2-menu")
        let first = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "\(port1)")).firstMatch
        XCTAssertTrue(first.waitForExistence(timeout: 5), "first server missing from the menu")
        first.tap()
        XCTAssertTrue(app.staticTexts["pantry"].waitForExistence(timeout: 10), "did not switch back")

        // Both survive a relaunch, and the active one is where we left off.
        app.terminate()
        let again = XCUIApplication()
        again.launch()
        XCTAssertTrue(again.staticTexts["pantry"].waitForExistence(timeout: 10), "active server not remembered")
        again.buttons.matching(NSPredicate(format: "label CONTAINS 'gear' OR identifier == 'gearshape'")).firstMatch.tap()
        XCTAssertTrue(again.staticTexts["Servers"].waitForExistence(timeout: 5) || again.staticTexts["SERVERS"].waitForExistence(timeout: 2))
        shot("servers-3-settings")
        XCTAssertTrue(again.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "\(port2)")).firstMatch.exists, "second server not in settings")
    }
}

/// People: share a vault with someone, invite someone new, take both back. Needs the usual
/// TEST_RUNNER_SNAPZ_URL/_TOKEN of a server run by the token's owner.
final class SnapzPeopleUITests: XCTestCase {
    func testShareInviteAndRemove() throws {
        continueAfterFailure = false
        let env = ProcessInfo.processInfo.environment
        guard let url = env["SNAPZ_URL"], let token = env["SNAPZ_TOKEN"] else { throw XCTSkip("set TEST_RUNNER_SNAPZ_URL and TEST_RUNNER_SNAPZ_TOKEN") }
        let app = XCUIApplication()
        app.launchEnvironment["SNAPZ_DEBUG_TOKEN"] = token
        app.launchArguments += ["-serverURL", url]
        app.launch()
        XCTAssertTrue(app.staticTexts["pantry"].waitForExistence(timeout: 10), "collections did not load")

        let menu = app.descendants(matching: .any).matching(identifier: "vault-menu").firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        menu.tap()
        app.buttons["People…"].tap()
        let email = app.textFields["email"]
        XCTAssertTrue(email.waitForExistence(timeout: 5), "people sheet did not open")

        // Share with someone who (as far as this test goes) already signs in.
        let who = "uitest-\(Int(Date().timeIntervalSince1970))@example.com"
        email.tap(); email.typeText(who)
        app.buttons["Share"].tap()
        let row = app.staticTexts[who]
        XCTAssertTrue(row.waitForExistence(timeout: 5), "shared member not listed")

        // Invite someone new: a link to send shows up.
        let guest = "guest-\(Int(Date().timeIntervalSince1970))@example.com"
        email.tap(); email.typeText(guest)
        app.buttons["Invite"].tap()
        XCTAssertTrue(app.buttons["Send link"].waitForExistence(timeout: 8), "no invitation link")
        if let dir = env["SNAPZ_SHOTS"] {
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("people.png"))
        }

        // Take both back.
        for address in [who, guest] {
            let r = app.staticTexts.matching(NSPredicate(format: "label == %@", address)).firstMatch
            while r.exists {
                r.swipeLeft()
                let b = app.buttons["Remove"].exists ? app.buttons["Remove"] : app.buttons["Withdraw"]
                if !b.waitForExistence(timeout: 2) { break }
                b.tap()
                sleep(1)
            }
        }
        XCTAssertFalse(app.staticTexts[who].exists, "member still listed after removal")
        app.buttons["Done"].tap()
    }
}
