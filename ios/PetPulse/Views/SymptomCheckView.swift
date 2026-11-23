import SwiftUI
import SwiftData
import PhotosUI
import PetPulseCore

private let redFlagKeys: Set<String> = ["breathing", "collapse", "seizure", "pale_gums", "toxin", "trauma", "retching",
                                        "swollen_abdomen", "straining_urinate", "blood_stool"]
private let onsets: [(String, Int)] = [("Just now", 0), ("A few hours ago", 4), ("About a day", 26), ("2–3 days", 48), ("A week or more", 168)]

struct SymptomCheckView: View {
    let pet: PetRecord
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var selected: Set<String> = []
    @State private var severity = "MILD"
    @State private var onset = 0
    @State private var notes = ""
    @State private var result: TriageResult?
    @State private var bookVet = false
    @ObservedObject private var plans = PlanStore.shared
    @State private var photoItem: PhotosPickerItem?
    @State private var photoData: Data?
    @State private var ai: AiTriageResult?
    @State private var aiBusy = false
    @State private var aiMessage: String?
    @State private var showPlans = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Common signs") { chips(Symptom.entries.filter { !redFlagKeys.contains($0.key) }) }
                Section { chips(Symptom.entries.filter { redFlagKeys.contains($0.key) }) } header: { Text("Serious signs").foregroundStyle(.red) }
                Section {
                    Picker("How bad?", selection: $severity) {
                        ForEach(Severity.entries, id: \.name) { Text($0.name.capitalized).tag($0.name) }
                    }.pickerStyle(.segmented)
                    Picker("Started", selection: $onset) {
                        ForEach(onsets.indices, id: \.self) { Text(onsets[$0].0).tag($0) }
                    }
                    TextField("Notes (what, how often, anything they ate)", text: $notes, axis: .vertical)
                }
                Section {
                    HStack {
                        if let photoData, let img = UIImage(data: photoData) {
                            Image(uiImage: img).resizable().scaledToFill().frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8))
                        }
                        PhotosPicker(selection: $photoItem, matching: .images) {
                            Label(photoData == nil ? "Add a photo" : "Change photo", systemImage: "camera")
                        }
                        if photoData != nil { Spacer(); Button("Remove", role: .destructive) { photoData = nil; photoItem = nil; ai = nil } }
                    }
                    .onChange(of: photoItem) { _, item in
                        Task { photoData = try? await item?.loadTransferable(type: Data.self); ai = nil }
                    }
                } footer: { Text("A clear, close, well-lit photo of the problem area helps the AI check.") }
                Section {
                    Button("How urgent is this?") { ai = nil; result = run().1 }.disabled(selected.isEmpty)
                    Button {
                        Task { await runAi() }
                    } label: {
                        if aiBusy { HStack { ProgressView(); Text("Checking…") } }
                        else { Label("Check with AI" + (plans.plan == "FREE" ? " (Premium)" : ""), systemImage: "sparkles") }
                    }
                    .disabled((selected.isEmpty && photoData == nil) || aiBusy)
                    if let aiMessage {
                        Text(aiMessage).font(.callout)
                        if plans.plan == "FREE" { Button("See plans") { showPlans = true } }
                    }
                }
                if let a = ai {
                    Section {
                        Text("✨ Reviewed by AI" + (a.raisedByAi ? ", which raised the urgency" : "")).font(.caption).foregroundStyle(.secondary)
                        Text(a.urgency.headline).font(.title3.bold()).foregroundStyle(urgencyColor(a.urgency.name))
                        ForEach(a.reasons, id: \.self) { Text("• \($0)") }
                        if !a.photoFindings.isEmpty {
                            Text("From the photo").font(.headline)
                            ForEach(a.photoFindings, id: \.self) { Text("• \($0)") }
                        }
                        if !a.watchFor.isEmpty {
                            Text("Watch for").font(.headline)
                            ForEach(a.watchFor, id: \.self) { Text("• \($0)") }
                        }
                        if let note = a.note { Text(note).font(.footnote) }
                        Text(a.disclaimer).font(.caption).foregroundStyle(.secondary)
                        if a.urgency.name == "EMERGENCY" {
                            Button("Find the nearest emergency vet") { openURL(URL(string: "maps://?q=emergency+vet")!) }
                                .buttonStyle(.borderedProminent).tint(.red)
                        }
                    }
                } else if let r = result {
                    Section {
                        Text(r.urgency.headline).font(.title3.bold()).foregroundStyle(urgencyColor(r.urgency.name))
                        ForEach(r.reasons, id: \.self) { Text("• \($0)") }
                        if !r.watchFor.isEmpty {
                            Text("Watch for").font(.headline)
                            ForEach(r.watchFor, id: \.self) { Text("• \($0)") }
                        }
                        Text(r.disclaimer).font(.caption).foregroundStyle(.secondary)
                        switch r.urgency.name {
                        case "EMERGENCY":
                            Button("Find the nearest emergency vet") { openURL(URL(string: "maps://?q=emergency+vet")!) }
                                .buttonStyle(.borderedProminent).tint(.red)
                        case "SEE_VET_24H", "BOOK_VISIT":
                            Button("Save and book a vet") { save(); bookVet = true }.buttonStyle(.borderedProminent)
                        default: EmptyView()
                        }
                    }
                }
            }
            .navigationTitle("What's going on with \(pet.name)?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save(); dismiss() }.disabled(selected.isEmpty) }
            }
            .sheet(isPresented: $bookVet, onDismiss: { dismiss() }) { VetView(pet: pet, reason: summary) }
            .sheet(isPresented: $showPlans) { PlansView() }
        }
    }

    private func chips(_ symptoms: [Symptom]) -> some View {
        FlowLayout {
            ForEach(symptoms, id: \.key) { s in
                Chip(label: s.label, selected: selected.contains(s.key)) {
                    if selected.contains(s.key) { selected.remove(s.key) } else { selected.insert(s.key) }
                    result = nil
                    ai = nil
                }
            }
        }
    }

    private func run() -> (SymptomEntry, TriageResult) {
        TriageRunner.assess(pet: pet, keys: Array(selected),
                            severity: Severity.entries.first { $0.name == severity } ?? Severity.mild,
                            hoursAgo: onsets[onset].1, notes: notes)
    }

    private var summary: String {
        let labels = selected.map { Symptom.companion.fromKey(key: $0).label }.joined(separator: ", ")
        return "\(labels) (\(severity.lowercased()), started \(onsets[onset].0.lowercased()))" + (notes.isEmpty ? "" : ". Notes: \(notes)")
    }

    private func runAi() async {
        let rule = run().1
        result = rule
        aiMessage = nil
        guard plans.limits.aiPhotoCheck else { aiMessage = "AI photo checks are part of Premium. Your rule-based result is below."; return }
        guard AiTriageClient.available else { aiMessage = "The AI check needs the CuteFamilyPet cloud, which isn't set up in this build yet. The rule-based result is below."; return }
        aiBusy = true
        defer { aiBusy = false }
        do {
            ai = try await AiTriageClient.check(
                pet: pet, symptomLabels: selected.map { Symptom.companion.fromKey(key: $0).label }, severity: severity,
                hoursSinceOnset: onsets[onset].1, notes: notes, rule: rule,
                photoBase64: photoData.flatMap { MediaFiles.jpegBase64ForAi($0) })
        } catch {
            aiMessage = error.localizedDescription
        }
    }

    private func save() {
        let (entry, rule) = run()
        // Keep the AI's (never lower) urgency in the history when there was one.
        let urgencyName = ai?.urgency.name ?? rule.urgency.name
        pet.symptoms.append(SymptomRecord(symptomKeys: Array(selected), severity: severity,
                                          startedAtMillis: core.epochMillis(instant: entry.startedAt),
                                          loggedAtMillis: core.epochMillis(instant: entry.loggedAt),
                                          notes: notes.isEmpty ? nil : notes, urgency: urgencyName))
    }
}
