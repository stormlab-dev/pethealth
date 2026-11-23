import Foundation
import SwiftData

// On-device storage (SwiftData). Field layout mirrors the Android Room entities and the
// Supabase schema so cloud sync can map rows one to one later.
// Dates are stored as epoch days, instants as epoch milliseconds.

@Model final class PetRecord {
    var uid: Int64 = Int64.random(in: 1...Int64.max)
    var name: String
    var species: String
    var breed: String?
    var sex: String
    var neutered: Bool
    var birthEpochDay: Int?
    var microchipId: String?
    var notes: String?
    @Relationship(deleteRule: .cascade, inverse: \SymptomRecord.pet) var symptoms: [SymptomRecord] = []
    @Relationship(deleteRule: .cascade, inverse: \WeightRecord.pet) var weights: [WeightRecord] = []
    @Relationship(deleteRule: .cascade, inverse: \VaccineRecord.pet) var vaccines: [VaccineRecord] = []
    @Relationship(deleteRule: .cascade, inverse: \MedicationRecord.pet) var medications: [MedicationRecord] = []
    @Relationship(deleteRule: .cascade, inverse: \AppointmentRecord.pet) var appointments: [AppointmentRecord] = []
    @Relationship(deleteRule: .cascade, inverse: \ClinicLinkRecord.pet) var clinicLink: ClinicLinkRecord?

    init(name: String, species: String = "DOG", sex: String = "UNKNOWN") {
        self.name = name
        self.species = species
        self.sex = sex
        self.neutered = false
    }
}

@Model final class SymptomRecord {
    var uid: Int64 = Int64.random(in: 1...Int64.max)
    var pet: PetRecord?
    var symptomKeys: [String]
    var severity: String
    var startedAtMillis: Int64
    var loggedAtMillis: Int64
    var notes: String?
    var urgency: String?

    init(symptomKeys: [String], severity: String, startedAtMillis: Int64, loggedAtMillis: Int64, notes: String?, urgency: String?) {
        self.symptomKeys = symptomKeys
        self.severity = severity
        self.startedAtMillis = startedAtMillis
        self.loggedAtMillis = loggedAtMillis
        self.notes = notes
        self.urgency = urgency
    }
}

@Model final class WeightRecord {
    var uid: Int64 = Int64.random(in: 1...Int64.max)
    var pet: PetRecord?
    var kilograms: Double
    var epochDay: Int
    init(kilograms: Double, epochDay: Int) { self.kilograms = kilograms; self.epochDay = epochDay }
}

@Model final class VaccineRecord {
    var uid: Int64 = Int64.random(in: 1...Int64.max)
    var pet: PetRecord?
    var name: String
    var administeredEpochDay: Int
    var validForMonths: Int?
    var clinicName: String?
    var sourceRecordId: String?
    init(name: String, administeredEpochDay: Int, validForMonths: Int?, clinicName: String? = nil, sourceRecordId: String? = nil) {
        self.name = name
        self.administeredEpochDay = administeredEpochDay
        self.validForMonths = validForMonths
        self.clinicName = clinicName
        self.sourceRecordId = sourceRecordId
    }
}

@Model final class MedicationRecord {
    var uid: Int64 = Int64.random(in: 1...Int64.max)
    var pet: PetRecord?
    var name: String
    var dosage: String
    /// DAILY, EVERY_N_DAYS or AS_NEEDED
    var scheduleType: String
    /// "HH:mm" strings
    var times: [String]
    var intervalDays: Int
    var startEpochDay: Int
    var endEpochDay: Int?
    var unitsRemaining: Double?
    var unitsPerDose: Double
    var remindersEnabled: Bool
    var pharmacyPrescriptionId: String?
    var pharmacyProviderId: String?
    @Relationship(deleteRule: .cascade, inverse: \DoseRecord.medication) var doses: [DoseRecord] = []

    init(name: String, dosage: String, scheduleType: String, times: [String], intervalDays: Int, startEpochDay: Int) {
        self.name = name
        self.dosage = dosage
        self.scheduleType = scheduleType
        self.times = times
        self.intervalDays = intervalDays
        self.startEpochDay = startEpochDay
        self.unitsPerDose = 1
        self.remindersEnabled = true
    }
}

@Model final class DoseRecord {
    var medication: MedicationRecord?
    var scheduledForMillis: Int64
    var takenAtMillis: Int64?
    var skipped: Bool
    init(scheduledForMillis: Int64, takenAtMillis: Int64?, skipped: Bool) {
        self.scheduledForMillis = scheduledForMillis
        self.takenAtMillis = takenAtMillis
        self.skipped = skipped
    }
}

@Model final class ClinicLinkRecord {
    var pet: PetRecord?
    var providerId: String
    var clinicId: String
    var clinicName: String
    var patientId: String
    var lastSyncedMillis: Int64?
    init(providerId: String, clinicId: String, clinicName: String, patientId: String) {
        self.providerId = providerId
        self.clinicId = clinicId
        self.clinicName = clinicName
        self.patientId = patientId
    }
}

@Model final class AppointmentRecord {
    var pet: PetRecord?
    var externalId: String
    var clinicName: String
    var startMillis: Int64
    var minutes: Int
    var reason: String
    var status: String
    init(externalId: String, clinicName: String, startMillis: Int64, minutes: Int, reason: String, status: String) {
        self.externalId = externalId
        self.clinicName = clinicName
        self.startMillis = startMillis
        self.minutes = minutes
        self.reason = reason
        self.status = status
    }
}
