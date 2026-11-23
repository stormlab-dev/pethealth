import SwiftUI
import SwiftData
import PhotosUI
import AVKit
import PetPulseCore

/// Photos tab: pick photos and videos, view them, share with family or the vet.
struct MediaSection: View {
    @Bindable var pet: PetRecord
    @ObservedObject private var plans = PlanStore.shared
    @Query private var allMedia: [MediaRecord]
    @State private var picks: [PhotosPickerItem] = []
    @State private var busy = false
    @State private var message: String?
    @State private var showPlans = false
    @State private var open: MediaRecord?

    private var items: [MediaRecord] { pet.media.sorted { $0.takenAtMillis > $1.takenAtMillis } }

    var body: some View {
        Section {
            PhotosPicker(selection: $picks, maxSelectionCount: 20, matching: .any(of: [.images, .videos])) {
                Label(busy ? "Adding…" : "Add photos or videos", systemImage: "photo.badge.plus")
            }
            .disabled(busy)
            .onChange(of: picks) { _, new in if !new.isEmpty { Task { await add(new) } } }
            if let message {
                VStack(alignment: .leading, spacing: 6) {
                    Text(message)
                    if plans.plan == "FREE" { Button("See plans") { showPlans = true } }
                }
            }
        } footer: {
            if let max = plans.limits.maxMedia?.intValue {
                Text("\(allMedia.count) of \(max) on the free plan · clips up to \(plans.limits.maxClipSeconds)s")
            } else {
                Text("Unlimited photos and videos")
            }
        }
        Section {
            if items.isEmpty {
                Text("No photos yet. Add a few of \(pet.name), or a health photo to show your vet.").foregroundStyle(.secondary)
            } else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 6)], spacing: 6) {
                    ForEach(items) { m in
                        MediaThumb(record: m).onTapGesture { open = m }
                    }
                }
            }
        }
        .sheet(item: $open) { MediaViewer(record: $0, petName: pet.name) }
        .sheet(isPresented: $showPlans) { PlansView() }
    }

    private func add(_ selection: [PhotosPickerItem]) async {
        busy = true
        defer { busy = false; picks = [] }
        message = nil
        var count = allMedia.count
        for item in selection {
            let isVideo = item.supportedContentTypes.contains { $0.conforms(to: .movie) }
            do {
                var fileName: String
                var seconds = 0
                if isVideo {
                    guard let movie = try await item.loadTransferable(type: PickedMovie.self) else { continue }
                    seconds = await MediaFiles.duration(of: movie.url)
                    if let block = gate(count: count, video: true, seconds: seconds) { message = block; try? FileManager.default.removeItem(at: movie.url); return }
                    fileName = try MediaFiles.importFile(at: movie.url)
                    try? FileManager.default.removeItem(at: movie.url)
                } else {
                    guard let data = try await item.loadTransferable(type: Data.self) else { continue }
                    if let block = gate(count: count, video: false, seconds: 0) { message = block; return }
                    let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                    fileName = try MediaFiles.save(data: data, ext: ext)
                }
                pet.media.append(MediaRecord(kind: isVideo ? "VIDEO" : "PHOTO", fileName: fileName,
                                             durationSec: isVideo ? seconds : nil, takenAtMillis: Date().epochMillis))
                count += 1
            } catch {
                message = "Couldn't add that item: \(error.localizedDescription)"
            }
        }
    }

    /// Mirrors Entitlements.canAddMedia in the shared core.
    private func gate(count: Int, video: Bool, seconds: Int) -> String? {
        let l = plans.limits
        if let max = l.maxMedia?.intValue, count >= max { return "The free plan holds \(max) photos and videos. Premium is unlimited." }
        if video && seconds > Int(l.maxClipSeconds) {
            return plans.plan == "FREE" ? "Free clips can be up to \(l.maxClipSeconds) seconds. Premium allows 3 minutes." : "Clips can be up to 3 minutes. Trim it and try again."
        }
        return nil
    }
}

struct MediaThumb: View {
    let record: MediaRecord
    @State private var image: UIImage?
    var body: some View {
        Color(.secondarySystemBackground)
            .aspectRatio(1, contentMode: .fit)
            .overlay { if let image { Image(uiImage: image).resizable().scaledToFill() } }
            .overlay { if record.isVideo { Image(systemName: "play.circle.fill").font(.title).foregroundStyle(.white) } }
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .task(id: record.fileName) { image = await MediaFiles.thumbnail(for: record) }
    }
}

struct MediaViewer: View {
    let record: MediaRecord
    let petName: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @State private var image: UIImage?

    var body: some View {
        NavigationStack {
            Group {
                if record.isVideo {
                    VideoPlayer(player: AVPlayer(url: record.url))
                } else if let image {
                    Image(uiImage: image).resizable().scaledToFit()
                } else {
                    ProgressView()
                }
            }
            .task { if !record.isVideo { image = UIImage(contentsOfFile: record.url.path) } }
            .navigationTitle(Date(timeIntervalSince1970: Double(record.takenAtMillis) / 1000).formatted(date: .abbreviated, time: .omitted))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(item: record.url, subject: Text(petName), message: Text("\(petName), from CuteFamilyPet"))
                }
                ToolbarItem(placement: .bottomBar) {
                    Button("Delete", role: .destructive) {
                        MediaFiles.delete(record.fileName)
                        context.delete(record)
                        dismiss()
                    }
                }
            }
        }
    }
}
