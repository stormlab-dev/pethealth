package com.petpulse.app.reminders

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.petpulse.app.PetPulseApp
import com.petpulse.core.health.RefillPredictor
import com.petpulse.core.health.VaccineStatus
import com.petpulse.core.health.VaccineTracker
import com.petpulse.app.data.toModel
import com.petpulse.app.util.local
import com.petpulse.app.util.pretty
import com.petpulse.app.util.today
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.plus

class DoseReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PetPulseApp).container
        val medId = inputData.getLong(KEY_MED_ID, -1)
        val scheduledFor = inputData.getLong(KEY_SCHEDULED_FOR, System.currentTimeMillis())
        val med = container.repository.getMedication(medId) ?: return Result.success()
        val pet = container.repository.getPet(med.petId)

        if (med.remindersEnabled) {
            val notificationId = notificationId(medId)
            val builder = Notifications.base(
                applicationContext,
                Notifications.CHANNEL_DOSES,
                "Time for ${pet?.name ?: "your pet"}'s ${med.name}",
                med.dosage,
            )
                .setContentIntent(Notifications.openAppIntent(applicationContext, med.petId))
                .addAction(0, "Given", actionIntent(DoseActionReceiver.ACTION_TAKEN, medId, scheduledFor, notificationId))
                .addAction(0, "Skip", actionIntent(DoseActionReceiver.ACTION_SKIPPED, medId, scheduledFor, notificationId))
            Notifications.post(applicationContext, notificationId, builder)
        }

        // Queue the following dose.
        val after = Instant.fromEpochMilliseconds(scheduledFor).local()
        container.reminders.schedule(med, after)
        return Result.success()
    }

    private fun actionIntent(action: String, medId: Long, scheduledFor: Long, notificationId: Int): PendingIntent {
        val intent = Intent(applicationContext, DoseActionReceiver::class.java).apply {
            this.action = action
            putExtra(KEY_MED_ID, medId)
            putExtra(KEY_SCHEDULED_FOR, scheduledFor)
            putExtra(DoseActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val requestCode = (action.hashCode() * 31 + medId.toInt())
        return PendingIntent.getBroadcast(applicationContext, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val KEY_MED_ID = "medId"
        const val KEY_SCHEDULED_FOR = "scheduledFor"
        fun notificationId(medId: Long) = 10_000 + medId.toInt()
    }
}

/** Once a day: vaccines coming due and medications running low. */
class DailyHealthCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as PetPulseApp).container
        val repo = container.repository
        val today = today()

        val vaccines = container.db.health().allVaccinations().map { it.toModel() }
        vaccines.groupBy { it.petId }.forEach { (petId, list) ->
            val due = VaccineTracker.latestByName(list).filter {
                val s = VaccineTracker.status(it, today)
                s == VaccineStatus.OVERDUE || (s == VaccineStatus.DUE_SOON && it.dueOn == today.plus(VaccineTracker.DUE_SOON_DAYS, DateTimeUnit.DAY))
            }
            if (due.isNotEmpty()) {
                val pet = repo.getPet(petId)
                Notifications.post(
                    applicationContext, 20_000 + petId.toInt(),
                    Notifications.base(
                        applicationContext, Notifications.CHANNEL_HEALTH,
                        "${pet?.name ?: "Your pet"} has vaccines due",
                        due.joinToString { "${it.name} (${it.dueOn?.pretty()})" },
                    ).setContentIntent(Notifications.openAppIntent(applicationContext, petId)),
                )
            }
        }

        repo.allMedications().forEach { med ->
            val f = RefillPredictor.forecast(med, today) ?: return@forEach
            if (f.shouldReorder) {
                Notifications.post(
                    applicationContext, 30_000 + med.id.toInt(),
                    Notifications.base(
                        applicationContext, Notifications.CHANNEL_HEALTH,
                        "${med.name} is running low",
                        "About ${f.daysLeft} days left. Tap to request a refill.",
                    ).setContentIntent(Notifications.openAppIntent(applicationContext, med.petId)),
                )
            }
        }
        // Self-heal: make sure every medication has its next reminder queued.
        container.reminders.rescheduleAll()
        return Result.success()
    }
}
