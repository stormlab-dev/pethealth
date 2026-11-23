import SwiftUI
import SwiftData
import Charts
import PetPulseCore

struct PetDetailView: View {
    @Bindable var pet: PetRecord
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var tab = "Overview"
    @State private var editing = false
    @State private var checking = false
    @State private var confirmDelete = false
    private let tabs = ["Overview", "Symptoms", "Photos", "Weight", "Vaccines", "Meds", "Vet"]

    var body: some View {
        VStack(spacing: 0) {
            Picker("Section", selection: $tab) { ForEach(tabs, id: \.self) { Text($0) } }
                .pickerStyle(.segmented).padding(.horizontal)
            List {
                switch tab {
                case "Symptoms": SymptomsSection(pet: pet)
                case "Photos": MediaSection(pet: pet)
                case "Weight": WeightSection(pet: pet)
                case "Vaccines": VaccinesSection(pet: pet)
                case "Meds": MedsSection(pet: pet)
                case "Vet": VetSection(pet: pet)
                default: OverviewSection(pet: pet)
                }
            }
        }
        .navigationTitle("\(pet.emoji) \(pet.name)")
        .toolbar {
            Menu {
                Button("Edit", systemImage: "pencil") { editing = true }
                Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete = true }
            } label: { Image(systemName: "ellipsis.circle") }
        }
        .safeAreaInset(edge: .bottom) {
            if tab == "Overview" || tab == "Symptoms" {
                Button { checking = true } label: {
                    Label("Something's wrong", systemImage: "stethoscope").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).controlSize(.large).padding()
            }
        }
        .sheet(isPresented: $editing) { PetEditView(pet: pet) }
        .sheet(isPresented: $checking) { SymptomCheckView(pet: pet) }
        .confirmationDialog("Delete \(pet.name)?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                pet.medications.forEach { Reminders.shared.cancel($0) }
                context.delete(pet)
                try? context.save()
                dismiss()
            }
        } message: { Text("This removes all of \(pet.name)'s logs, medications and reminders.") }
    }
}

// MARK: - Overview

