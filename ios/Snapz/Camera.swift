import AVFoundation
import SwiftUI
import Vision

/// Owns the capture session. Photos come back as JPEG `Data` and go straight to the upload queue —
/// nothing here touches PhotoKit, so nothing lands in the system photo library.
// @unchecked Sendable: mutable capture state is confined to `queue`; `status` is only set on the main actor.
final class CameraController: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate, @unchecked Sendable {
    enum Status: Equatable { case idle, running, denied, unavailable(String) }

    @Published private(set) var status: Status = .idle
    let previewLayer = AVCaptureVideoPreviewLayer()

    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "snapz.camera")
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var configured = false
    private var waiting: [Int64: CheckedContinuation<Data, Error>] = [:]

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
        guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) ?? AVCaptureDevice.default(for: .video) else {
            return "No camera on this device."
        }
        guard let input = try? AVCaptureDeviceInput(device: device) else { return "Camera is in use or unavailable." }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        guard session.canAddInput(input), session.canAddOutput(output) else { return "Camera configuration failed." }
        session.addInput(input)
        session.addOutput(output)
        output.maxPhotoQualityPrioritization = .balanced
        rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: previewLayer)
        return nil
    }

    /// One JPEG. The device's physical orientation is recorded as EXIF orientation (the server
    /// honors it for thumbnails), so landscape shots come out landscape despite the portrait UI.
    func capture() async throws -> Data {
        try await withCheckedThrowingContinuation { cont in
            queue.async {
                guard self.session.isRunning else { cont.resume(throwing: APIError(status: 0, message: "camera not running")); return }
                let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
                settings.photoQualityPrioritization = .balanced
                if let angle = self.rotation?.videoRotationAngleForHorizonLevelCapture, let conn = self.output.connection(with: .video),
                   conn.isVideoRotationAngleSupported(angle) {
                    conn.videoRotationAngle = angle
                }
                self.waiting[settings.uniqueID] = cont
                self.output.capturePhoto(with: settings, delegate: self)
            }
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        queue.async {
            guard let cont = self.waiting.removeValue(forKey: photo.resolvedSettings.uniqueID) else { return }
            if let data = photo.fileDataRepresentation() { cont.resume(returning: data) }
            else { cont.resume(throwing: error ?? APIError(status: 0, message: "capture produced no image")) }
        }
    }
}

enum BarcodeScanner {
    /// Retail + common 2D symbologies. UPC-A comes back from Vision as EAN-13 with a leading 0,
    /// which conveniently gives every retail code one canonical 13-digit form.
    private static let symbologies: [VNBarcodeSymbology] = [.ean13, .ean8, .upce, .code128, .code39, .itf14, .qr, .dataMatrix]

    /// Barcodes visible in a still image. Never throws: no barcode is the normal case.
    static func scan(jpeg: Data) async -> [Barcode] {
        await Task.detached(priority: .userInitiated) {
            let request = VNDetectBarcodesRequest()
            request.symbologies = symbologies
            let handler = VNImageRequestHandler(data: jpeg, options: [:])
            guard (try? handler.perform([request])) != nil else { return [] }
            var seen = Set<String>()
            return (request.results ?? []).compactMap { obs -> Barcode? in
                guard let value = obs.payloadStringValue, !value.isEmpty, seen.insert(value).inserted else { return nil }
                return Barcode(symbology: obs.symbology.rawValue.replacingOccurrences(of: "VNBarcodeSymbology", with: "").lowercased(), value: value)
            }
        }.value
    }
}
