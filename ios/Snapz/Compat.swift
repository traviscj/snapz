import AVFoundation
import ImageIO
import SwiftUI

// Everything platform-specific lives in this file. The rest of the app is plain SwiftUI +
// AVFoundation, which also lets it be type-checked on a Mac without the iOS SDK.

#if canImport(UIKit)
import UIKit

/// Hosts the camera's preview layer and keeps it sized to the view.
struct CameraPreview: UIViewRepresentable {
    let layer: AVCaptureVideoPreviewLayer

    final class Host: UIView {
        var preview: AVCaptureVideoPreviewLayer? {
            didSet {
                oldValue?.removeFromSuperlayer()
                if let preview { layer.addSublayer(preview) }
            }
        }
        override func layoutSubviews() {
            super.layoutSubviews()
            preview?.frame = bounds
        }
    }

    func makeUIView(context: Context) -> Host {
        let v = Host()
        v.backgroundColor = .black
        v.preview = layer
        return v
    }

    func updateUIView(_ v: Host, context: Context) {}
}

enum Device {
    static var name: String { UIDevice.current.name }
}
#else
import AppKit

struct CameraPreview: NSViewRepresentable {
    let layer: AVCaptureVideoPreviewLayer

    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        v.wantsLayer = true
        layer.frame = v.bounds
        layer.autoresizingMask = [.layerWidthSizable, .layerHeightSizable]
        v.layer?.addSublayer(layer)
        return v
    }

    func updateNSView(_ v: NSView, context: Context) {}
}

enum Device {
    static var name: String { Host.current().localizedName ?? "mac" }
}
#endif

extension View {
    @ViewBuilder func inlineTitle() -> some View {
        #if os(iOS)
        self.navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// Field for URLs / tokens: no autocorrect, no capitalization.
    @ViewBuilder func rawTextEntry(url: Bool = false) -> some View {
        #if os(iOS)
        self.textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(url ? .URL : .asciiCapable)
        #else
        self.autocorrectionDisabled()
        #endif
    }

    @ViewBuilder func cover<C: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> C) -> some View {
        #if os(iOS)
        self.fullScreenCover(isPresented: isPresented, content: content)
        #else
        self.sheet(isPresented: isPresented, content: content)
        #endif
    }

    @ViewBuilder func hideStatusBar() -> some View {
        #if os(iOS)
        self.statusBarHidden(true)
        #else
        self
        #endif
    }
}

enum Pasteboard {
    static var string: String? {
        #if canImport(UIKit)
        UIPasteboard.general.string
        #else
        NSPasteboard.general.string(forType: .string)
        #endif
    }
}

/// Decodes (and downsamples) image bytes off the main thread; applies EXIF orientation.
enum ImageDecoder {
    static func decode(_ data: Data, maxPixel: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }
}
