package com.petpulse.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Dates are stored as epoch days, instants as epoch millis, to keep the schema converter-free.

@Entity(tableName = "pets")
data class PetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val species: String,
    val breed: String?,
    val sex: String,
    val neutered: Boolean,
    val birthEpochDay: Long?,
    val microchipId: String?,
    val notes: String?,
)

@Entity(
    tableName = "symptom_entries",
    foreignKeys = [ForeignKey(entity = PetEntity::class, parentColumns = ["id"], childColumns = ["petId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("petId")],
)
data class SymptomEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val petId: Long,
    /** Comma-separated [com.petpulse.core.model.Symptom.key]s. */
    val symptomKeys: String,
    val severity: String,
    val startedAtMillis: Long,
    val loggedAtMillis: Long,
    val notes: String?,
    /** Urgency the triage engine gave at the time, for history display. */
    val urgency: String?,
)

@Entity(
    tableName = "weights",
    foreignKeys = [ForeignKey(entity = PetEntity::class, parentColumns = ["id"], childColumns = ["petId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("petId")],
)
data class WeightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val petId: Long,
    val kilograms: Double,
    val epochDay: Long,
)

@Entity(
    tableName = "vaccinations",
    foreignKeys = [ForeignKey(entity = PetEntity::class, parentColumns = ["id"], childColumns = ["petId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("petId")],
)
data class VaccinationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val petId: Long,
    val name: String,
    val administeredEpochDay: Long,
    val validForMonths: Int?,
    val clinicName: String?,
    val sourceRecordId: String?,
)

@Entity(
    tableName = "medications",
    foreignKeys = [ForeignKey(entity = PetEntity::class, parentColumns = ["id"], childColumns = ["petId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("petId")],
)
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val petId: Long,
    val name: String,
    val dosage: String,
    /** DAILY, EVERY_N_DAYS or AS_NEEDED. */
    val scheduleType: String,
    /** "08:00,20:00" for DAILY; single time for EVERY_N_DAYS. */
    val times: String,
    val intervalDays: Int,
    val startEpochDay: Long,
    val endEpochDay: Long?,
    val unitsRemaining: Double?,
    val unitsPerDose: Double,
    val remindersEnabled: Boolean,
    val pharmacyPrescriptionId: String?,
    val pharmacyProviderId: String?,
)

@Entity(
    tableName = "dose_logs",
    foreignKeys = [ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicationId")],
)
data class DoseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val scheduledForMillis: Long,
    val takenAtMillis: Long?,
    val skipped: Boolean,
)

/** A pet's connection to a clinic through a clinic-network provider. */
@Entity(
    tableName = "clinic_links",
    foreignKeys = [ForeignKey(entity = PetEntity::class, parentColumns = ["id"], childColumns = ["petId"], onDelete = ForeignKey.CASCADE)],
)
data class ClinicLinkEntity(
    @PrimaryKey val petId: Long,
    val providerId: String,
    val clinicId: String,
    val clinicName: String,
    val patientId: String,
    val lastSyncedMillis: Long?,
)

@Entity(
    tableName = "appointments",
    foreignKeys = [ForeignKey(entity = PetEntity::class, parentColumns = ["id"], childColumns = ["petId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("petId")],
)
data class AppointmentEntity(
    @PrimaryKey val externalId: String,
    val petId: Long,
    val providerId: String,
    val clinicId: String,
    val clinicName: String,
    /** Local date-time as ISO string, e.g. 2026-11-24T09:30. */
    val start: String,
    val minutes: Int,
    val reason: String,
    val status: String,
)

@Entity(
    tableName = "refill_orders",
    foreignKeys = [ForeignKey(entity = MedicationEntity::class, parentColumns = ["id"], childColumns = ["medicationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("medicationId")],
)
data class RefillOrderEntity(
    @PrimaryKey val externalId: String,
    val medicationId: Long,
    val providerId: String,
    val prescriptionId: String,
    val state: String,
    val placedAtMillis: Long,
    val message: String?,
    val trackingUrl: String?,
)
