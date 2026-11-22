package com.petpulse.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.petpulse.app.PetPulseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/** Handles "Given" / "Skip" taps on a dose notification. */
class DoseActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val medId = intent.getLongExtra(DoseReminderWorker.KEY_MED_ID, -1)
        if (medId < 0) return
        val scheduledFor = Instant.ofEpochMilli(intent.getLongExtra(DoseReminderWorker.KEY_SCHEDULED_FOR, System.currentTimeMillis()))
        val taken = intent.action == ACTION_TAKEN
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        val pending = goAsync()
        val repo = (context.applicationContext as PetPulseApp).container.repository
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.recordDose(medId, scheduledFor, taken)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TAKEN = "com.petpulse.app.DOSE_TAKEN"
        const val ACTION_SKIPPED = "com.petpulse.app.DOSE_SKIPPED"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }
}
