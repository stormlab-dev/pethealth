package com.petpulse.core.integrations.vet

import com.petpulse.core.model.Vaccination
import com.petpulse.core.model.WeightEntry
import kotlinx.datetime.monthsUntil

data class ImportPlan(val newVaccinations: List<Vaccination>, val newWeights: List<WeightEntry>) {
    val isEmpty get() = newVaccinations.isEmpty() && newWeights.isEmpty()
}

/** Turns clinic records into local entries, skipping anything already imported or typed in by hand. */
object RecordImporter {
    fun plan(
        petId: Long,
        clinicName: String,
        record: MedicalRecord,
        existingVaccinations: List<Vaccination>,
        existingWeights: List<WeightEntry>,
    ): ImportPlan {
        val importedIds = existingVaccinations.mapNotNull { it.sourceRecordId }.toSet()
        val manualKeys = existingVaccinations.map { it.name.trim().lowercase() to it.administeredOn }.toSet()
        val vaccines = record.vaccinations
            .filter { it.recordId !in importedIds && (it.name.trim().lowercase() to it.administeredOn) !in manualKeys }
            .map {
                Vaccination(
                    petId = petId,
                    name = it.name,
                    administeredOn = it.administeredOn,
                    validForMonths = it.nextDueOn?.let { due -> it.administeredOn.monthsUntil(due) },
                    clinicName = clinicName,
                    sourceRecordId = it.recordId,
                )
            }
        val weightDates = existingWeights.map { it.date }.toSet()
        val weights = record.weights
            .filter { it.date !in weightDates }
            .map { WeightEntry(petId = petId, kilograms = it.kilograms, date = it.date) }
        return ImportPlan(vaccines, weights)
    }
}
