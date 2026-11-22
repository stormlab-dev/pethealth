package com.petpulse.app.data

import com.petpulse.core.health.WeightAnalyzer
import com.petpulse.core.health.PetAge
import com.petpulse.core.model.*
import com.petpulse.core.triage.TriageContext
import com.petpulse.core.triage.TriageEngine
import com.petpulse.core.triage.TriageResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.petpulse.app.util.today
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

/** The app's single source of truth for pet data. */
class PetRepository(private val db: PetPulseDatabase, private val triage: TriageEngine) {

    val pets: Flow<List<Pet>> = db.pets().observeAll().map { list -> list.map { it.toModel() } }
    fun pet(id: Long): Flow<Pet?> = db.pets().observe(id).map { it?.toModel() }
    suspend fun getPet(id: Long): Pet? = db.pets().get(id)?.toModel()

    suspend fun savePet(pet: Pet): Long {
        val id = db.pets().upsert(pet.toEntity())
        return if (id == -1L) pet.id else id
    }

    suspend fun deletePet(id: Long) = db.pets().delete(id)

    // --- Symptoms + triage -------------------------------------------------------

    fun symptoms(petId: Long): Flow<List<Pair<SymptomEntry, String?>>> =
        db.health().observeSymptoms(petId).map { list -> list.map { it.toModel() to it.urgency } }

    suspend fun triage(entry: SymptomEntry): TriageResult {
        val pet = getPet(entry.petId) ?: error("Pet ${entry.petId} not found")
        val since = (entry.loggedAt - 14.days).toEpochMilliseconds()
        val recent = db.health().symptomsSince(pet.id, since).map { it.toModel() }.filter { it.id != entry.id }
        val weights = db.health().weights(pet.id).map { it.toModel() }
        val ctx = TriageContext(
            pet = pet,
            ageYears = PetAge.years(pet.birthDate, today()),
            recentEntries = recent,
            recentWeightChangePercent = WeightAnalyzer.trend(weights)?.takeIf { it.overDays <= 45 }?.changePercent,
        )
        return triage.assess(entry, ctx)
    }

    suspend fun logSymptoms(entry: SymptomEntry, result: TriageResult?): Long =
        db.health().insertSymptom(entry.toEntity(result?.urgency?.name))

    suspend fun deleteSymptom(id: Long) = db.health().deleteSymptom(id)

    // --- Weight ------------------------------------------------------------------

    fun weights(petId: Long): Flow<List<WeightEntry>> = db.health().observeWeights(petId).map { l -> l.map { it.toModel() } }
    suspend fun addWeight(entry: WeightEntry) = db.health().insertWeights(listOf(entry.toEntity()))
    suspend fun deleteWeight(id: Long) = db.health().deleteWeight(id)

    // --- Vaccines ----------------------------------------------------------------

    fun vaccinations(petId: Long): Flow<List<Vaccination>> = db.health().observeVaccinations(petId).map { l -> l.map { it.toModel() } }
    suspend fun addVaccination(v: Vaccination) = db.health().insertVaccinations(listOf(v.toEntity()))
    suspend fun deleteVaccination(id: Long) = db.health().deleteVaccination(id)

    // --- Medications -------------------------------------------------------------

    fun medications(petId: Long): Flow<List<Medication>> = db.meds().observeForPet(petId).map { l -> l.map { it.toModel() } }
    suspend fun getMedication(id: Long): Medication? = db.meds().get(id)?.toModel()
    suspend fun allMedications(): List<Medication> = db.meds().all().map { it.toModel() }

    suspend fun saveMedication(med: Medication): Long {
        val id = db.meds().upsert(med.toEntity())
        return if (id == -1L) med.id else id
    }

    suspend fun deleteMedication(id: Long) = db.meds().delete(id)

    suspend fun recordDose(medId: Long, scheduledFor: Instant, taken: Boolean) {
        db.meds().logDose(DoseLogEntity(medicationId = medId, scheduledForMillis = scheduledFor.toEpochMilliseconds(), takenAtMillis = if (taken) System.currentTimeMillis() else null, skipped = !taken))
        if (taken) db.meds().decrementUnits(medId)
    }

    fun doseLogs(medId: Long, limit: Int = 20) = db.meds().observeDoseLogs(medId, limit)
}
