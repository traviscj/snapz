import AVFoundation
import SwiftUI
import Vision

/// A machine-readable code currently visible in the viewfinder.
struct LiveCode: Identifiable, Hashable {
    var id: String { value }
    let symbology: String
    let value: String
    /// Outline in the preview layer's coordinate space (points), for drawing the HUD box.
    let corners: [CGPoint]
    /// Bounds as fractions of the *unrotated sensor frame* (origin top-left).
    let sensorBox: CGRect
    /// Seen in enough consecutive frames to trust the decode.
    var locked: Bool
}

struct CapturedShot {
    let jpeg: Data
    /// Rotation applied at capture (0/90/180/270), needed to map sensor boxes onto the upright photo.
    let rotationAngle: CGFloat
    /// What the live decoder was seeing at the instant of the shutter.
    let liveCodes: [LiveCode]
}

/// Owns the capture session. Photos come back as JPEG `Data` and go straight to the upload queue —
/// nothing here touches PhotoKit, so nothing lands in the system photo library.
///
/// Alongside stills it runs the system's live barcode decoder on the video stream. That matters
/// more than it sounds: the live decoder gets ~30 tries a second at slightly different focus and
/// angles, where a single still gets exactly one, often the blurry one.
///
/// @unchecked Sendable: capture state is confined to `queue`; published state is set on main.
final class CameraController: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    enum Status: Equatable { case idle, running, denied, unavailable(String) }

    @Published private(set) var status: Status = .idle
    @Published private(set) var liveCodes: [LiveCode] = []
    @Published private(set) var focusing = false
    /// Which physical lens the virtual camera is using right now, e.g. "1x" or "macro".
    @Published private(set) var lens = ""
    let previewLayer = AVCaptureVideoPreviewLayer()

    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let metadata = AVCaptureMetadataOutput()
    private let queue = DispatchQueue(label: "snapz.camera")
    private var device: AVCaptureDevice?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var configured = false
    private var waiting: [Int64: (CheckedContinuation<CapturedShot, Error>, CGFloat, [LiveCode])] = [:]
    private var observers: [NSKeyValueObservation] = []
    private var streak: [String: Int] = [:]     // value -> consecutive metadata callbacks seen
    private var clearLive: DispatchWorkItem?

    /// Consecutive detections before a code counts as locked (callbacks arrive at frame rate).
    static let lockFrames = 4

    static let symbologies: [AVMetadataObject.ObjectType: String] = [
        .ean13: "ean13", .ean8: "ean8", .upce: "upce", .code128: "code128", .code39: "code39",
        .itf14: "itf14", .qr: "qr", .dataMatrix: "datamatrix", .pdf417: "pdf417", .aztec: "aztec",
    ]

    override init() {
        super.init()
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
    }

    @MainActor
    func start() async {
        guard await AVCaptureDevice.requestAccess(for: .video) else { status = .denied; return }
        let result: Status = await withCheckedContinuation { cont in
            queue.async {
                if !self.configured {
                    if let failure = self.configure() { cont.resume(returning: .unavailable(failure)); return }
                    self.configured = true
                }
                if !self.session.isRunning { self.session.startRunning() }
                cont.resume(returning: .running)
            }
        }
        status = result
        if result == .running, let rotation {
            // UI is portrait-locked, so the preview angle is fixed for the life of the session.
            previewLayer.connection?.videoRotationAngle = rotation.videoRotationAngleForHorizonLevelPreview
        }
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    /// Runs on `queue`. Returns a failure description, or nil on success.
    private func configure() -> String? {
        // Prefer a virtual multi-lens camera: it switches to the ultra-wide for close subjects
        // ("macro"), which is the difference between a readable UPC at 10 cm and a smear — the
        // main lens alone can't focus that close.
        let found = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInTripleCamera, .builtInDualWideCamera, .builtInWideAngleCamera], mediaType: .video, position: .back
        ).devices.first ?? AVCaptureDevice.default(for: .video)
        guard let device = found else { return "No camera on this device." }
        guard let input = try? AVCaptureDeviceInput(device: device) else { return "Camera is in use or unavailable." }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        guard session.canAddInput(input), session.canAddOutput(output) else { return "Camera configuration failed." }
        session.addInput(input)
        session.addOutput(output)
        output.maxPhotoQualityPrioritization = .balanced

        if session.canAddOutput(metadata) {
            session.addOutput(metadata)
            // Delivered on main: results feed @Published state and the preview-layer transform.
            metadata.setMetadataObjectsDelegate(self, queue: .main)
            metadata.metadataObjectTypes = metadata.availableMetadataObjectTypes.filter { Self.symbologies[$0] != nil }
        }

        if (try? device.lockForConfiguration()) != nil {
            // A virtual device's 1.0 is the ultra-wide; start at the main lens's field of view.
            if let wide = device.virtualDeviceSwitchOverVideoZoomFactors.first {
                device.videoZoomFactor = CGFloat(truncating: wide)
            }
            if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
            if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
            device.isSubjectAreaChangeMonitoringEnabled = true
            device.unlockForConfiguration()
        }
        self.device = device
        rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        observers = [
            device.observe(\.isAdjustingFocus, options: [.initial, .new]) { [weak self] d, _ in
                let v = d.isAdjustingFocus
                DispatchQueue.main.async { self?.focusing = v }
            },
            device.observe(\.activePrimaryConstituent, options: [.initial, .new]) { [weak self] d, _ in
                let label: String
                switch d.activePrimaryConstituent?.deviceType {
                case .builtInUltraWideCamera?: label = "macro"
                case .builtInTelephotoCamera?: label = "tele"
                default: label = "1x"
                }
                DispatchQueue.main.async { self?.lens = label }
            },
        ]
        // The scene changed after a tap-to-focus: hand focus back to continuous AF.
        NotificationCenter.default.addObserver(forName: AVCaptureDevice.subjectAreaDidChangeNotification, object: device, queue: nil) { [weak self] _ in
            self?.queue.async { self?.applyFocus(point: nil) }
        }
        return nil
    }

    /// Tap-to-focus. `layerPoint` is in the preview layer's coordinate space.
    @MainActor
    func focus(atLayerPoint layerPoint: CGPoint) {
        let p = previewLayer.captureDevicePointConverted(fromLayerPoint: layerPoint)
        queue.async { self.applyFocus(point: p) }
    }

    /// Runs on `queue`. nil = back to continuous autofocus on the center.
    private func applyFocus(point: CGPoint?) {
        guard let device, (try? device.lockForConfiguration()) != nil else { return }
        defer { device.unlockForConfiguration() }
        if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = point ?? CGPoint(x: 0.5, y: 0.5) }
        let mode: AVCaptureDevice.FocusMode = point == nil ? .continuousAutoFocus : .autoFocus
        if device.isFocusModeSupported(mode) { device.focusMode = mode }
        if device.isExposurePointOfInterestSupported { device.exposurePointOfInterest = point ?? CGPoint(x: 0.5, y: 0.5) }
        if device.isExposureModeSupported(.continuousAutoExposure) { device.exposureMode = .continuousAutoExposure }
    }

    // MARK: live codes (main queue)

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        var seen: [LiveCode] = []
        for case let code as AVMetadataMachineReadableCodeObject in objects {
            guard let name = Self.symbologies[code.type], let value = code.stringValue, !value.isEmpty,
                  !seen.contains(where: { $0.value == value }) else { continue }
            let onScreen = previewLayer.transformedMetadataObject(for: code) as? AVMetadataMachineReadableCodeObject
            let n = (streak[value] ?? 0) + 1
            streak[value] = n
            seen.append(LiveCode(symbology: name, value: value, corners: onScreen?.corners ?? [], sensorBox: code.bounds, locked: n >= Self.lockFrames))
        }
        // A code that drops out for a frame starts its streak over: locks have to be earned.
        streak = streak.filter { k, _ in seen.contains { $0.value == k } }
        liveCodes = seen
        // The delegate isn't guaranteed a final empty callback; don't leave a stale lock on screen.
        clearLive?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.liveCodes = []; self?.streak = [:] }
        clearLive = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    // MARK: stills

    /// One JPEG. The device's physical orientation is recorded as EXIF orientation (the server
    /// honors it for thumbnails), so landscape shots come out landscape despite the portrait UI.
    @MainActor
    func capture() async throws -> CapturedShot {
        let live = liveCodes
        return try await withCheckedThrowingContinuation { cont in
            queue.async {
                guard self.session.isRunning else { cont.resume(throwing: APIError(status: 0, message: "camera not running")); return }
                let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                settings.photoQualityPrioritization = .balanced
                var angle: CGFloat = 90
                if let a = self.rotation?.videoRotationAngleForHorizonLevelCapture, let conn = self.output.connection(with: .video),
                   conn.isVideoRotationAngleSupported(a) {
                    conn.videoRotationAngle = a
                    angle = a
                }
                self.waiting[settings.uniqueID] = (cont, angle, live)
                self.output.capturePhoto(with: settings, delegate: self)
            }
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        queue.async {
            guard let (cont, angle, live) = self.waiting.removeValue(forKey: photo.resolvedSettings.uniqueID) else { return }
            if let data = photo.fileDataRepresentation() { cont.resume(returning: CapturedShot(jpeg: data, rotationAngle: angle, liveCodes: live)) }
            else { cont.resume(throwing: error ?? APIError(status: 0, message: "capture produced no image")) }
        }
    }
}

