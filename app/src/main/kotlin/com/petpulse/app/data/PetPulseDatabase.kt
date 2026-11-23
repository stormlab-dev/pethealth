package com.petpulse.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PetEntity::class, SymptomEntity::class, WeightEntity::class, VaccinationEntity::class,
        MedicationEntity::class, DoseLogEntity::class, ClinicLinkEntity::class, AppointmentEntity::class,
        RefillOrderEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PetPulseDatabase : RoomDatabase() {
    abstract fun pets(): PetDao
    abstract fun health(): HealthLogDao
    abstract fun meds(): MedicationDao
    abstract fun integrations(): IntegrationDao

    companion object {
        fun create(context: Context): PetPulseDatabase =
            Room.databaseBuilder(context, PetPulseDatabase::class.java, "petpulse.db").build()
    }
}
