import SwiftUI
import PhotosUI
import ImageIO
import QuickLook
import UniformTypeIdentifiers
import UIKit
import SwellSignal

/// Photo preparation shared by the picker and the camera: scaled to at most 1600 px on the long side, rotated upright,
/// re-encoded as JPEG quality 82 (like Android). Re-encoding writes no EXIF, GPS or camera data.
enum PhotoPrep {
    static let maxSide = 1600
    static let quality = 0.82

    /// From a file on disk (the picker's copy). Only the scaled pixels are decoded, never the full image.
    static func jpeg(from url: URL) -> Data? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return encode(src)
    }

    static func jpeg(from data: Data) -> Data? {
        guard let src = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return encode(src)
    }

    private static func encode(_ src: CGImageSource) -> Data? {
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceThumbnailMaxPixelSize: maxSide, kCGImageSourceShouldCacheImmediately: true]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        // No metadata dictionary is passed: the output carries no EXIF, GPS or maker notes. Orientation is already in the pixels.
        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return stripMetadata(out as Data)
    }

    /// ImageIO still writes a small Exif block (pixel size, colour space) and an empty Photoshop block. Drop every APP1..APP15
    /// segment so the file carries no metadata at all; JFIF (APP0) and the image data stay.
    static func stripMetadata(_ d: Data) -> Data {
        let b = [UInt8](d)
        guard b.count > 4, b[0] == 0xFF, b[1] == 0xD8 else { return d }
        var out: [UInt8] = [0xFF, 0xD8]; var i = 2
        while i + 4 <= b.count, b[i] == 0xFF {
            let m = b[i + 1]
            if m == 0xDA { break }
            let len = Int(b[i + 2]) << 8 | Int(b[i + 3])
            guard len >= 2, i + 2 + len <= b.count else { return d }
            if !(m >= 0xE1 && m <= 0xEF) { out += b[i..<(i + 2 + len)] }
            i += 2 + len
        }
        out += b[i...]
        return Data(out)
    }

    /// From the camera.
    static func jpeg(from image: UIImage) -> Data? {
        guard let d = image.jpegData(compressionQuality: 1) else { return nil }
        return jpeg(from: d)
    }

    static func name() -> String { "photo-\(Int(Date().timeIntervalSince1970 * 1000)).jpg" }
}

/// The system photo picker: runs out of process, so Swell gets only the photo the person taps. No library permission.
struct PhotoPicker: UIViewControllerRepresentable {
    let onPick: (Data?) -> Void
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var cfg = PHPickerConfiguration(photoLibrary: .shared())
        cfg.filter = .images; cfg.selectionLimit = 1; cfg.preferredAssetRepresentationMode = .current
        let p = PHPickerViewController(configuration: cfg); p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ vc: PHPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick) }
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPick: (Data?) -> Void
        init(_ f: @escaping (Data?) -> Void) { onPick = f }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard let r = results.first else { return }
            r.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                let d = url.flatMap { PhotoPrep.jpeg(from: $0) }
                DispatchQueue.main.async { self.onPick(d) }
            }
        }
    }
}

/// The camera, for one photo.
struct CameraPicker: UIViewControllerRepresentable {
    let onPick: (Data?) -> Void
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let p = UIImagePickerController(); p.sourceType = .camera; p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick) }
    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPick: (Data?) -> Void
        init(_ f: @escaping (Data?) -> Void) { onPick = f }
        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let img = info[.originalImage] as? UIImage
            picker.dismiss(animated: true)
            DispatchQueue.global(qos: .userInitiated).async {
                let d = img.flatMap { PhotoPrep.jpeg(from: $0) }
                DispatchQueue.main.async { self.onPick(d) }
            }
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true) }
    }
}

/// The Files picker. The file is copied into the app's own temporary folder first, then read.
struct FilePicker: UIViewControllerRepresentable {
    struct Picked { let name: String; let mime: String; let data: Data }
    let onPick: (Picked?, String?) -> Void   // picked file, or a message for the person
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let p = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        p.allowsMultipleSelection = false; p.delegate = context.coordinator
        return p
    }
    func updateUIViewController(_ vc: UIDocumentPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick) }
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (Picked?, String?) -> Void
        init(_ f: @escaping (Picked?, String?) -> Void) { onPick = f }
        func documentPicker(_ c: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let u = urls.first else { return }
            defer { try? FileManager.default.removeItem(at: u) }
            let size = (try? u.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if size > 5_000_000 { onPick(nil, "Files can be up to 5 MB."); return }
            guard let d = try? Data(contentsOf: u), !d.isEmpty else { onPick(nil, "That file could not be read."); return }
            let mime = UTType(filenameExtension: u.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            onPick(Picked(name: u.lastPathComponent, mime: mime, data: d), nil)
        }
    }
}

