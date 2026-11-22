package com.petpulse.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.petpulse.app.AppContainer
import com.petpulse.app.data.AppointmentEntity
import com.petpulse.app.data.ClinicLinkEntity
import com.petpulse.app.data.toEntity
import com.petpulse.app.data.toModel
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.ui.components.SectionCard
import com.petpulse.app.ui.components.StatusPill
import com.petpulse.app.ui.components.dateFormat
import com.petpulse.app.ui.components.timeFormat
import com.petpulse.app.ui.theme.urgencyColor
import com.petpulse.core.integrations.vet.*
import com.petpulse.core.triage.Urgency
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class VetViewModel(private val c: AppContainer, private val petId: Long, initialReason: String) : ViewModel() {
    val provider: VetClinicProvider = c.integrations.clinics.first()
    private val dao = c.db.integrations()
    val link = dao.observeClinicLink(petId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val appointments = dao.observeAppointments(petId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var query by mutableStateOf("")
    var clinics by mutableStateOf<List<Clinic>>(emptyList()); private set
    var emergencyClinics by mutableStateOf<List<Clinic>>(emptyList()); private set
    var bookingClinic by mutableStateOf<Clinic?>(null); private set
    var slots by mutableStateOf<List<AppointmentSlot>>(emptyList()); private set
    var record by mutableStateOf<MedicalRecord?>(null); private set
    var pendingImport by mutableStateOf<com.petpulse.core.integrations.vet.ImportPlan?>(null); private set
    var reason by mutableStateOf(initialReason)
    var shareSummary by mutableStateOf(initialReason.isNotBlank())
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    private var petName = ""

    init {
        launchTask {
            petName = c.repository.getPet(petId)?.name.orEmpty()
            clinics = provider.searchClinics(ClinicQuery())
            emergencyClinics = provider.searchClinics(ClinicQuery(emergencyOnly = true))
        }
    }

    private fun launchTask(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { block() } catch (e: Exception) { message = e.message ?: "Something went wrong" } finally { busy = false }
    }

    fun search() = launchTask { clinics = provider.searchClinics(ClinicQuery(text = query)) }

    fun linkClinic(clinic: Clinic, phone: String, code: String) = launchTask {
        val l = provider.linkPatient(clinic.id, phone, petName, code)
        dao.upsertClinicLink(ClinicLinkEntity(petId, provider.info.id, clinic.id, clinic.name, l.patientId, null))
        message = "Linked to ${clinic.name}"
    }

    fun unlink() = launchTask { dao.deleteClinicLink(petId); record = null }

    fun fetchRecords() = launchTask {
        val l = link.value ?: return@launchTask
        val r = provider.records(PatientLink(l.clinicId, l.patientId, petName))
        record = r
        val health = c.db.health()
        pendingImport = RecordImporter.plan(
            petId, l.clinicName, r,
            health.vaccinations(petId).map { it.toModel() },
            health.weights(petId).map { it.toModel() },
        )
        if (pendingImport?.isEmpty == true) { pendingImport = null; message = "Everything is already up to date." }
    }

    fun confirmImport() = launchTask {
        val plan = pendingImport ?: return@launchTask
        c.db.health().insertVaccinations(plan.newVaccinations.map { it.toEntity() })
        c.db.health().insertWeights(plan.newWeights.map { it.toEntity() })
        link.value?.let { dao.upsertClinicLink(it.copy(lastSyncedMillis = System.currentTimeMillis())) }
        message = "Imported ${plan.newVaccinations.size} vaccines and ${plan.newWeights.size} weights."
        pendingImport = null
    }

    fun dismissImport() { pendingImport = null }

    fun startBooking(clinic: Clinic) = launchTask {
        bookingClinic = clinic
        slots = provider.availableSlots(clinic.id, LocalDate.now(), 7)
    }

    fun book(slot: AppointmentSlot, onBooked: () -> Unit) = launchTask {
        val clinic = bookingClinic ?: return@launchTask
        val l = link.value?.takeIf { it.clinicId == clinic.id }?.let { PatientLink(it.clinicId, it.patientId, petName) }
        val appt = provider.book(slot, l, petName, reason.ifBlank { "General visit" }, if (shareSummary) reason else null)
        dao.upsertAppointment(AppointmentEntity(appt.id, petId, provider.info.id, clinic.id, clinic.name, appt.start.toString(), appt.minutes, appt.reason, appt.status.name))
        message = if (appt.status == AppointmentStatus.CONFIRMED) "Booked! See you ${appt.start.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a"))}." else "Request sent. ${clinic.name} will confirm."
        bookingClinic = null
        onBooked()
    }

    fun cancelBooking() { bookingClinic = null; slots = emptyList() }

    fun cancelAppointment(a: AppointmentEntity) = launchTask {
        val updated = provider.cancel(a.externalId)
        dao.upsertAppointment(a.copy(status = updated.status.name))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VetScreen(petId: Long, reason: String, onBack: () -> Unit) {
    val vm = appViewModel { VetViewModel(it, petId, reason) }
    val link by vm.link.collectAsStateWithLifecycle()
    val appts by vm.appointments.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var linking by remember { mutableStateOf<Clinic?>(null) }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.bookingClinic != null) "Book at ${vm.bookingClinic!!.name}" else "Vet") },
                navigationIcon = {
                    IconButton(onClick = { if (vm.bookingClinic != null) vm.cancelBooking() else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val booking = vm.bookingClinic
            if (booking != null) {
                item {
                  Column {
                    OutlinedTextField(vm.reason, { vm.reason = it }, label = { Text("Reason for visit") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(vm.shareSummary, { vm.shareSummary = it })
                        Text("Share this with the clinic ahead of the visit")
                    }
                  }
                }
                if (vm.slots.isEmpty() && !vm.busy) item { Text("No online slots this week. Call ${booking.phone}.") }
                vm.slots.groupBy { it.start.toLocalDate() }.forEach { (day, daySlots) ->
                    item {
                        Column {
                            Text(day.format(dateFormat), style = MaterialTheme.typography.titleSmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                daySlots.forEach { s -> SuggestionChip(onClick = { vm.book(s) {} }, label = { Text(s.start.toLocalTime().format(timeFormat)) }, enabled = !vm.busy) }
                            }
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                val l = link
                SectionCard("Your clinic", action = { if (vm.provider.info.isDemo) StatusPill("Demo", urgencyColor(Urgency.BOOK_VISIT)) }) {
                    if (l == null) Text("Pick your clinic below and verify with the phone number they have on file.")
                    else {
                        Text(l.clinicName)
                        l.lastSyncedMillis?.let { Text("Records last imported ${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(dateFormat)}", style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.fetchRecords() }, enabled = !vm.busy) { Text("Import records") }
                            OutlinedButton(onClick = { vm.clinics.firstOrNull { it.id == l.clinicId }?.let(vm::startBooking) }) { Text("Book") }
                        }
                        TextButton(onClick = { vm.unlink() }) { Text("Unlink") }
                    }
                }
            }
            vm.record?.let { r ->
                item {
                    SectionCard("Visit history") {
                        r.visits.sortedByDescending { it.date }.forEach { v ->
                            Text("${v.date.format(dateFormat)} · ${v.reason}", style = MaterialTheme.typography.titleSmall)
                            Text("${v.summary} (${v.veterinarian})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            val upcoming = appts.filter { it.status != AppointmentStatus.CANCELLED.name }
            if (upcoming.isNotEmpty()) item {
                SectionCard("Appointments") {
                    upcoming.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { AppointmentLine(a) }
                            TextButton(onClick = { vm.cancelAppointment(a) }) { Text("Cancel") }
                        }
                    }
                }
            }
            item {
                SectionCard("Emergency clinics (24/7)") {
                    vm.emergencyClinics.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(c.name); Text(c.address, style = MaterialTheme.typography.bodySmall) }
                            IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}"))) }) { Icon(Icons.Filled.Call, "Call") }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    vm.query, { vm.query = it }, label = { Text("Search clinics") }, singleLine = true,
                    trailingIcon = { TextButton(onClick = { vm.search() }) { Text("Search") } }, modifier = Modifier.fillMaxWidth(),
                )
            }
            items(vm.clinics.filter { !it.isEmergency24h }, key = { it.id }) { c ->
                SectionCard(c.name) {
                    Text("${c.address} · ${c.phone}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (link?.clinicId != c.id) OutlinedButton(onClick = { linking = c }) { Text("This is my vet") }
                        if (c.acceptsOnlineBooking) Button(onClick = { vm.startBooking(c) }) { Text("Book online") }
                    }
                }
            }
        }
    }

    vm.pendingImport?.let { plan ->
        AlertDialog(
            onDismissRequest = vm::dismissImport,
            title = { Text("Import from ${link?.clinicName.orEmpty()}?") },
            text = {
                Column {
                    plan.newVaccinations.forEach { Text("• ${it.name}, ${it.administeredOn.format(dateFormat)}") }
                    plan.newWeights.forEach { Text("• Weight %.1f kg, %s".format(it.kilograms, it.date.format(dateFormat))) }
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport() }) { Text("Import") } },
            dismissButton = { TextButton(onClick = vm::dismissImport) { Text("Not now") } },
        )
    }

    linking?.let { clinic ->
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { linking = null },
            title = { Text("Link ${clinic.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the phone number on your account. The clinic will text you a code. (Demo code: ${MockVetClinicProvider.DEMO_CODE})")
                    OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
            },
            confirmButton = { TextButton(enabled = phone.isNotBlank() && code.length == 6, onClick = { vm.linkClinic(clinic, phone, code); linking = null }) { Text("Link") } },
            dismissButton = { TextButton(onClick = { linking = null }) { Text("Cancel") } },
        )
    }
}
