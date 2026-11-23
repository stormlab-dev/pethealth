package com.petpulse.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.petpulse.app.cloud.CloudConfig
import com.petpulse.app.reminders.Notifications
import kotlinx.coroutines.launch
import com.petpulse.app.ui.PetPulseNavHost
import com.petpulse.app.ui.theme.PetPulseTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Notifications.canPost(this)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as PetPulseApp).container
        container.billing.attach(this)
        com.petpulse.app.ads.Ads.start(this)
        lifecycleScope.launch {
            container.billing.refresh()
            container.media.pruneOrphans()
            if (CloudConfig.enabled) runCatching {
                container.cloud.accessToken()
                container.cloud.userId?.let { container.billing.logIn(it) }
            }
        }
        val startPetId = intent.getLongExtra(EXTRA_PET_ID, -1L).takeIf { it >= 0 }
        setContent {
            PetPulseTheme { PetPulseNavHost(startPetId) }
        }
    }

    companion object {
        const val EXTRA_PET_ID = "petId"
    }
}
