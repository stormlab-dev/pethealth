package com.petpulse.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.petpulse.core.triage.Urgency

private val Light = lightColorScheme(
    primary = Color(0xFF2E7D6B), onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EFDD), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4B635B), tertiary = Color(0xFF8A5A00),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9CD3C1), onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142), onPrimaryContainer = Color(0xFFB8EFDD),
    secondary = Color(0xFFB2CCC2), tertiary = Color(0xFFFFB952),
)

@Composable
fun PetPulseTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Fixed traffic-light colors so urgency reads the same under dynamic color. */
fun urgencyColor(u: Urgency): Color = when (u) {
    Urgency.EMERGENCY -> Color(0xFFC62828)
    Urgency.SEE_VET_24H -> Color(0xFFE65100)
    Urgency.BOOK_VISIT -> Color(0xFFF9A825)
    Urgency.MONITOR -> Color(0xFF2E7D32)
}
