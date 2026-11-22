import SwiftUI
import SwiftData
import PetPulseCore

/// Vet tab inside pet detail: linked clinic + appointments, with a button into the full flow.
struct VetSection: View {
    let pet: PetRecord
    @State private var open = false
    var body: some View {
        Section("Your clinic") {
            if let l = pet.clinicLink { Text(l.clinicName); Text("Patient id \(l.patientId)").font(.caption).foregroundStyle(.secondary) }
            else { Text("Link your vet clinic to import records and book online.").foregroundStyle(.secondary) }
            Button(pet.clinicLink == nil ? "Find and link a clinic" : "Records and booking") { open = true }
        }
        Section("Appointments") {
            if pet.appointments.isEmpty { Text("None yet.").foregroundStyle(.secondary) }
            ForEach(pet.appointments.sorted { $0.startMillis > $1.startMillis }) { a in
                VStack(alignment: .leading) {
                    Text("\(Date(epochMillis: a.startMillis).formatted(date: .abbreviated, time: .shortened)) · \(a.clinicName)")
                    Text("\(a.reason) · \(a.status.lowercased())").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .sheet(isPresented: $open) { VetView(pet: pet, reason: "") }
    }
}

@MainActor
struct VetView: View {
    let pet: PetRecord
    @State var reason: String
    @Environment(\.dismiss) private var dismiss
    @State private var clinics: [Clinic] = []
    @State private var emergency: [Clinic] = []
    @State private var query = ""
    @State private var linking: Clinic?
    @State private var phone = ""
    @State private var code = ""
    @State private var booking: Clinic?
    @State private var slots: [AppointmentSlot] = []
    @State private var shareSummary = true
    @State private var record: MedicalRecord?
    @State private var importPlan: ImportPlan?
    @State private var busy = false
    @State private var message: String?
    private let provider = partners.clinics.first!

    var body: some View {
        NavigationStack {
            List {
                if let clinic = booking { bookingSections(clinic) } else { mainSections }
            }
            .overlay { if busy { ProgressView() } }
            .navigationTitle(booking.map { "Book at \($0.name)" } ?? "Vet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(booking == nil ? "Done" : "Back") { if booking != nil { booking = nil } else { dismiss() } }
                }
            }
            .task {
                run {
                    clinics = try await provider.searchClinics(query: ClinicQuery(latitude: nil, longitude: nil, text: nil, emergencyOnly: false))
                    emergency = try await provider.searchClinics(query: ClinicQuery(latitude: nil, longitude: nil, text: nil, emergencyOnly: true))
                }
            }
            .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) { Button("OK") {} }
            .alert("Link \(linking?.name ?? "")", isPresented: Binding(get: { linking != nil }, set: { if !$0 { linking = nil } })) {
                TextField("Phone", text: $phone).keyboardType(.phonePad)
                TextField("Code", text: $code).keyboardType(.numberPad)
                Button("Link") { if let c = linking { link(c) } }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Enter the phone number on your account. The clinic will text you a code. (Demo code: \(MockVetClinicProvider.companion.DEMO_CODE))")
            }
            .confirmationDialog("Import from \(pet.clinicLink?.clinicName ?? "clinic")?", isPresented: Binding(get: { importPlan != nil }, set: { if !$0 { importPlan = nil } }), titleVisibility: .visible) {
                Button("Import") { applyImport() }
            } message: {
                if let p = importPlan {
                    Text((p.newVaccinations.map { "\($0.name), \($0.administeredOn.pretty)" } + p.newWeights.map { String(format: "Weight %.1f kg, %@", $0.kilograms, $0.date.pretty) }).joined(separator: "\n"))
                }
            }
        }
    }

    @ViewBuilder private var mainSections: some View {
        Section {
            if let l = pet.clinicLink {
                Text(l.clinicName)
                if let s = l.lastSyncedMillis { Text("Records last imported \(Date(epochMillis: s).formatted(date: .abbreviated, time: .omitted))").font(.caption) }
                Button("Import records") { fetchRecords(l) }
                if let c = clinics.first(where: { $0.id == l.clinicId }) { Button("Book online") { startBooking(c) } }
                Button("Unlink", role: .destructive) { pet.clinicLink = nil }
            } else {
                Text("Pick your clinic below and verify with the phone number they have on file.").foregroundStyle(.secondary)
            }
        } header: {
            HStack { Text("Your clinic"); if provider.info.isDemo { Pill(text: "Demo", color: .yellow) } }
        }
        if let r = record {
            Section("Visit history") {
                ForEach(r.visits, id: \.recordId) { v in
                    VStack(alignment: .leading) {
                        Text("\(v.date.pretty) · \(v.reason)").font(.headline)
                        Text("\(v.summary) (\(v.veterinarian))").font(.caption)
                    }
                }
            }
        }
        Section("Emergency clinics (24/7)") {
            ForEach(emergency, id: \.id) { c in
                HStack {
                    VStack(alignment: .leading) { Text(c.name); Text(c.address).font(.caption) }
                    Spacer()
                    if let url = URL(string: "tel:\(c.phone.filter { $0.isNumber || $0 == "+" })") { Link(destination: url) { Image(systemName: "phone.fill") } }
                }
            }
        }
        Section("Clinics") {
            HStack {
                TextField("Search clinics", text: $query)
                Button("Search") { run { clinics = try await provider.searchClinics(query: ClinicQuery(latitude: nil, longitude: nil, text: query, emergencyOnly: false)) } }
            }
            ForEach(clinics.filter { !$0.isEmergency24h }, id: \.id) { c in
                VStack(alignment: .leading, spacing: 6) {
                    Text(c.name).font(.headline)
                    Text("\(c.address) · \(c.phone)").font(.caption)
                    HStack {
                        if pet.clinicLink?.clinicId != c.id { Button("This is my vet") { linking = c }.buttonStyle(.bordered) }
                        if c.acceptsOnlineBooking { Button("Book online") { startBooking(c) }.buttonStyle(.borderedProminent) }
                    }
                }
            }
        }
    }

    @ViewBuilder private func bookingSections(_ clinic: Clinic) -> some View {
        Section {
            TextField("Reason for visit", text: $reason, axis: .vertical)
            Toggle("Share this with the clinic ahead of the visit", isOn: $shareSummary)
        }
        if slots.isEmpty && !busy { Text("No online slots this week. Call \(clinic.phone).") }
        let days = Dictionary(grouping: slots) { $0.start.date.epochDay }
        ForEach(days.keys.sorted(), id: \.self) { day in
            Section(KDate.fromEpochDay(day).pretty) {
                FlowLayout {
                    ForEach(days[day] ?? [], id: \.self) { s in
                        Chip(label: s.start.time.pretty, selected: false) { book(s, at: clinic) }
                    }
                }
            }
        }
    }

    private func link(_ clinic: Clinic) {
        run {
            let l = try await provider.linkPatient(clinicId: clinic.id, ownerPhone: phone, petName: pet.name, verificationCode: code)
            pet.clinicLink = ClinicLinkRecord(providerId: provider.info.id, clinicId: clinic.id, clinicName: clinic.name, patientId: l.patientId)
            message = "Linked to \(clinic.name)"
        }
    }

    private func fetchRecords(_ l: ClinicLinkRecord) {
        run {
            let r = try await provider.records(link: PatientLink(clinicId: l.clinicId, patientId: l.patientId, petName: pet.name))
            record = r
            let plan = RecordImporter.shared.plan(petId: pet.uid, clinicName: l.clinicName, record: r,
                                                  existingVaccinations: pet.vaccines.map(\.model), existingWeights: pet.weights.map(\.model))
            if plan.isEmpty { message = "Everything is already up to date." } else { importPlan = plan }
        }
    }

    private func applyImport() {
        guard let plan = importPlan else { return }
        for v in plan.newVaccinations {
            pet.vaccines.append(VaccineRecord(name: v.name, administeredEpochDay: v.administeredOn.epochDay,
                                              validForMonths: v.validForMonths.map { Int(truncating: $0) },
                                              clinicName: v.clinicName, sourceRecordId: v.sourceRecordId))
        }
        for w in plan.newWeights { pet.weights.append(WeightRecord(kilograms: w.kilograms, epochDay: w.date.epochDay)) }
        pet.clinicLink?.lastSyncedMillis = Date().epochMillis
        message = "Imported \(plan.newVaccinations.count) vaccines and \(plan.newWeights.count) weights."
        importPlan = nil
    }

    private func startBooking(_ clinic: Clinic) {
        booking = clinic
        run { slots = try await provider.availableSlots(clinicId: clinic.id, from: core.today(), days: 7) }
    }

    private func book(_ slot: AppointmentSlot, at clinic: Clinic) {
        run {
            let link = pet.clinicLink.flatMap { $0.clinicId == clinic.id ? PatientLink(clinicId: $0.clinicId, patientId: $0.patientId, petName: pet.name) : nil }
            let a = try await provider.book(slot: slot, link: link, petName: pet.name, reason: reason.isEmpty ? "General visit" : reason,
                                            triageSummary: shareSummary && !reason.isEmpty ? reason : nil)
            pet.appointments.append(AppointmentRecord(externalId: a.id, clinicName: clinic.name, startMillis: a.start.swiftDate.epochMillis,
                                                      minutes: Int(a.minutes), reason: a.reason, status: a.status.name))
            message = a.status.name == "CONFIRMED" ? "Booked! See you \(a.start.pretty)." : "Request sent. \(clinic.name) will confirm."
            booking = nil
        }
    }

    private func run(_ work: @escaping () async throws -> Void) {
        Task {
            busy = true
            defer { busy = false }
            do { try await work() } catch { message = error.localizedDescription }
        }
    }
}
