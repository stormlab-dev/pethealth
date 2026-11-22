package com.petpulse.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.petpulse.app.AppContainer
import com.petpulse.app.data.AppointmentEntity
import com.petpulse.app.data.ClinicLinkEntity
import com.petpulse.app.ui.components.*
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.ui.theme.urgencyColor
import com.petpulse.core.health.*
import com.petpulse.core.model.*
import com.petpulse.core.triage.Urgency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class PetDetailViewModel(private val c: AppContainer, val petId: Long) : ViewModel() {
    private val repo = c.repository
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val pet = repo.pet(petId).state(null)
    val symptoms = repo.symptoms(petId).state(emptyList())
    val weights = repo.weights(petId).state(emptyList())
    val vaccinations = repo.vaccinations(petId).state(emptyList())
    val medications = repo.medications(petId).state(emptyList())
    val clinicLink = c.db.integrations().observeClinicLink(petId).state(null)
    val appointments = c.db.integrations().observeAppointments(petId).state(emptyList())

    fun addWeight(kg: Double, date: LocalDate) = viewModelScope.launch { repo.addWeight(WeightEntry(petId = petId, kilograms = kg, date = date)) }
    fun deleteWeight(id: Long) = viewModelScope.launch { repo.deleteWeight(id) }
    fun addVaccination(v: Vaccination) = viewModelScope.launch { repo.addVaccination(v.copy(petId = petId)) }
    fun deleteVaccination(id: Long) = viewModelScope.launch { repo.deleteVaccination(id) }
    fun deleteSymptom(id: Long) = viewModelScope.launch { repo.deleteSymptom(id) }

    fun markGiven(med: Medication) = viewModelScope.launch {
        val now = Instant.now()
        repo.recordDose(med.id, now, taken = true)
    }
}

private enum class Tab(val label: String) { OVERVIEW("Overview"), SYMPTOMS("Symptoms"), WEIGHT("Weight"), VACCINES("Vaccines"), MEDS("Meds"), VET("Vet") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PetDetailScreen(
    petId: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onLogSymptoms: () -> Unit,
    onEditMed: (Long?) -> Unit,
    onPharmacy: (Long?) -> Unit,
    onVet: (String) -> Unit,
) {
    val vm = appViewModel { PetDetailViewModel(it, petId) }
    val pet by vm.pet.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.OVERVIEW) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pet?.let { "${speciesEmoji(it.species)} ${it.name}" } ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit pet") } },
            )
        },
        floatingActionButton = {
            when (tab) {
                Tab.OVERVIEW, Tab.SYMPTOMS -> ExtendedFloatingActionButton(onClick = onLogSymptoms, icon = { Icon(Icons.Filled.MedicalServices, null) }, text = { Text("Something's wrong") })
                Tab.MEDS -> FloatingActionButton(onClick = { onEditMed(null) }) { Icon(Icons.Filled.Add, "Add medication") }
                else -> {}
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                Tab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
            }
            val p = pet ?: return@Column
            when (tab) {
                Tab.OVERVIEW -> OverviewTab(vm, p, onLogSymptoms, onVet)
                Tab.SYMPTOMS -> SymptomsTab(vm)
                Tab.WEIGHT -> WeightTab(vm)
                Tab.VACCINES -> VaccinesTab(vm)
                Tab.MEDS -> MedsTab(vm, onEditMed, onPharmacy)
                Tab.VET -> VetTab(vm, onVet)
            }
        }
    }
}

