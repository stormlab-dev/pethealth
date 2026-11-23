import SwiftUI
import SwiftData
import PetPulseCore

struct PetListView: View {
    @Query(sort: \PetRecord.name) private var pets: [PetRecord]
    @State private var adding = false
    @State private var showPlans = false

    var body: some View {
        NavigationStack {
            Group {
                if pets.isEmpty {
                    ContentUnavailableView("No pets yet", systemImage: "pawprint",
                                           description: Text("Add your first pet to start tracking symptoms, weight, vaccines and medications."))
                } else {
                    List(pets) { pet in
                        NavigationLink(value: pet) {
                            HStack(spacing: 14) {
                                Text(pet.emoji).font(.largeTitle)
                                VStack(alignment: .leading) {
                                    Text(pet.name).font(.headline)
                                    Text([pet.breed ?? pet.speciesModel.label, PetAge.shared.label(birthDate: pet.birthDate, today: core.today())]
                                        .compactMap { $0 }.joined(separator: " · "))
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("My pets")
            .navigationDestination(for: PetRecord.self) { PetDetailView(pet: $0) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Plans", systemImage: "star") { showPlans = true } }
                ToolbarItem(placement: .primaryAction) { Button("Add pet", systemImage: "plus") { adding = true } }
            }
            .sheet(isPresented: $adding) { PetEditView(pet: nil) }
            .sheet(isPresented: $showPlans) { PlansView() }
        }
    }
}

struct PetEditView: View {
    let pet: PetRecord?
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var species = "DOG"
    @State private var sex = "UNKNOWN"
    @State private var breed = ""
    @State private var neutered = false
    @State private var hasBirthday = false
    @State private var birthday = Date()
    @State private var microchip = ""
    @State private var notes = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $name)
                Picker("Species", selection: $species) {
                    ForEach(Species.entries, id: \.name) { Text($0.label).tag($0.name) }
                }
                Picker("Sex", selection: $sex) {
                    ForEach(Sex.entries, id: \.name) { Text($0.name.capitalized).tag($0.name) }
                }
                TextField("Breed (optional)", text: $breed)
                Toggle("Spayed / neutered", isOn: $neutered)
                Toggle("Birthday known", isOn: $hasBirthday)
                if hasBirthday { DatePicker("Birthday", selection: $birthday, in: ...Date(), displayedComponents: .date) }
                TextField("Microchip number", text: $microchip)
                TextField("Notes: allergies, conditions, diet", text: $notes, axis: .vertical).lineLimit(3...6)
            }
            .navigationTitle(pet == nil ? "Add pet" : "Edit \(pet!.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty) }
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        guard let p = pet else { return }
        name = p.name; species = p.species; sex = p.sex; breed = p.breed ?? ""; neutered = p.neutered
        microchip = p.microchipId ?? ""; notes = p.notes ?? ""
        if let b = p.birthDate { hasBirthday = true; birthday = b.swiftDate }
    }

    private func save() {
        let p = pet ?? PetRecord(name: name)
        p.name = name.trimmingCharacters(in: .whitespaces)
        p.species = species; p.sex = sex; p.neutered = neutered
        p.breed = breed.isEmpty ? nil : breed
        p.microchipId = microchip.isEmpty ? nil : microchip
        p.notes = notes.isEmpty ? nil : notes
        p.birthEpochDay = hasBirthday ? KDate.from(birthday).epochDay : nil
        if pet == nil { context.insert(p) }
        try? context.save()
        dismiss()
    }
}
