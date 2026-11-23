import SwiftUI
import SwiftData
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
                    Button("How urgent is this?") { result = run().1 }.disabled(selected.isEmpty)
                }
                if let r = result {
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
        }
    }

    private func chips(_ symptoms: [Symptom]) -> some View {
        FlowLayout {
            ForEach(symptoms, id: \.key) { s in
                Chip(label: s.label, selected: selected.contains(s.key)) {
                    if selected.contains(s.key) { selected.remove(s.key) } else { selected.insert(s.key) }
                    result = nil
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

    private func save() {
        let (entry, r) = run()
        pet.symptoms.append(SymptomRecord(symptomKeys: Array(selected), severity: severity,
                                          startedAtMillis: core.epochMillis(instant: entry.startedAt),
                                          loggedAtMillis: core.epochMillis(instant: entry.loggedAt),
                                          notes: notes.isEmpty ? nil : notes, urgency: r.urgency.name))
    }
}