private struct OverviewSection: View {
    let pet: PetRecord
    var body: some View {
        let today = core.today()
        let upcoming = pet.medications.compactMap { m in m.nextDose().map { (m, $0) } }
            .sorted { $0.1.swiftDate < $1.1.swiftDate }.prefix(4)
        Section("Upcoming doses") {
            if upcoming.isEmpty { Text("No scheduled medications.").foregroundStyle(.secondary) }
            ForEach(Array(upcoming), id: \.0.uid) { item in Text("\(item.1.pretty) · \(item.0.name) \(item.0.dosage)") }
        }
        Section("Vaccines") {
            let due = VaccineTracker.shared.latestByName(all: pet.vaccines.map(\.model))
                .filter { ["OVERDUE", "DUE_SOON"].contains(VaccineTracker.shared.status(v: $0, today: today).name) }
            if pet.vaccines.isEmpty { Text("No vaccines logged yet.").foregroundStyle(.secondary) }
            else if due.isEmpty { Text("All up to date.") }
            ForEach(due, id: \.self) { VaccineRow(v: $0) }
        }
        Section("Weight") {
            if let t = WeightAnalyzer.shared.trend(entries: pet.weights.map(\.model), windowDays: 30) {
                Text(String(format: "%.1f kg (%+.1f kg, %+.0f%% over %d days)", t.latestKg, t.changeKg, t.changePercent, t.overDays))
            } else {
                Text("Log weight regularly to spot changes early.").foregroundStyle(.secondary)
            }
        }
        Section("Recent symptoms") {
            if let last = pet.symptoms.max(by: { $0.loggedAtMillis < $1.loggedAtMillis }) {
                Text(last.symptomKeys.map { Symptom.companion.fromKey(key: $0).label }.joined(separator: ", "))
                if let u = last.urgency { Pill(text: urgencyHeadline(u), color: urgencyColor(u)) }
            } else {
                Text("Nothing logged. That's good news!").foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - Symptoms

private struct SymptomsSection: View {
    let pet: PetRecord
    @Environment(\.modelContext) private var context
    var body: some View {
        let entries = pet.symptoms.sorted { $0.loggedAtMillis > $1.loggedAtMillis }
        Section("History") {
            if entries.isEmpty { Text("Log symptoms when something seems off. The history helps your vet.").foregroundStyle(.secondary) }
            ForEach(entries) { e in
                VStack(alignment: .leading, spacing: 4) {
                    Text(Date(epochMillis: e.loggedAtMillis).formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                    Text(e.symptomKeys.map { Symptom.companion.fromKey(key: $0).label }.joined(separator: ", "))
                    if let n = e.notes { Text(n).font(.caption) }
                    if let u = e.urgency { Pill(text: urgencyHeadline(u), color: urgencyColor(u)) }
                }
            }
            .onDelete { idx in idx.map { entries[$0] }.forEach { context.delete($0) } }
        }
    }
}

// MARK: - Weight

private struct WeightSection: View {
    let pet: PetRecord
    @Environment(\.modelContext) private var context
    @State private var kg = ""
    @State private var date = Date()

    var body: some View {
        let sorted = pet.weights.sorted { $0.epochDay < $1.epochDay }
        Section("Weight history") {
            if sorted.count >= 2 {
                Chart(sorted) { w in
                    LineMark(x: .value("Date", KDate.fromEpochDay(w.epochDay).swiftDate), y: .value("kg", w.kilograms))
                    PointMark(x: .value("Date", KDate.fromEpochDay(w.epochDay).swiftDate), y: .value("kg", w.kilograms))
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 160)
            }
            if let t = WeightAnalyzer.shared.trend(entries: sorted.map(\.model), windowDays: 30) {
                Text(String(format: "%+.1f kg (%+.0f%%) over %d days", t.changeKg, t.changePercent, t.overDays) + (abs(t.changePercent) >= 10 ? ". Worth mentioning to your vet." : ""))
                    .foregroundStyle(abs(t.changePercent) >= 10 ? .orange : .secondary)
            }
        }
        Section("Add") {
            TextField("Weight (kg)", text: $kg).keyboardType(.decimalPad)
            DatePicker("Date", selection: $date, in: ...Date(), displayedComponents: .date)
            Button("Save weight") {
                guard let v = Double(kg.replacingOccurrences(of: ",", with: ".")), v > 0 else { return }
                pet.weights.append(WeightRecord(kilograms: v, epochDay: KDate.from(date).epochDay))
                kg = ""
            }
        }
        Section {
            ForEach(sorted.reversed()) { w in
                HStack { Text(KDate.fromEpochDay(w.epochDay).pretty); Spacer(); Text(String(format: "%.2f kg", w.kilograms)) }
            }
            .onDelete { idx in let rev = Array(sorted.reversed()); idx.map { rev[$0] }.forEach { context.delete($0) } }
        }
    }
}

// MARK: - Vaccines

struct VaccineRow: View {
    let v: Vaccination
    var body: some View {
        let status = VaccineTracker.shared.status(v: v, today: core.today()).name
        let (label, color): (String, Color) = switch status {
        case "OVERDUE": ("Overdue", .orange)
        case "DUE_SOON": ("Due soon", .yellow)
        case "CURRENT": ("Current", .green)
        default: ("No booster", .gray)
        }
        HStack {
            VStack(alignment: .leading) {
                Text(v.name)
                Text("Given \(v.administeredOn.pretty)" + (v.dueOn.map { " · due \($0.pretty)" } ?? "")).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Pill(text: label, color: color)
        }
    }
}

private struct VaccinesSection: View {
    let pet: PetRecord
    @Environment(\.modelContext) private var context
    @State private var name = ""
    @State private var date = Date()
    @State private var months = "12"
    private let common: [(String, Int)] = [("Rabies", 36), ("DHPP", 12), ("Bordetella", 12), ("Leptospirosis", 12), ("FVRCP", 12), ("FeLV", 12)]

    var body: some View {
        Section("Current status") {
            let latest = VaccineTracker.shared.latestByName(all: pet.vaccines.map(\.model))
            if latest.isEmpty { Text("Add vaccines from your records, or import them from your clinic on the Vet tab.").foregroundStyle(.secondary) }
            ForEach(latest, id: \.self) { VaccineRow(v: $0) }
        }
        Section("Add vaccine") {
            FlowLayout { ForEach(common, id: \.0) { item in Chip(label: item.0, selected: name == item.0) { name = item.0; months = String(item.1) } } }
            TextField("Vaccine", text: $name)
            DatePicker("Date given", selection: $date, in: ...Date(), displayedComponents: .date)
            TextField("Booster every (months, blank = none)", text: $months).keyboardType(.numberPad)
            Button("Save vaccine") {
                guard !name.isEmpty else { return }
                pet.vaccines.append(VaccineRecord(name: name, administeredEpochDay: KDate.from(date).epochDay, validForMonths: Int(months)))
                name = ""
            }
        }
        Section("All doses") {
            let all = pet.vaccines.sorted { $0.administeredEpochDay > $1.administeredEpochDay }
            ForEach(all) { v in
                VStack(alignment: .leading) {
                    Text(v.name)
                    Text([KDate.fromEpochDay(v.administeredEpochDay).pretty, v.clinicName, v.sourceRecordId == nil ? nil : "imported"].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .onDelete { idx in idx.map { all[$0] }.forEach { context.delete($0) } }
        }
    }
}

// MARK: - Meds

private struct MedsSection: View {
    let pet: PetRecord
    @State private var editing: MedicationRecord?
    @State private var adding = false
    @State private var pharmacyFor: MedicationRecord?

    var body: some View {
        let today = core.today()
        Section {
            if pet.medications.isEmpty { Text("Add medications to get dose reminders and refill alerts.").foregroundStyle(.secondary) }
            ForEach(pet.medications.sorted { $0.name < $1.name }) { m in
                VStack(alignment: .leading, spacing: 6) {
                    HStack { Text(m.name).font(.headline); Spacer(); Button("Edit") { editing = m }.buttonStyle(.borderless) }
                    Text(m.dosage)
                    Text(m.scheduleLabel).font(.caption).foregroundStyle(.secondary)
                    if let next = m.nextDose() { Text("Next: \(next.pretty)" + (m.remindersEnabled ? "" : " (reminders off)")) }
                    if let f = RefillPredictor.shared.forecast(med: m.model, today: today, leadDays: 7) {
                        Text("About \(f.daysLeft) days left · runs out \(f.runsOutOn.pretty)")
                            .foregroundStyle(f.shouldReorder ? .orange : .secondary).font(.caption)
                    }
                    HStack {
                        Button("Mark given") { m.recordDose(taken: true) }.buttonStyle(.bordered)
                        Button(m.pharmacyPrescriptionId == nil ? "Link pharmacy" : "Refill") { pharmacyFor = m }.buttonStyle(.bordered)
                    }
                }
            }
        }
        Section { Button("Add medication", systemImage: "plus") { adding = true } }
            .sheet(isPresented: $adding) { MedicationEditView(pet: pet, med: nil) }
            .sheet(item: $editing) { MedicationEditView(pet: pet, med: $0) }
            .sheet(item: $pharmacyFor) { PharmacyView(med: $0) }
    }
}
