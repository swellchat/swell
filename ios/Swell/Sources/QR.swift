import SwiftUI
import AVFoundation
import CoreImage.CIFilterBuiltins

/// A QR code for `text`: black on white (also in dark mode), error correction Q, a quiet zone of 4 modules,
/// drawn with sharp edges. `width` is the full size including the quiet zone.
struct QRCodeView: View {
    let text: String
    var width: CGFloat? = nil
    /// Full screen brightness while it is on screen (so a camera reads it easily); restored afterwards.
    var bright = false
    @State private var previous: CGFloat?

    var body: some View {
        Group {
            if let img = Self.image(text) {
                Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                    .frame(width: width ?? UIScreen.main.bounds.width * 0.74).accessibilityLabel("QR code")
            }
        }
        .onAppear { if bright { previous = UIScreen.main.brightness; UIScreen.main.brightness = 1 } }
        .onDisappear { if let p = previous { UIScreen.main.brightness = p; previous = nil } }
    }

    static func image(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8); f.correctionLevel = "Q"
        guard let out = f.outputImage, let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        let n = cg.width, quiet = 4, scale = 12
        let side = (n + quiet * 2) * scale
        let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1; fmt.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: fmt).image { ctx in
            UIColor.white.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
            let g = ctx.cgContext
            g.interpolationQuality = .none
            g.translateBy(x: CGFloat(quiet * scale), y: CGFloat(quiet * scale) + CGFloat(n * scale)); g.scaleBy(x: 1, y: -1)
            g.draw(cg, in: CGRect(x: 0, y: 0, width: n * scale, height: n * scale))
        }
    }
}

/// Camera view that reports the first QR code it reads, once.
struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onDenied: () -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let c = ScannerController(); c.onCode = onCode; c.onDenied = onDenied; return c
    }
    func updateUIViewController(_ c: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        var onDenied: (() -> Void)?
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?
        private var done = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: configure()
            case .notDetermined: AVCaptureDevice.requestAccess(for: .video) { ok in DispatchQueue.main.async { ok ? self.configure() : self.onDenied?() } }
            default: onDenied?()
            }
        }

        private func configure() {
            guard let dev = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: dev), session.canAddInput(input) else { onDenied?(); return }
            session.addInput(input)
            let out = AVCaptureMetadataOutput()
            guard session.canAddOutput(out) else { return }
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
            let p = AVCaptureVideoPreviewLayer(session: session)
            p.videoGravity = .resizeAspectFill; p.frame = view.bounds
            view.layer.addSublayer(p); preview = p
            DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
        }

        override func viewDidLayoutSubviews() { super.viewDidLayoutSubviews(); preview?.frame = view.bounds }
        override func viewDidDisappear(_ animated: Bool) { super.viewDidDisappear(animated); if session.isRunning { session.stopRunning() } }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let s = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            done = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            session.stopRunning()
            onCode?(s)
        }
    }
}

/// Full scanner page with a frame and a hint; `onCode` is called once.
struct ScanPage: View {
    let title: String
    let hint: String
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var denied = false

    var body: some View {
        NavigationStack {
            ZStack {
                if denied {
                    VStack(spacing: 14) {
                        Image(systemName: "camera.fill").font(.largeTitle).foregroundStyle(.secondary)
                        Text("Swell needs the camera to scan a code.").font(.headline).multilineTextAlignment(.center)
                        Text("You can allow it in Settings > Swell > Camera.").font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                        Button("Open Settings") { if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) } }
                            .buttonStyle(PrimaryButton()).padding(.top, 8)
                    }.padding(32)
                } else {
                    QRScanner(onCode: { code in onCode(code) }, onDenied: { denied = true }).ignoresSafeArea()
                    RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(Color.white.opacity(0.9), lineWidth: 3)
                        .frame(width: 250, height: 250).accessibilityHidden(true)
                    VStack { Spacer()
                        Text(hint).font(.subheadline.weight(.medium)).foregroundStyle(.white).multilineTextAlignment(.center)
                            .padding(.horizontal, 16).padding(.vertical, 10).background(Capsule().fill(.black.opacity(0.55))).padding(.bottom, 40)
                    }
                }
            }
            .background(Ocean.background.ignoresSafeArea())
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
}