/// Downscaled thumbnails of received/sent photos, decoded once.
@MainActor enum Thumbs {
    private static let cache = NSCache<NSString, UIImage>()
    static func image(_ url: URL, id: String, side: Int = 640) -> UIImage? {
        if let c = cache.object(forKey: id as NSString) { return c }
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                                                    kCGImageSourceThumbnailMaxPixelSize: side] as CFDictionary) else { return nil }
        let i = UIImage(cgImage: cg); cache.setObject(i, forKey: id as NSString); return i
    }
    static func forget(_ id: String) { cache.removeObject(forKey: id as NSString) }
}

/// A photo in a bubble: tap opens it full screen.
struct PhotoBubble: View {
    @EnvironmentObject var model: AppModel
    let message: ChatMessage
    let mine: Bool
    @State private var open = false

    var body: some View {
        let url = model.fileURL(message.fileId)
        let img = url.flatMap { Thumbs.image($0, id: message.fileId) }
        ZStack {
            if let img {
                Image(uiImage: img).resizable().scaledToFill().frame(width: 220, height: 220 * min(1.5, max(0.6, img.size.height / max(1, img.size.width)))).clipped()
            } else {
                Rectangle().fill(Ocean.card).frame(width: 220, height: 160).overlay(Image(systemName: "photo").font(.largeTitle).foregroundStyle(.secondary))
            }
            if let p = model.progress[message.id], p < 100, message.status == .sending {
                ZStack { Color.black.opacity(0.35); ProgressView(value: Double(p), total: 100).tint(.white).frame(width: 100) }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .contentShape(Rectangle())
        .onTapGesture { if url != nil { open = true } }
        .accessibilityElement().accessibilityLabel("Photo").accessibilityAddTraits(.isButton)
        .fullScreenCover(isPresented: $open) { if let url { PhotoViewer(url: url, name: message.fileName) } }
    }
}

/// Full-screen photo: pinch/double-tap to zoom. Sharing or saving happens only from the Share button.
struct PhotoViewer: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    let name: String
    @State private var scale = 1.0
    @State private var base = 1.0
    @State private var image: UIImage?
    @State private var sharing = false

    var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()
            if let image {
                Image(uiImage: image).resizable().scaledToFit().scaleEffect(scale)
                    .gesture(MagnifyGesture().onChanged { scale = max(1, min(6, base * $0.magnification)) }.onEnded { _ in base = scale })
                    .onTapGesture(count: 2) { withAnimation { scale = scale > 1 ? 1 : 2.5; base = scale } }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .accessibilityLabel("Photo")
            }
            HStack {
                Button { dismiss() } label: { Image(systemName: "xmark.circle.fill").font(.title).symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.5)) }
                    .accessibilityLabel("Close")
                Spacer()
                Button { sharing = true } label: { Image(systemName: "square.and.arrow.up.circle.fill").font(.title).symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.5)) }
                    .accessibilityLabel("Share or save")
            }.padding()
        }
        .task {
            if let src = CGImageSourceCreateWithURL(url as CFURL, nil),
               let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                                                                     kCGImageSourceThumbnailMaxPixelSize: 2400] as CFDictionary) { image = UIImage(cgImage: cg) }
        }
        .sheet(isPresented: $sharing) { ShareSheet(items: [url]) }
        .statusBarHidden()
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// A non-image file in a bubble: icon, name, size. Tap opens it in Quick Look.
struct FileBubble: View {
    @EnvironmentObject var model: AppModel
    let message: ChatMessage
    let mine: Bool
    @State private var preview: URL?

    var body: some View {
        let tint = mine ? Color.white : Ocean.accent
        HStack(spacing: 10) {
            ZStack {
                Image(systemName: "doc.fill").font(.system(size: 30)).foregroundStyle(tint)
                if let p = model.progress[message.id], p < 100, message.status == .sending { ProgressView().tint(tint).scaleEffect(0.7) }
            }.frame(width: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(message.fileName.isEmpty ? "File" : message.fileName).font(.subheadline.weight(.semibold)).lineLimit(2).truncationMode(.middle)
                    .foregroundStyle(mine ? Ocean.onAccent : Color.primary)
                Text(Self.size(message.fileSize)).font(.caption).foregroundStyle(mine ? Color.white.opacity(0.85) : Color.secondary)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { open() }
        .accessibilityElement(children: .combine).accessibilityAddTraits(.isButton)
        .quickLookPreview($preview)
    }

    static func size(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }

    /// Quick Look needs a file with the right extension: a short-lived copy under the real name in the temporary folder.
    private func open() {
        guard let src = model.fileURL(message.fileId) else { return }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("open-\(message.fileId.prefix(8))", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dst = dir.appendingPathComponent(message.fileName.isEmpty ? "file" : message.fileName)
        if (try? FileManager.default.copyItem(at: src, to: dst)) != nil { preview = dst }
    }
}
