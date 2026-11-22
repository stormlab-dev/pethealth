import SwiftUI
import SwiftData
import PetPulseCore

struct MedicationEditView: View {
    let pet: PetRecord
    let med: MedicationRecord?
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var dosage = ""
    @State private var kind = "DAILY"
    @State private var times: [Date] = [Calendar.current.date(bySettingHour: 8, minute: 0, second: 0, of: Date())!]
    @State private var intervalDays = 30
    @State private var start = Date()
    @State private var hasEnd = false
    @State private var end = Date()
    @State private var onHand = ""
    @State private var perDose = "1"
    @State private var reminders = true

    var body: some View {
        NavigationStack {
            Form {
                TextField("Medication", text: $name)
                TextField("Dose, e.g. 1 tablet with food", text: $dosage)
                Section("Schedule") {
                    Picker("Schedule", selection: $kind) {
                        Text("Daily").tag("DAILY"); Text("Every N days").tag("EVERY_N_DAYS"); Text("As needed").tag("AS_NEEDED")
                    }.pickerStyle(.segmented)
                    if kind == "EVERY_N_DAYS" { Stepper("Every \(intervalDays) days", value: $intervalDays, in: 1...365) }
                    if kind != "AS_NEEDED" {
                        ForEach(times.indices, id: \.self) { i in
                            DatePicker(kind == "DAILY" ? "Dose \(i + 1)" : "Time", selection: $times[i], displayedComponents: .hourAndMinute)
                        }
                        .onDelete { times.remove(atOffsets: $0) }
                        if kind == "DAILY" {
                            Button("Add time") { times.append(Calendar.current.date(bySettingHour: 20, minute: 0, second: 0, of: Date())!) }
                        }
                    }
                    DatePicker("Start", selection: $start, displayedComponents: .date)
                    Toggle("Has an end date", isOn: $hasEnd)
                    if hasEnd { DatePicker("End", selection: $end, in: start..., displayedComponents: .date) }
                }
                Section("Refill tracking (optional)") {
                    TextField("Units on hand", text: $onHand).keyboardType(.decimalPad)
                    TextField("Units per dose", text: $perDose).keyboardType(.decimalPad)
                }
                Toggle("Dose reminders", isOn: $reminders)
                if med != nil {
                    Button("Delete medication", role: .destructive) {
                        if let m = med { Reminders.shared.cancel(m); context.delete(m) }
                        dismiss()
                    }
                }
            }
            .navigationTitle(med == nil ? "Add medication" : "Edit medication")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save).disabled(name.isEmpty || (kind != "AS_NEEDED" && times.isEmpty)) }
            }
            .onAppear(perform: load)
        }
    }

    private func hhmm(_ d: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: d)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    private func load() {
        guard let m = med else { return }
        name = m.name; dosage = m.dosage; kind = m.scheduleType; intervalDays = max(1, m.intervalDays)
        times = m.times.map { t in let k = KTime.parse(t); return Calendar.current.date(bySettingHour: Int(k.hour), minute: Int(k.minute), second: 0, of: Date())! }
        start = KDate.fromEpochDay(m.startEpochDay).swiftDate
        if let e = m.endEpochDay { hasEnd = true; end = KDate.fromEpochDay(e).swiftDate }
        onHand = m.unitsRemaining.map { String($0) } ?? ""
        perDose = String(m.unitsPerDose)
        reminders = m.remindersEnabled
    }

    private func save() {
        let m = med ?? MedicationRecord(name: name, dosage: dosage, scheduleType: kind, times: [], intervalDays: 1, startEpochDay: 0)
        m.name = name; m.dosage = dosage; m.scheduleType = kind
        let sortedTimes = Array(Set(times.map(hhmm))).sorted()
        m.times = kind == "AS_NEEDED" ? [] : (kind == "DAILY" ? sortedTimes : Array(sortedTimes.prefix(1)))
        m.intervalDays = kind == "EVERY_N_DAYS" ? intervalDays : 1
        m.startEpochDay = KDate.from(start).epochDay
        m.endEpochDay = hasEnd ? KDate.from(end).epochDay : nil
        m.unitsRemaining = Double(onHand.replacingOccurrences(of: ",", with: "."))
        m.unitsPerDose = Double(perDose.replacingOccurrences(of: ",", with: ".")).flatMap { $0 > 0 ? $0 : nil } ?? 1
        m.remindersEnabled = reminders
        if med == nil { pet.medications.append(m) }
        try? context.save()
        Reminders.shared.schedule(m)
        dismiss()
    }
}
