package com.petpulse.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.petpulse.app.AppContainer
import com.petpulse.app.cloud.CloudException
import com.petpulse.app.media.MediaLibrary
import com.petpulse.app.util.today
import com.petpulse.core.billing.Entitlements
import com.petpulse.core.billing.Gate
import com.petpulse.core.billing.Plan
import com.petpulse.core.health.PetAge
import com.petpulse.core.triage.AiTriageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class SymptomLogViewModel(private val c: AppContainer, private val petId: Long) : ViewModel() {
    private val repo: PetRepository = c.repository
    var photo by mutableStateOf<Uri?>(null)
    var ai by mutableStateOf<AiTriageResult?>(null); private set
    var aiBusy by mutableStateOf(false); private set
    var aiMessage by mutableStateOf<Pair<String, Boolean>?>(null); private set
    val plan get() = c.billing.state.value.plan

    fun attachPhoto(uri: Uri?) { photo = uri; ai = null }

    fun runAi(context: Context) = viewModelScope.launch {
        val e = entry()
        val rule = repo.triage(e)
        result = rule
        aiMessage = null
        when (val gate = Entitlements.canUseAiPhotoCheck(plan)) {
            is Gate.NeedsUpgrade -> { aiMessage = (gate.reason) to true; return@launch }
            Gate.Allowed -> {}
        }
        if (!c.ai.available) { aiMessage = "The AI check needs the CuteFamilyPet cloud, which isn't set up in this build yet. The rule-based result is shown." to false; return@launch }
        aiBusy = true
        try {
            val pet = repo.getPet(petId)!!
            val photoData = photo?.let { u -> withContext(Dispatchers.IO) { MediaLibrary.jpegBase64ForAi(context, u) } }
            ai = c.ai.check(
                pet = pet,
                ageYears = PetAge.years(pet.birthDate, today()),
                weightKg = repo.latestWeightKg(petId),
                symptomLabels = selected.map { it.label },
                severity = severity,
                hoursSinceOnset = onset.ago.inWholeMinutes / 60.0,
                notes = notes,
                rule = rule,
                photoJpegBase64 = photoData,
            )
        } catch (ex: CloudException) {
            aiMessage = ex.message.orEmpty() to (ex.code == "UPGRADE_REQUIRED")
        } catch (ex: Exception) {
            aiMessage = "The AI check is unavailable right now. The rule-based result still applies." to false
        } finally {
            aiBusy = false
        }
    }
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
        ai = null
    }

    private fun entry(): SymptomEntry {
        val now = Clock.System.now()
        return SymptomEntry(petId = petId, symptoms = selected, severity = severity, startedAt = now - onset.ago, loggedAt = now, notes = notes)
    }

    fun assess() = viewModelScope.launch { result = repo.triage(entry()) }

    fun save(onSaved: () -> Unit) = viewModelScope.launch {
        val e = entry()
        val r = result ?: repo.triage(e)
        // Keep the AI's (never lower) urgency in the history when there was one.
        repo.logSymptoms(e, ai?.let { r.copy(urgency = it.urgency) } ?: r)
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
fun SymptomLogScreen(petId: Long, onDone: () -> Unit, onBookVet: (String) -> Unit, onPlans: () -> Unit) {
    val vm = appViewModel { SymptomLogViewModel(it, petId) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.attachPhoto(uri) }

    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, vm.photo) {
        value = vm.photo?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }) } }.getOrNull()
            }
        }
    }

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

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                thumb?.let { Image(it.asImageBitmap(), "Attached photo", contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))) }
                OutlinedButton(onClick = { takePhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Filled.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text(if (vm.photo == null) "Add a photo" else "Change photo")
                }
                if (vm.photo != null) TextButton(onClick = { vm.attachPhoto(null) }) { Text("Remove") }
            }

            Button(onClick = { vm.assess() }, enabled = vm.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("How urgent is this?") }
            FilledTonalButton(
                onClick = { vm.runAi(ctx) },
                enabled = (vm.selected.isNotEmpty() || vm.photo != null) && !vm.aiBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vm.aiBusy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Checking…") }
                else { Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Check with AI" + if (vm.plan == Plan.FREE) " (Premium)" else "") }
            }
            vm.aiMessage?.let { (text, upgrade) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text, Modifier.weight(1f))
                        if (upgrade) TextButton(onClick = onPlans) { Text("See plans") }
                    }
                }
            }

            vm.ai?.let { a ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✨ Reviewed by AI" + if (a.raisedByAi) ", which raised the urgency" else "", style = MaterialTheme.typography.labelLarge)
                        UrgencyBadge(a.urgency)
                        Text(a.urgency.headline, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = urgencyColor(a.urgency))
                        a.reasons.forEach { Text("• $it") }
                        if (a.photoFindings.isNotEmpty()) {
                            Text("From the photo", style = MaterialTheme.typography.titleSmall)
                            a.photoFindings.forEach { Text("• $it") }
                        }
                        if (a.watchFor.isNotEmpty()) {
                            Text("Watch for", style = MaterialTheme.typography.titleSmall)
                            a.watchFor.forEach { Text("• $it") }
                        }
                        a.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(a.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (a.urgency == Urgency.EMERGENCY) Button(
                            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=emergency+vet"))) },
                            colors = ButtonDefaults.buttonColors(containerColor = urgencyColor(Urgency.EMERGENCY)),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Find the nearest emergency vet") }
                    }
                }
            }

            vm.result?.takeIf { vm.ai == null }?.let { r ->
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
