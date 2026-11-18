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
