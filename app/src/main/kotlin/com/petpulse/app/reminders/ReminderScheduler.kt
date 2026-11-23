package com.petpulse.app.reminders

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.petpulse.app.data.PetRepository
import com.petpulse.core.health.DoseScheduler
import com.petpulse.core.model.Medication
import com.petpulse.app.util.nowLocal
import com.petpulse.app.util.zone
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import java.util.concurrent.TimeUnit

/**
 * Schedules one WorkManager job per medication for its next dose; the job re-schedules the
 * following dose when it fires. WorkManager persists jobs across reboots.
 */
class ReminderScheduler(context: Context, private val repository: PetRepository) {
    private val work = WorkManager.getInstance(context)

    fun schedule(med: Medication, after: LocalDateTime = nowLocal()) {
        if (!med.remindersEnabled) return cancel(med.id)
        val next = DoseScheduler.nextDose(med, after) ?: return cancel(med.id)
        val nextInstant = next.toInstant(zone)
        val delayMs = (nextInstant - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<DoseReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    DoseReminderWorker.KEY_MED_ID to med.id,
                    DoseReminderWorker.KEY_SCHEDULED_FOR to nextInstant.toEpochMilliseconds(),
                ),
            )
            .build()
        work.enqueueUniqueWork(doseWorkName(med.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(medId: Long) {
        work.cancelUniqueWork(doseWorkName(medId))
    }

    suspend fun rescheduleAll() {
        repository.allMedications().forEach { schedule(it) }
    }

    fun scheduleDailyHealthCheck() {
        val request = PeriodicWorkRequestBuilder<DailyHealthCheckWorker>(1, TimeUnit.DAYS).build()
        work.enqueueUniquePeriodicWork("daily-health-check", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun doseWorkName(medId: Long) = "dose-$medId"
}
