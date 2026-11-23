package com.petpulse.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PetEntity::class, SymptomEntity::class, WeightEntity::class, VaccinationEntity::class,
        MedicationEntity::class, DoseLogEntity::class, ClinicLinkEntity::class, AppointmentEntity::class,
        RefillOrderEntity::class, MediaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PetPulseDatabase : RoomDatabase() {
    abstract fun pets(): PetDao
    abstract fun health(): HealthLogDao
    abstract fun meds(): MedicationDao
    abstract fun integrations(): IntegrationDao
    abstract fun media(): MediaDao

    companion object {
        fun create(context: Context): PetPulseDatabase =
            Room.databaseBuilder(context, PetPulseDatabase::class.java, "petpulse.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /** v2: photos and videos. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pet_media` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `petId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mimeType` TEXT NOT NULL, `durationSec` INTEGER, `caption` TEXT, " +
                        "`takenAtMillis` INTEGER NOT NULL, FOREIGN KEY(`petId`) REFERENCES `pets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_pet_media_petId` ON `pet_media` (`petId`)")
            }
        }
    }
}