enum BarcodeScanner {
    private static let symbologies: [VNBarcodeSymbology] = [.ean13, .ean8, .upce, .code128, .code39, .itf14, .qr, .dataMatrix, .pdf417, .aztec]

    /// Barcodes in a still image, boxes as [x, y, w, h] fractions of the upright image (origin
    /// top-left). Never throws: no barcode is the normal case.
    static func scan(jpeg: Data) async -> [Barcode] {
        await Task.detached(priority: .userInitiated) {
            let request = VNDetectBarcodesRequest()
            request.symbologies = symbologies
            let handler = VNImageRequestHandler(data: jpeg, options: [:])
            guard (try? handler.perform([request])) != nil else { return [] }
            var seen = Set<String>()
            return (request.results ?? []).compactMap { obs -> Barcode? in
                guard let value = obs.payloadStringValue, !value.isEmpty, seen.insert(value).inserted else { return nil }
                let b = obs.boundingBox // normalized, origin bottom-left
                return Barcode(symbology: obs.symbology.rawValue.replacingOccurrences(of: "VNBarcodeSymbology", with: "").lowercased(), value: value,
                               box: [b.minX, 1 - b.maxY, b.width, b.height].map { round4(Double($0)) }, source: "still")
            }
        }.value
    }

    /// The saved photo's detections: everything the still decoder found (accurate boxes), plus
    /// anything only the live decoder caught, with its sensor-space box rotated onto the photo.
    static func merge(still: [Barcode], shot: CapturedShot) -> [Barcode] {
        var out = still
        for live in shot.liveCodes where !out.contains(where: { $0.value == live.value }) {
            out.append(Barcode(symbology: live.symbology, value: live.value, box: uprightBox(live.sensorBox, angle: shot.rotationAngle), source: "live"))
        }
        return out
    }

    /// Sensor-frame rect -> rect on the image after rotating it clockwise by `angle` degrees.
    static func uprightBox(_ r: CGRect, angle: CGFloat) -> [Double] {
        let (x, y, w, h) = (Double(r.minX), Double(r.minY), Double(r.width), Double(r.height))
        let box: [Double]
        switch Int(angle.rounded()) % 360 {
        case 90: box = [1 - y - h, x, h, w]
        case 180: box = [1 - x - w, 1 - y - h, w, h]
        case 270: box = [y, 1 - x - w, h, w]
        default: box = [x, y, w, h]
        }
        return box.map(round4)
    }

    private static func round4(_ v: Double) -> Double { (min(1, max(0, v)) * 10_000).rounded() / 10_000 }
}
