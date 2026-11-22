package com.petpulse.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petpulse.app.AppContainer
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.ui.components.DateField
import com.petpulse.app.ui.components.showTimePicker
import com.petpulse.core.model.DoseSchedule
import com.petpulse.core.model.Medication
import kotlinx.coroutines.launch
import com.petpulse.app.util.pretty
import com.petpulse.app.util.today
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

enum class ScheduleKind(val label: String) { DAILY("Daily"), INTERVAL("Every N days"), AS_NEEDED("As needed") }

class MedicationEditViewModel(private val c: AppContainer, private val petId: Long, private val medId: Long?) : ViewModel() {
    var name by mutableStateOf("")
    var dosage by mutableStateOf("")
    var kind by mutableStateOf(ScheduleKind.DAILY)
    var times by mutableStateOf(listOf(LocalTime(8, 0)))
    var intervalDays by mutableStateOf("30")
    var startDate by mutableStateOf(today())
    var endDate by mutableStateOf<LocalDate?>(null)
    var unitsRemaining by mutableStateOf("")
    var unitsPerDose by mutableStateOf("1")
    var reminders by mutableStateOf(true)
    private var existing: Medication? = null

    init {
        medId?.let { id ->
            viewModelScope.launch {
                c.repository.getMedication(id)?.let { m ->
                    existing = m
                    name = m.name; dosage = m.dosage; startDate = m.startDate; endDate = m.endDate
                    unitsRemaining = m.unitsRemaining?.let { fmt(it) }.orEmpty(); unitsPerDose = fmt(m.unitsPerDose)
                    reminders = m.remindersEnabled
                    when (val s = m.schedule) {
                        is DoseSchedule.Daily -> { kind = ScheduleKind.DAILY; times = s.times }
                        is DoseSchedule.EveryNDays -> { kind = ScheduleKind.INTERVAL; times = listOf(s.time); intervalDays = s.intervalDays.toString() }
                        DoseSchedule.AsNeeded -> kind = ScheduleKind.AS_NEEDED
                    }
                }
            }
        }
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    val valid get() = name.isNotBlank() && (kind != ScheduleKind.INTERVAL || (intervalDays.toIntOrNull() ?: 0) > 0) &&
        (kind == ScheduleKind.AS_NEEDED || times.isNotEmpty())

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val schedule = when (kind) {
            ScheduleKind.DAILY -> DoseSchedule.Daily(times.distinct().sorted())
            ScheduleKind.INTERVAL -> DoseSchedule.EveryNDays(intervalDays.toInt(), times.first(), startDate)
            ScheduleKind.AS_NEEDED -> DoseSchedule.AsNeeded
        }
        val med = (existing ?: Medication(petId = petId, name = "", dosage = "", schedule = schedule, startDate = startDate)).copy(
            name = name, dosage = dosage, schedule = schedule, startDate = startDate, endDate = endDate,
            unitsRemaining = unitsRemaining.replace(',', '.').toDoubleOrNull(),
            unitsPerDose = unitsPerDose.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
            remindersEnabled = reminders,
        )
        val id = c.repository.saveMedication(med)
        c.reminders.schedule(med.copy(id = id))
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        medId?.let { c.reminders.cancel(it); c.repository.deleteMedication(it) }
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MedicationEditScreen(petId: Long, medId: Long?, onDone: () -> Unit) {
    val vm = appViewModel { MedicationEditViewModel(it, petId, medId) }
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (medId == null) "Add medication" else "Edit medication") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (medId != null) IconButton(onClick = { vm.delete(onDone) }) { Icon(Icons.Filled.Delete, "Delete") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(vm.name, { vm.name = it }, label = { Text("Medication") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(vm.dosage, { vm.dosage = it }, label = { Text("Dose, e.g. 1 tablet with food") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Schedule", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ScheduleKind.entries.forEachIndexed { i, k ->
                    SegmentedButton(selected = vm.kind == k, onClick = {
                        vm.kind = k
                        if (k == ScheduleKind.INTERVAL) vm.times = vm.times.take(1).ifEmpty { listOf(LocalTime(9, 0)) }
                    }, shape = SegmentedButtonDefaults.itemShape(i, ScheduleKind.entries.size)) { Text(k.label) }
                }
            }
            if (vm.kind == ScheduleKind.INTERVAL) {
                OutlinedTextField(vm.intervalDays, { vm.intervalDays = it.filter(Char::isDigit) }, label = { Text("Every how many days? (30 = monthly)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
            if (vm.kind != ScheduleKind.AS_NEEDED) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    vm.times.forEachIndexed { i, t ->
                        InputChip(
                            selected = false,
                            onClick = { showTimePicker(ctx, t) { picked -> vm.times = vm.times.toMutableList().also { it[i] = picked } } },
                            label = { Text(t.pretty()) },
                        )
                        if (vm.times.size > 1) TextButton(onClick = { vm.times = vm.times.filterIndexed { j, _ -> j != i } }) { Text("Remove") }
                    }
                    if (vm.kind == ScheduleKind.DAILY) AssistChip(onClick = {
                        showTimePicker(ctx, LocalTime(20, 0)) { picked -> vm.times = (vm.times + picked).distinct().sorted() }
                    }, label = { Text("+ Add time") })
                }
            }
            DateField("Start date", vm.startDate, { vm.startDate = it }, maxToday = false)
            DateField("End date (optional)", vm.endDate, { vm.endDate = it }, maxToday = false)
            if (vm.endDate != null) TextButton(onClick = { vm.endDate = null }) { Text("Clear end date") }
            Text("Refill tracking (optional)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.unitsRemaining, { vm.unitsRemaining = it }, label = { Text("On hand") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(vm.unitsPerDose, { vm.unitsPerDose = it }, label = { Text("Per dose") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Dose reminders", Modifier.weight(1f))
                Switch(vm.reminders, { vm.reminders = it })
            }
            Button(onClick = { vm.save(onDone) }, enabled = vm.valid, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
}
