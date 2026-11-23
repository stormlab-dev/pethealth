import Foundation
import UIKit
import AVFoundation
import CoreTransferable
import UniformTypeIdentifiers

/// Stores pet photos and videos privately in the app's Documents folder.
enum MediaFiles {
    static let directory: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("media", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    static func save(data: Data, ext: String) throws -> String {
        let name = "\(UUID().uuidString).\(ext)"
        try data.write(to: directory.appendingPathComponent(name), options: .completeFileProtection)
        return name
    }

    static func importFile(at url: URL) throws -> String {
        let name = "\(UUID().uuidString).\(url.pathExtension.isEmpty ? "mov" : url.pathExtension.lowercased())"
        try FileManager.default.copyItem(at: url, to: directory.appendingPathComponent(name))
        return name
    }

    static func delete(_ fileName: String) { try? FileManager.default.removeItem(at: directory.appendingPathComponent(fileName)) }

    /// Removes files whose records are gone, e.g. after a pet was deleted.
    static func pruneOrphans(keeping names: [String]) {
        let keep = Set(names)
        for url in (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? [] where !keep.contains(url.lastPathComponent) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    static func duration(of url: URL) async -> Int {
        guard let d = try? await AVURLAsset(url: url).load(.duration) else { return 0 }
        return Int(ceil(d.seconds.isFinite ? d.seconds : 0))
    }

    static func thumbnail(for record: MediaRecord, maxPixels: CGFloat = 400) async -> UIImage? {
        if record.isVideo {
            let gen = AVAssetImageGenerator(asset: AVURLAsset(url: record.url))
            gen.appliesPreferredTrackTransform = true
            gen.maximumSize = CGSize(width: maxPixels, height: maxPixels)
            guard let cg = try? await gen.image(at: .zero).image else { return nil }
            return UIImage(cgImage: cg)
        }
        guard let img = UIImage(contentsOfFile: record.url.path) else { return nil }
        return await img.byPreparingThumbnail(ofSize: fit(img.size, maxPixels)) ?? img
    }

    /// JPEG, at most 1568 px on the long side, base64 encoded, for the AI symptom check.
    static func jpegBase64ForAi(_ data: Data) -> String? {
        guard let img = UIImage(data: data) else { return nil }
        let size = fit(img.size, 1568)
        let scaled = UIGraphicsImageRenderer(size: size).image { _ in img.draw(in: CGRect(origin: .zero, size: size)) }
        return scaled.jpegData(compressionQuality: 0.85)?.base64EncodedString()
    }

    private static func fit(_ s: CGSize, _ m: CGFloat) -> CGSize {
        let f = min(1, m / max(s.width, s.height))
        return CGSize(width: (s.width * f).rounded(), height: (s.height * f).rounded())
    }
}

/// Lets PhotosPicker hand over videos as files instead of loading them into memory.
struct PickedMovie: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(contentType: .movie) { SentTransferredFile($0.url) } importing: { received in
            let copy = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).\(received.file.pathExtension)")
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedMovie(url: copy)
        }
    }
}
