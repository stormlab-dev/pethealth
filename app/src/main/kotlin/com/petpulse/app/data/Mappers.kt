package com.petpulse.app.data

import com.petpulse.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

fun PetEntity.toModel() = Pet(
    id = id, name = name, species = Species.valueOf(species), breed = breed, sex = Sex.valueOf(sex),
    neutered = neutered, birthDate = birthEpochDay?.let(LocalDate::ofEpochDay), microchipId = microchipId, notes = notes,
)

fun Pet.toEntity() = PetEntity(
    id = id, name = name.trim(), species = species.name, breed = breed?.trim()?.ifEmpty { null }, sex = sex.name,
    neutered = neutered, birthEpochDay = birthDate?.toEpochDay(), microchipId = microchipId?.ifBlank { null }, notes = notes?.ifBlank { null },
)

fun SymptomEntity.toModel() = SymptomEntry(
    id = id, petId = petId,
    symptoms = symptomKeys.split(',').filter { it.isNotBlank() }.map(Symptom::fromKey).toSet(),
    severity = Severity.valueOf(severity),
    startedAt = Instant.ofEpochMilli(startedAtMillis), loggedAt = Instant.ofEpochMilli(loggedAtMillis), notes = notes,
)

fun SymptomEntry.toEntity(urgency: String?) = SymptomEntity(
    id = id, petId = petId, symptomKeys = symptoms.joinToString(",") { it.key }, severity = severity.name,
    startedAtMillis = startedAt.toEpochMilli(), loggedAtMillis = loggedAt.toEpochMilli(), notes = notes?.ifBlank { null }, urgency = urgency,
)

fun WeightEntity.toModel() = WeightEntry(id, petId, kilograms, LocalDate.ofEpochDay(epochDay))
fun WeightEntry.toEntity() = WeightEntity(id, petId, kilograms, date.toEpochDay())

fun VaccinationEntity.toModel() = Vaccination(id, petId, name, LocalDate.ofEpochDay(administeredEpochDay), validForMonths, clinicName, sourceRecordId)
fun Vaccination.toEntity() = VaccinationEntity(id, petId, name.trim(), administeredOn.toEpochDay(), validForMonths, clinicName, sourceRecordId)

fun MedicationEntity.toModel(): Medication {
    val parsedTimes = times.split(',').filter { it.isNotBlank() }.map { LocalTime.parse(it.trim()) }
    val schedule = when (scheduleType) {
        "DAILY" -> DoseSchedule.Daily(parsedTimes)
        "EVERY_N_DAYS" -> DoseSchedule.EveryNDays(intervalDays, parsedTimes.firstOrNull() ?: LocalTime.of(9, 0), LocalDate.ofEpochDay(startEpochDay))
        else -> DoseSchedule.AsNeeded
    }
    return Medication(
        id = id, petId = petId, name = name, dosage = dosage, schedule = schedule,
        startDate = LocalDate.ofEpochDay(startEpochDay), endDate = endEpochDay?.let(LocalDate::ofEpochDay),
        unitsRemaining = unitsRemaining, unitsPerDose = unitsPerDose, remindersEnabled = remindersEnabled,
        pharmacyPrescriptionId = pharmacyPrescriptionId, pharmacyProviderId = pharmacyProviderId,
    )
}

fun Medication.toEntity(): MedicationEntity {
    val (type, times, interval) = when (val s = schedule) {
        is DoseSchedule.Daily -> Triple("DAILY", s.times.sorted().joinToString(","), 1)
        is DoseSchedule.EveryNDays -> Triple("EVERY_N_DAYS", s.time.toString(), s.intervalDays)
        DoseSchedule.AsNeeded -> Triple("AS_NEEDED", "", 0)
    }
    return MedicationEntity(
        id = id, petId = petId, name = name.trim(), dosage = dosage.trim(), scheduleType = type, times = times,
        intervalDays = interval, startEpochDay = startDate.toEpochDay(), endEpochDay = endDate?.toEpochDay(),
        unitsRemaining = unitsRemaining, unitsPerDose = unitsPerDose, remindersEnabled = remindersEnabled,
        pharmacyPrescriptionId = pharmacyPrescriptionId, pharmacyProviderId = pharmacyProviderId,
    )
}
