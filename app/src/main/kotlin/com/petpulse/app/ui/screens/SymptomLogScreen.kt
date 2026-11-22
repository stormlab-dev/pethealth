package com.petpulse.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petpulse.app.data.PetRepository
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.ui.components.UrgencyBadge
import com.petpulse.app.ui.theme.urgencyColor
import com.petpulse.core.model.Severity
import com.petpulse.core.model.Symptom
import com.petpulse.core.model.SymptomEntry
import com.petpulse.core.triage.TriageResult
import com.petpulse.core.triage.Urgency
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** When the owner first noticed the problem, as offset from now. */
enum class Onset(val label: String, val ago: Duration) {
    NOW("Just now", Duration.ZERO),
    HOURS("A few hours ago", 4.hours),
    DAY("About a day", 26.hours),
    TWO_DAYS("2–3 days", 2.days),
    WEEK("A week or more", 7.days),
}

class SymptomLogViewModel(private val repo: PetRepository, private val petId: Long) : ViewModel() {
    var selected by mutableStateOf(setOf<Symptom>()); private set
    var severity by mutableStateOf(Severity.MILD)
    var onset by mutableStateOf(Onset.NOW)
    var notes by mutableStateOf("")
    var result by mutableStateOf<TriageResult?>(null); private set
    var petName by mutableStateOf(""); private set

    init { viewModelScope.launch { petName = repo.getPet(petId)?.name.orEmpty() } }

    fun toggle(s: Symptom) {
        selected = if (s in selected) selected - s else selected + s
        result = null
    }

    private fun entry(): SymptomEntry {
        val now = Clock.System.now()
        return SymptomEntry(petId = petId, symptoms = selected, severity = severity, startedAt = now - onset.ago, loggedAt = now, notes = notes)
    }

    fun assess() = viewModelScope.launch { result = repo.triage(entry()) }

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        val e = entry()
        val r = result ?: repo.triage(e)
        repo.logSymptoms(e, r)
        onSaved()
    }

    fun summary(): String = buildString {
        append(selected.joinToString { it.label })
        append(" (${severity.name.lowercase()}, started ${onset.label.lowercase()})")
        if (notes.isNotBlank()) append(". Notes: ").append(notes.trim())
    }
}

// Symptoms grouped so red flags are easy to find.
private val redFlags = listOf(
    Symptom.DIFFICULTY_BREATHING, Symptom.COLLAPSE, Symptom.SEIZURE, Symptom.PALE_GUMS, Symptom.TOXIN_INGESTION,
    Symptom.TRAUMA, Symptom.UNPRODUCTIVE_RETCHING, Symptom.SWOLLEN_ABDOMEN, Symptom.STRAINING_TO_URINATE, Symptom.BLOOD_IN_STOOL,
)
private val common = Symptom.entries - redFlags.toSet()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SymptomLogScreen(petId: Long, onDone: () -> Unit, onBookVet: (String) -> Unit) {
    val vm = appViewModel { SymptomLogViewModel(it.repository, petId) }
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("What's going on with ${vm.petName}?") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Common signs", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                common.forEach { s -> FilterChip(selected = s in vm.selected, onClick = { vm.toggle(s) }, label = { Text(s.label) }) }
            }
            Text("Serious signs", style = MaterialTheme.typography.titleSmall, color = urgencyColor(Urgency.EMERGENCY))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                redFlags.forEach { s -> FilterChip(selected = s in vm.selected, onClick = { vm.toggle(s) }, label = { Text(s.label) }) }
            }
            Text("How bad does it seem?", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Severity.entries.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = vm.severity == s,
                        onClick = { vm.severity = s },
                        shape = SegmentedButtonDefaults.itemShape(i, Severity.entries.size),
                    ) { Text(s.name.lowercase().replaceFirstChar(Char::uppercase)) }
                }
            }
            Text("When did it start?", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Onset.entries.forEach { o -> FilterChip(selected = vm.onset == o, onClick = { vm.onset = o }, label = { Text(o.label) }) }
            }
            OutlinedTextField(vm.notes, { vm.notes = it }, label = { Text("Notes (what, how often, anything they ate)") }, minLines = 2, modifier = Modifier.fillMaxWidth())

            Button(onClick = { vm.assess() }, enabled = vm.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("How urgent is this?") }

            vm.result?.let { r ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        UrgencyBadge(r.urgency)
                        Text(r.urgency.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = urgencyColor(r.urgency))
                        r.reasons.forEach { Text("• $it") }
                        if (r.watchFor.isNotEmpty()) {
                            Text("Watch for", style = MaterialTheme.typography.titleSmall)
                            r.watchFor.forEach { Text("• $it") }
                        }
                        Text(r.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (r.urgency) {
                            Urgency.EMERGENCY -> Button(
                                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=emergency+vet"))) },
                                colors = ButtonDefaults.buttonColors(containerColor = urgencyColor(Urgency.EMERGENCY)),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Find the nearest emergency vet") }
                            Urgency.SEE_VET_24H, Urgency.BOOK_VISIT -> Button(
                                onClick = { vm.save { onBookVet(vm.summary()) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Save and book a vet") }
                            Urgency.MONITOR -> {}
                        }
                    }
                }
            }
            OutlinedButton(onClick = { vm.save(onDone) }, enabled = vm.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save to log") }
        }
    }
}
