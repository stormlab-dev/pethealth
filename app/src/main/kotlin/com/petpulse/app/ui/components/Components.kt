package com.petpulse.app.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.petpulse.app.ui.theme.urgencyColor
import com.petpulse.core.model.Species
import com.petpulse.core.triage.Urgency
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

fun speciesEmoji(s: Species) = when (s) {
    Species.DOG -> "🐕"; Species.CAT -> "🐈"; Species.RABBIT -> "🐇"
    Species.BIRD -> "🦜"; Species.REPTILE -> "🦎"; Species.OTHER -> "🐾"
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

@Composable
fun UrgencyBadge(urgency: Urgency, modifier: Modifier = Modifier) {
    val c = urgencyColor(urgency)
    Surface(color = c.copy(alpha = 0.15f), contentColor = c, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(urgency.headline, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusPill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), contentColor = color, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun DateField(label: String, value: LocalDate?, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, maxToday: Boolean = true) {
    val ctx = LocalContext.current
    val open = {
        val d = value ?: LocalDate.now()
        DatePickerDialog(ctx, { _, y, m, day -> onChange(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).apply {
            if (maxToday) datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }
    Box(modifier) {
        OutlinedTextField(
            value = value?.format(dateFormat) ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay so the whole field opens the picker.
        Box(Modifier.matchParentSize().clickable { open() })
    }
}

fun showTimePicker(context: android.content.Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onPick(LocalTime.of(h, m)) }, initial.hour, initial.minute, false).show()
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
