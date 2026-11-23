package com.petpulse.app.ads

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.petpulse.app.BuildConfig
import com.petpulse.app.PetPulseApp
import com.petpulse.core.billing.Plan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob banners for the free plan. Premium and Family never see ads. Before any ad loads, Google's
 * consent form (UMP) is shown where the law requires it (EEA, UK, some US states).
 */
object Ads {
    private val started = AtomicBoolean(false)
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    fun start(activity: Activity) {
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    if (consent.canRequestAds()) initialize(activity)
                }
            },
            { _ -> if (consent.canRequestAds()) initialize(activity) },
        )
        // Returning users who already answered can load ads right away.
        if (consent.canRequestAds()) initialize(activity)
    }

    private fun initialize(activity: Activity) {
        if (!started.compareAndSet(false, true)) return
        MobileAds.initialize(activity.applicationContext) { _ready.value = true }
    }

    /** Re-opens the consent choices (shown in Plans when the law requires an entry point). */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ -> }
    }

    fun privacyOptionsRequired(activity: Activity): Boolean =
        UserMessagingPlatform.getConsentInformation(activity).privacyOptionsRequirementStatus ==
            com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
}

/**
 * An adaptive banner shown only to free-plan users. Keep it off health screens (symptom checks,
 * medications, vet) so ads never sit next to medical guidance.
 */
@Composable
fun FreePlanBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as PetPulseApp).container
    val customer by container.billing.state.collectAsState()
    val ready by Ads.ready.collectAsState()
    if (customer.plan != Plan.FREE || !ready) return
    val widthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        factory = { ctx ->
            AdView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                adUnitId = BuildConfig.ADMOB_BANNER_ID
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}
