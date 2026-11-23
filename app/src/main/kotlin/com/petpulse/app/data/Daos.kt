package com.petpulse.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PetDao {
    @Query("SELECT * FROM pets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PetEntity>>

    @Query("SELECT * FROM pets WHERE id = :id")
    fun observe(id: Long): Flow<PetEntity?>

    @Query("SELECT * FROM pets WHERE id = :id")
    suspend fun get(id: Long): PetEntity?

    @Upsert
    suspend fun upsert(pet: PetEntity): Long

    @Query("DELETE FROM pets WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HealthLogDao {
    @Query("SELECT * FROM symptom_entries WHERE petId = :petId ORDER BY loggedAtMillis DESC")
    fun observeSymptoms(petId: Long): Flow<List<SymptomEntity>>

    @Query("SELECT * FROM symptom_entries WHERE petId = :petId AND loggedAtMillis >= :sinceMillis ORDER BY loggedAtMillis DESC")
    suspend fun symptomsSince(petId: Long, sinceMillis: Long): List<SymptomEntity>

    @Insert
    suspend fun insertSymptom(entry: SymptomEntity): Long

    @Query("DELETE FROM symptom_entries WHERE id = :id")
    suspend fun deleteSymptom(id: Long)

    @Query("SELECT * FROM weights WHERE petId = :petId ORDER BY epochDay")
    fun observeWeights(petId: Long): Flow<List<WeightEntity>>

    @Query("SELECT * FROM weights WHERE petId = :petId ORDER BY epochDay")
    suspend fun weights(petId: Long): List<WeightEntity>

    @Insert
    suspend fun insertWeights(entries: List<WeightEntity>)

    @Query("DELETE FROM weights WHERE id = :id")
    suspend fun deleteWeight(id: Long)

    @Query("SELECT * FROM vaccinations WHERE petId = :petId ORDER BY administeredEpochDay DESC")
    fun observeVaccinations(petId: Long): Flow<List<VaccinationEntity>>

    @Query("SELECT * FROM vaccinations WHERE petId = :petId")
    suspend fun vaccinations(petId: Long): List<VaccinationEntity>

    @Query("SELECT * FROM vaccinations")
    suspend fun allVaccinations(): List<VaccinationEntity>

    @Insert
    suspend fun insertVaccinations(entries: List<VaccinationEntity>)

    @Query("DELETE FROM vaccinations WHERE id = :id")
    suspend fun deleteVaccination(id: Long)
}

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications WHERE petId = :petId ORDER BY name COLLATE NOCASE")
    fun observeForPet(petId: Long): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications")
    suspend fun all(): List<MedicationEntity>

    @Query("SELECT * FROM medications WHERE id = :id")
    suspend fun get(id: Long): MedicationEntity?

    @Upsert
    suspend fun upsert(med: MedicationEntity): Long

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun logDose(log: DoseLogEntity): Long

    @Query("SELECT * FROM dose_logs WHERE medicationId = :medId ORDER BY scheduledForMillis DESC LIMIT :limit")
    fun observeDoseLogs(medId: Long, limit: Int): Flow<List<DoseLogEntity>>

    @Query("UPDATE medications SET unitsRemaining = MAX(0, unitsRemaining - unitsPerDose) WHERE id = :id AND unitsRemaining IS NOT NULL")
    suspend fun decrementUnits(id: Long)
}

@Dao
interface IntegrationDao {
    @Query("SELECT * FROM clinic_links WHERE petId = :petId")
    fun observeClinicLink(petId: Long): Flow<ClinicLinkEntity?>

    @Query("SELECT * FROM clinic_links WHERE petId = :petId")
    suspend fun clinicLink(petId: Long): ClinicLinkEntity?

    @Upsert
    suspend fun upsertClinicLink(link: ClinicLinkEntity)

    @Query("DELETE FROM clinic_links WHERE petId = :petId")
    suspend fun deleteClinicLink(petId: Long)

    @Query("SELECT * FROM appointments WHERE petId = :petId ORDER BY start")
    fun observeAppointments(petId: Long): Flow<List<AppointmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppointment(a: AppointmentEntity)

    @Query("SELECT r.* FROM refill_orders r JOIN medications m ON m.id = r.medicationId WHERE m.petId = :petId ORDER BY r.placedAtMillis DESC")
    fun observeRefillOrders(petId: Long): Flow<List<RefillOrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRefillOrder(o: RefillOrderEntity)

    @Delete
    suspend fun deleteRefillOrder(o: RefillOrderEntity)
}

@Dao
interface MediaDao {
    @Query("SELECT * FROM pet_media WHERE petId = :petId ORDER BY takenAtMillis DESC")
    fun observeForPet(petId: Long): Flow<List<MediaEntity>>

    @Query("SELECT COUNT(*) FROM pet_media")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pet_media")
    suspend fun count(): Int

    @Insert
    suspend fun insert(m: MediaEntity): Long

    @Query("DELETE FROM pet_media WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT fileName FROM pet_media")
    suspend fun allFileNames(): List<String>
}
