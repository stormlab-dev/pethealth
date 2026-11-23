import Foundation
import PetPulseCore

// Converts SwiftData records into the shared Kotlin models, so triage, dose schedules,
// refill forecasts and vaccine status all run the exact same code as on Android.

typealias KDate = PetPulseCore.LocalDate
typealias KTime = PetPulseCore.LocalTime
typealias KDateTime = PetPulseCore.LocalDateTime

let core = Dates.shared

// MARK: - Swift <-> Kotlin dates

extension KDate {
    var swiftDate: Date {
        Calendar.current.date(from: DateComponents(year: Int(year), month: Int(monthNumber), day: Int(dayOfMonth))) ?? Date()
    }
    var pretty: String { swiftDate.formatted(date: .abbreviated, time: .omitted) }
    var epochDay: Int { Int(core.epochDays(date: self)) }
    static func from(_ d: Date) -> KDate {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: d)
        return core.date(year: Int32(c.year!), month: Int32(c.month!), day: Int32(c.day!))
    }
    static func fromEpochDay(_ day: Int) -> KDate { core.fromEpochDays(days: Int32(day)) }
}

extension KTime {
    var hhmm: String { String(format: "%02d:%02d", hour, minute) }
    var pretty: String {
        let d = Calendar.current.date(from: DateComponents(hour: Int(hour), minute: Int(minute))) ?? Date()
        return d.formatted(date: .omitted, time: .shortened)
    }
    static func parse(_ hhmm: String) -> KTime {
        let parts = hhmm.split(separator: ":").compactMap { Int32($0) }
        return core.time(hour: parts.first ?? 9, minute: parts.count > 1 ? parts[1] : 0)
    }
}

extension KDateTime {
    var swiftDate: Date { Date(timeIntervalSince1970: TimeInterval(core.epochMillis(dateTime: self)) / 1000) }
    var pretty: String { swiftDate.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).hour().minute()) }
}

extension Date {
    var epochMillis: Int64 { Int64(timeIntervalSince1970 * 1000) }
    init(epochMillis: Int64) { self.init(timeIntervalSince1970: TimeInterval(epochMillis) / 1000) }
}

// MARK: - Records -> Kotlin models

extension PetRecord {
    var model: Pet {
        Pet(id: uid, name: name,
            species: Species.entries.first { $0.name == species } ?? Species.other,
            breed: breed,
            sex: Sex.entries.first { $0.name == sex } ?? Sex.unknown,
            neutered: neutered,
            birthDate: birthEpochDay.map { KDate.fromEpochDay($0) },
            microchipId: microchipId, notes: notes, linkedClinicId: clinicLink?.clinicId)
    }
    var speciesModel: Species { Species.entries.first { $0.name == species } ?? Species.other }
    var emoji: String {
        ["DOG": "🐕", "CAT": "🐈", "RABBIT": "🐇", "BIRD": "🦜", "REPTILE": "🦎"][species] ?? "🐾"
    }
    var birthDate: KDate? { birthEpochDay.map { KDate.fromEpochDay($0) } }
}

extension SymptomRecord {
    var model: SymptomEntry {
        SymptomEntry(id: uid, petId: pet?.uid ?? 0,
                     symptoms: Set(symptomKeys.map { Symptom.companion.fromKey(key: $0) }),
                     severity: Severity.entries.first { $0.name == severity } ?? Severity.mild,
                     startedAt: core.instant(epochMillis: startedAtMillis),
                     loggedAt: core.instant(epochMillis: loggedAtMillis),
                     notes: notes)
    }
}

extension WeightRecord {
    var model: WeightEntry { WeightEntry(id: uid, petId: pet?.uid ?? 0, kilograms: kilograms, date: KDate.fromEpochDay(epochDay)) }
}

extension VaccineRecord {
    var model: Vaccination {
        Vaccination(id: uid, petId: pet?.uid ?? 0, name: name, administeredOn: KDate.fromEpochDay(administeredEpochDay),
                    validForMonths: validForMonths.map { KotlinInt(int: Int32($0)) },
                    clinicName: clinicName, sourceRecordId: sourceRecordId)
    }
}

extension MedicationRecord {
    var schedule: DoseSchedule {
        switch scheduleType {
        case "DAILY": return DoseScheduleDaily(times: times.map { KTime.parse($0) })
        case "EVERY_N_DAYS":
            return DoseScheduleEveryNDays(intervalDays: Int32(intervalDays), time: KTime.parse(times.first ?? "09:00"),
                                          startDate: KDate.fromEpochDay(startEpochDay))
        default: return DoseScheduleAsNeeded.shared
        }
    }

    var model: Medication {
        Medication(id: uid, petId: pet?.uid ?? 0, name: name, dosage: dosage, schedule: schedule,
                   startDate: KDate.fromEpochDay(startEpochDay), endDate: endEpochDay.map { KDate.fromEpochDay($0) },
                   unitsRemaining: unitsRemaining.map { KotlinDouble(double: $0) }, unitsPerDose: unitsPerDose,
                   remindersEnabled: remindersEnabled,
                   pharmacyPrescriptionId: pharmacyPrescriptionId, pharmacyProviderId: pharmacyProviderId)
    }

    var scheduleLabel: String {
        switch scheduleType {
        case "DAILY": return times.count == 1 ? "Once a day at \(KTime.parse(times[0]).pretty)" : "\(times.count)× a day: " + times.map { KTime.parse($0).pretty }.joined(separator: ", ")
        case "EVERY_N_DAYS": return intervalDays == 30 ? "Monthly at \(KTime.parse(times.first ?? "09:00").pretty)" : "Every \(intervalDays) days"
        default: return "As needed"
        }
    }

    func nextDose(after: KDateTime = core.nowLocal()) -> KDateTime? {
        DoseScheduler.shared.nextDose(med: model, after: after)
    }

    func recordDose(taken: Bool, scheduledFor: Date = Date()) {
        doses.append(DoseRecord(scheduledForMillis: scheduledFor.epochMillis, takenAtMillis: taken ? Date().epochMillis : nil, skipped: !taken))
        if taken, let left = unitsRemaining { unitsRemaining = max(0, left - unitsPerDose) }
    }
}

// MARK: - Triage

enum TriageRunner {
    static func assess(pet: PetRecord, keys: [String], severity: Severity, hoursAgo: Int, notes: String) -> (SymptomEntry, TriageResult) {
        let entry = SymptomEntry(id: 0, petId: pet.uid,
                                 symptoms: Set(keys.map { Symptom.companion.fromKey(key: $0) }),
                                 severity: severity, startedAt: core.hoursAgo(hours: Int32(hoursAgo)), loggedAt: core.now(),
                                 notes: notes.isEmpty ? nil : notes)
        let twoWeeksAgo = Date().addingTimeInterval(-14 * 86_400).epochMillis
        let recent = pet.symptoms.filter { $0.loggedAtMillis >= twoWeeksAgo }.sorted { $0.loggedAtMillis > $1.loggedAtMillis }.map(\.model)
        let trend = WeightAnalyzer.shared.trend(entries: pet.weights.map(\.model), windowDays: 30)
        let context = TriageContext(
            pet: pet.model,
            ageYears: PetAge.shared.years(birthDate: pet.birthDate, today: core.today()),
            recentEntries: recent,
            recentWeightChangePercent: (trend != nil && trend!.overDays <= 45) ? KotlinDouble(double: trend!.changePercent) : nil)
        return (entry, RuleBasedTriageEngine().assessNow(entry: entry, context: context))
    }
}

/// Partners shared with Android (demo implementations for now).
let partners = Partners.shared.demo()
