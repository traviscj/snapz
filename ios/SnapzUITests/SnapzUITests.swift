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
        let triage = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'from inbox'")).firstMatch
        if triage.waitForExistence(timeout: 3) {
            triage.tap()
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'file-'")).firstMatch.waitForExistence(timeout: 8), "no filing choices")
            sleep(2)
            shot("8-triage")
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
