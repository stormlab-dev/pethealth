package com.petpulse.app

import android.app.Application
import com.petpulse.app.data.LinkedAccountStore
import com.petpulse.app.data.PetPulseDatabase
import com.petpulse.app.data.PetRepository
import com.petpulse.app.reminders.Notifications
import com.petpulse.app.reminders.ReminderScheduler
import com.petpulse.core.integrations.IntegrationRegistry
import com.petpulse.core.integrations.pharmacy.MockPharmacyProvider
import com.petpulse.core.integrations.vet.MockVetClinicProvider
import com.petpulse.app.billing.AppBilling
import com.petpulse.app.cloud.AiTriageClient
import com.petpulse.app.cloud.CloudSession
import com.petpulse.app.media.MediaLibrary
import com.petpulse.core.triage.RuleBasedTriageEngine

/** Manual dependency container; small enough that a DI framework isn't worth it yet. */
class AppContainer(app: Application) {
    val db = PetPulseDatabase.create(app)
    val repository = PetRepository(db, RuleBasedTriageEngine())
    val accounts = LinkedAccountStore(app)

    /** Swap the mocks for real partner implementations here (see docs/INTEGRATIONS.md). */
    val integrations = IntegrationRegistry(
        pharmacies = listOf(MockPharmacyProvider()),
        clinics = listOf(MockVetClinicProvider()),
    )
    val reminders = ReminderScheduler(app, repository)
    val media = MediaLibrary(app, db)
    val cloud = CloudSession(app)
    val ai = AiTriageClient(cloud)
    val billing = AppBilling(app)
}

class PetPulseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.reminders.scheduleDailyHealthCheck()
    }
}