@Composable
private fun TabList(content: LazyListScope.() -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

// ---------------------------------------------------------------- Overview

@Composable
private fun OverviewTab(vm: PetDetailViewModel, pet: Pet, onLogSymptoms: () -> Unit, onVet: (String) -> Unit) {
    val meds by vm.medications.collectAsStateWithLifecycle()
    val vaccines by vm.vaccinations.collectAsStateWithLifecycle()
    val weights by vm.weights.collectAsStateWithLifecycle()
    val symptoms by vm.symptoms.collectAsStateWithLifecycle()
    val appts by vm.appointments.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val now = LocalDateTime.now()

    TabList {
        item {
            SectionCard("Upcoming doses") {
                val upcoming = meds.mapNotNull { m -> DoseScheduler.nextDose(m, now)?.let { m to it } }.sortedBy { it.second }.take(4)
                if (upcoming.isEmpty()) Text("No scheduled medications.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                upcoming.forEach { (m, at) -> Text("${at.format(dateTimeFormat)}  ·  ${m.name} ${m.dosage}") }
            }
        }
        item {
            val due = VaccineTracker.latestByName(vaccines).filter { VaccineTracker.status(it, today) in setOf(VaccineStatus.DUE_SOON, VaccineStatus.OVERDUE) }
            SectionCard("Vaccines") {
                if (vaccines.isEmpty()) Text("No vaccines logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (due.isEmpty()) Text("All up to date.")
                due.forEach { v -> VaccineLine(v, today) }
            }
        }
        item {
            SectionCard("Weight") {
                val t = WeightAnalyzer.trend(weights)
                if (t == null) Text(weights.lastOrNull()?.let { "%.1f kg".format(it.kilograms) } ?: "Log weight regularly to spot changes early.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("%.1f kg  (%+.1f kg, %+.0f%% over %d days)".format(t.latestKg, t.changeKg, t.changePercent, t.overDays))
            }
        }
        item {
            SectionCard("Recent symptoms") {
                val last = symptoms.firstOrNull()
                if (last == null) Text("Nothing logged. That's good news!", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text(last.first.symptoms.joinToString { it.label })
                    last.second?.let { runCatching { Urgency.valueOf(it) }.getOrNull() }?.let { UrgencyBadge(it) }
                }
            }
        }
        val upcomingAppts = appts.filter { it.status != "CANCELLED" && LocalDateTime.parse(it.start).isAfter(now) }
        if (upcomingAppts.isNotEmpty()) item {
            SectionCard("Appointments") { upcomingAppts.forEach { AppointmentLine(it) } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLogSymptoms, Modifier.weight(1f)) { Text("Check symptoms") }
                OutlinedButton(onClick = { onVet("") }, Modifier.weight(1f)) { Text("Book a vet") }
            }
        }
    }
}

// ---------------------------------------------------------------- Symptoms

@Composable
private fun SymptomsTab(vm: PetDetailViewModel) {
    val entries by vm.symptoms.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return EmptyState("Log symptoms when something seems off. PetPulse will tell you how urgent it looks, and the history helps your vet.")
    TabList {
        items(entries, key = { it.first.id }) { (e, urgency) ->
            SectionCard(
                e.loggedAt.atZone(ZoneId.systemDefault()).format(dateTimeFormat),
                action = { IconButton(onClick = { vm.deleteSymptom(e.id) }) { Icon(Icons.Filled.Delete, "Delete") } },
            ) {
                Text(e.symptoms.joinToString { it.label })
                Text("Severity: ${e.severity.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                e.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                urgency?.let { runCatching { Urgency.valueOf(it) }.getOrNull() }?.let { UrgencyBadge(it) }
            }
        }
    }
}

// ---------------------------------------------------------------- Weight

@Composable
private fun WeightTab(vm: PetDetailViewModel) {
    val weights by vm.weights.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    TabList {
        item {
            SectionCard("Weight history", action = { TextButton(onClick = { adding = true }) { Text("Add") } }) {
                if (weights.size >= 2) WeightChart(weights, Modifier.fillMaxWidth().height(160.dp))
                WeightAnalyzer.trend(weights)?.let {
                    val warn = kotlin.math.abs(it.changePercent) >= 10
                    Text(
                        "%+.1f kg (%+.0f%%) over %d days".format(it.changeKg, it.changePercent, it.overDays) + if (warn) ". Worth mentioning to your vet." else "",
                        color = if (warn) urgencyColor(Urgency.BOOK_VISIT) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (weights.isEmpty()) Text("No weights yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(weights.reversed(), key = { it.id }) { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.date.format(dateFormat), Modifier.weight(1f))
                Text("%.2f kg".format(w.kilograms))
                IconButton(onClick = { vm.deleteWeight(w.id) }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
    if (adding) WeightDialog(onDismiss = { adding = false }, onSave = { kg, d -> vm.addWeight(kg, d) })
}

@Composable
private fun WeightChart(weights: List<WeightEntry>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val sorted = weights.sortedBy { it.date }
        val minD = sorted.first().date.toEpochDay().toFloat()
        val spanD = (sorted.last().date.toEpochDay() - sorted.first().date.toEpochDay()).coerceAtLeast(1).toFloat()
        val minW = sorted.minOf { it.kilograms }.toFloat()
        val spanW = (sorted.maxOf { it.kilograms }.toFloat() - minW).coerceAtLeast(0.5f)
        fun pt(w: WeightEntry) = Offset(
            (w.date.toEpochDay() - minD) / spanD * size.width,
            size.height - ((w.kilograms.toFloat() - minW) / spanW * size.height * 0.8f + size.height * 0.1f),
        )
        for (i in 0..3) drawLine(grid, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1f)
        sorted.zipWithNext().forEach { (a, b) -> drawLine(color, pt(a), pt(b), strokeWidth = 6f, cap = StrokeCap.Round) }
        sorted.forEach { drawCircle(color, 8f, pt(it)) }
    }
}

@Composable
private fun WeightDialog(onDismiss: () -> Unit, onSave: (Double, LocalDate) -> Unit) {
    var text by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    val kg = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log weight") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Weight (kg)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                DateField("Date", date, { date = it })
            }
        },
        confirmButton = { TextButton(enabled = kg != null, onClick = { onSave(kg!!, date); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Vaccines

private val commonVaccines = listOf("Rabies" to 36, "DHPP" to 12, "Bordetella" to 12, "Leptospirosis" to 12, "Lyme" to 12, "Canine influenza" to 12, "FVRCP" to 12, "FeLV" to 12)

@Composable
private fun VaccineLine(v: Vaccination, today: LocalDate) {
    val status = VaccineTracker.status(v, today)
    val (label, color) = when (status) {
        VaccineStatus.OVERDUE -> "Overdue" to urgencyColor(Urgency.SEE_VET_24H)
        VaccineStatus.DUE_SOON -> "Due soon" to urgencyColor(Urgency.BOOK_VISIT)
        VaccineStatus.CURRENT -> "Current" to urgencyColor(Urgency.MONITOR)
        VaccineStatus.NO_BOOSTER -> "No booster" to Color.Gray
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(v.name)
            Text(
                "Given ${v.administeredOn.format(dateFormat)}" + (v.dueOn?.let { " · due ${it.format(dateFormat)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(label, color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaccinesTab(vm: PetDetailViewModel) {
    val all by vm.vaccinations.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    TabList {
        item {
            SectionCard("Current status", action = { TextButton(onClick = { adding = true }) { Text("Add") } }) {
                val latest = VaccineTracker.latestByName(all)
                if (latest.isEmpty()) Text("Add vaccines from your pet's records, or import them from a linked clinic on the Vet tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                latest.forEach { VaccineLine(it, today) }
            }
        }
        if (all.isNotEmpty()) item { Text("All doses", style = MaterialTheme.typography.titleSmall) }
        items(all, key = { it.id }) { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.name)
                    Text(listOfNotNull(v.administeredOn.format(dateFormat), v.clinicName, v.sourceRecordId?.let { "imported" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.deleteVaccination(v.id) }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var months by remember { mutableStateOf("12") }
        var date by remember { mutableStateOf(LocalDate.now()) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add vaccine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        commonVaccines.forEach { (n, m) -> SuggestionChip(onClick = { name = n; months = m.toString() }, label = { Text(n) }) }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Vaccine") }, singleLine = true)
                    DateField("Date given", date, { date = it })
                    OutlinedTextField(months, { months = it.filter(Char::isDigit) }, label = { Text("Booster every (months, blank = none)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.addVaccination(Vaccination(petId = vm.petId, name = name, administeredOn = date, validForMonths = months.toIntOrNull()))
                    adding = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Meds

@Composable
private fun MedsTab(vm: PetDetailViewModel, onEditMed: (Long?) -> Unit, onPharmacy: (Long?) -> Unit) {
    val meds by vm.medications.collectAsStateWithLifecycle()
    val now = LocalDateTime.now()
    val today = LocalDate.now()
    TabList {
        if (meds.isEmpty()) item { EmptyState("Add medications to get dose reminders and refill alerts.") }
        items(meds, key = { it.id }) { m ->
            SectionCard(m.name, action = { IconButton(onClick = { onEditMed(m.id) }) { Icon(Icons.Filled.Edit, "Edit") } }) {
                Text(m.dosage)
                Text(scheduleLabel(m.schedule), style = MaterialTheme.typography.bodySmall)
                DoseScheduler.nextDose(m, now)?.let { Text("Next: ${it.format(dateTimeFormat)}" + if (!m.remindersEnabled) " (reminders off)" else "") }
                RefillPredictor.forecast(m, today)?.let { f ->
                    Text(
                        "About ${f.daysLeft} days left · runs out ${f.runsOutOn.format(dateFormat)}",
                        color = if (f.shouldReorder) urgencyColor(Urgency.SEE_VET_24H) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.schedule !is DoseSchedule.AsNeeded || m.unitsRemaining != null) {
                        FilledTonalButton(onClick = { vm.markGiven(m) }) { Text("Mark given") }
                    }
                    OutlinedButton(onClick = { onPharmacy(m.id) }) { Text(if (m.pharmacyPrescriptionId != null) "Refill" else "Link pharmacy") }
                }
            }
        }
        item { TextButton(onClick = { onPharmacy(null) }) { Icon(Icons.Filled.LocalPharmacy, null); Spacer(Modifier.width(8.dp)); Text("Pharmacy orders") } }
    }
}

fun scheduleLabel(s: DoseSchedule): String = when (s) {
    is DoseSchedule.Daily -> when (s.times.size) {
        1 -> "Once a day at ${s.times[0].format(timeFormat)}"
        else -> "${s.times.size}× a day: " + s.times.sorted().joinToString { it.format(timeFormat) }
    }
    is DoseSchedule.EveryNDays -> if (s.intervalDays == 30) "Monthly at ${s.time.format(timeFormat)}" else "Every ${s.intervalDays} days at ${s.time.format(timeFormat)}"
    DoseSchedule.AsNeeded -> "As needed"
}

// ---------------------------------------------------------------- Vet

@Composable
fun AppointmentLine(a: AppointmentEntity) {
    Column {
        Text("${LocalDateTime.parse(a.start).format(dateTimeFormat)} · ${a.clinicName}")
        Text("${a.reason} · ${a.status.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VetTab(vm: PetDetailViewModel, onVet: (String) -> Unit) {
    val link: ClinicLinkEntity? by vm.clinicLink.collectAsStateWithLifecycle()
    val appts by vm.appointments.collectAsStateWithLifecycle()
    TabList {
        item {
            SectionCard("Your clinic") {
                val l = link
                if (l == null) Text("Link your vet clinic to import records and book online.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text("${l.clinicName}\nPatient id ${l.patientId}")
                Button(onClick = { onVet("") }) { Text(if (l == null) "Find and link a clinic" else "Records and booking") }
            }
        }
        item {
            SectionCard("Appointments") {
                if (appts.isEmpty()) Text("None yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                appts.sortedByDescending { it.start }.forEach { AppointmentLine(it) }
            }
        }
    }
}
