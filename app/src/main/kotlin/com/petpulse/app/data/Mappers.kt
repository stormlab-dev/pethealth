package com.petpulse.app.data

import com.petpulse.core.model.*
import com.petpulse.app.util.epochDay
import com.petpulse.app.util.epochDayToDate
import com.petpulse.app.util.millisToInstant
import kotlinx.datetime.LocalTime

fun PetEntity.toModel() = Pet(
    id = id, name = name, species = Species.valueOf(species), breed = breed, sex = Sex.valueOf(sex),
    neutered = neutered, birthDate = birthEpochDay?.epochDayToDate(), microchipId = microchipId, notes = notes,
)

fun Pet.toEntity() = PetEntity(
    id = id, name = name.trim(), species = species.name, breed = breed?.trim()?.ifEmpty { null }, sex = sex.name,
    neutered = neutered, birthEpochDay = birthDate?.epochDay(), microchipId = microchipId?.ifBlank { null }, notes = notes?.ifBlank { null },
)

fun SymptomEntity.toModel() = SymptomEntry(
    id = id, petId = petId,
    symptoms = symptomKeys.split(',').filter { it.isNotBlank() }.map(Symptom::fromKey).toSet(),
    severity = Severity.valueOf(severity),
    startedAt = startedAtMillis.millisToInstant(), loggedAt = loggedAtMillis.millisToInstant(), notes = notes,
)

fun SymptomEntry.toEntity(urgency: String?) = SymptomEntity(
    id = id, petId = petId, symptomKeys = symptoms.joinToString(",") { it.key }, severity = severity.name,
    startedAtMillis = startedAt.toEpochMilliseconds(), loggedAtMillis = loggedAt.toEpochMilliseconds(), notes = notes?.ifBlank { null }, urgency = urgency,
)

fun WeightEntity.toModel() = WeightEntry(id, petId, kilograms, epochDay.epochDayToDate())
fun WeightEntry.toEntity() = WeightEntity(id, petId, kilograms, date.epochDay())

fun VaccinationEntity.toModel() = Vaccination(id, petId, name, administeredEpochDay.epochDayToDate(), validForMonths, clinicName, sourceRecordId)
fun Vaccination.toEntity() = VaccinationEntity(id, petId, name.trim(), administeredOn.epochDay(), validForMonths, clinicName, sourceRecordId)

fun MedicationEntity.toModel(): Medication {
    val parsedTimes = times.split(',').filter { it.isNotBlank() }.map { LocalTime.parse(it.trim()) }
    val schedule = when (scheduleType) {
        "DAILY" -> DoseSchedule.Daily(parsedTimes)
        "EVERY_N_DAYS" -> DoseSchedule.EveryNDays(intervalDays, parsedTimes.firstOrNull() ?: LocalTime(9, 0), startEpochDay.epochDayToDate())
        else -> DoseSchedule.AsNeeded
    }
    return Medication(
        id = id, petId = petId, name = name, dosage = dosage, schedule = schedule,
        startDate = startEpochDay.epochDayToDate(), endDate = endEpochDay?.epochDayToDate(),
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
        intervalDays = interval, startEpochDay = startDate.epochDay(), endEpochDay = endDate?.epochDay(),
        unitsRemaining = unitsRemaining, unitsPerDose = unitsPerDose, remindersEnabled = remindersEnabled,
        pharmacyPrescriptionId = pharmacyPrescriptionId, pharmacyProviderId = pharmacyProviderId,
    )
}
