package com.petpulse.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.petpulse.app.AppContainer
import com.petpulse.app.data.RefillOrderEntity
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.ui.components.SectionCard
import com.petpulse.app.ui.components.StatusPill
import com.petpulse.app.ui.components.dateFormat
import com.petpulse.app.ui.theme.urgencyColor
import com.petpulse.core.integrations.pharmacy.OrderState
import com.petpulse.core.integrations.pharmacy.PharmacyProvider
import com.petpulse.core.integrations.pharmacy.Prescription
import com.petpulse.core.model.Medication
import com.petpulse.core.triage.Urgency
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PharmacyViewModel(private val c: AppContainer, petId: Long, private val medId: Long?) : ViewModel() {
    // One pharmacy for now; a picker goes here once there are several partners.
    val provider: PharmacyProvider = c.integrations.pharmacies.first()
    val account = c.accounts.accounts
    val orders = c.db.integrations().observeRefillOrders(petId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var medication by mutableStateOf<Medication?>(null); private set
    var prescriptions by mutableStateOf<List<Prescription>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    init {
        viewModelScope.launch {
            medication = medId?.let { c.repository.getMedication(it) }
            if (c.accounts.get(provider.info.id) != null) loadPrescriptions()
        }
    }

    private fun launchTask(block: suspend () -> Unit) = viewModelScope.launch {
        busy = true
        try { block() } catch (e: Exception) { message = e.message ?: "Something went wrong" } finally { busy = false }
    }

    fun link() = launchTask {
        // A real provider opens a Custom Tab for OAuth and returns here with the code.
        c.accounts.save(provider.link("demo-auth-code"))
        loadPrescriptions()
    }

    fun unlink() { c.accounts.remove(provider.info.id); prescriptions = emptyList() }

    private suspend fun loadPrescriptions() {
        val acct = c.accounts.get(provider.info.id) ?: return
        prescriptions = provider.prescriptions(acct)
    }

    fun attach(rx: Prescription) = launchTask {
        val m = medication ?: return@launchTask
        val updated = m.copy(pharmacyPrescriptionId = rx.id, pharmacyProviderId = provider.info.id)
        c.repository.saveMedication(updated)
        medication = updated
        message = "${m.name} is linked to ${rx.drugName}."
    }

    fun refill(rx: Prescription) = launchTask {
        val acct = c.accounts.get(provider.info.id) ?: return@launchTask
        val order = provider.requestRefill(acct, rx.id)
        val targetMed = medication
        if (targetMed != null) {
            c.db.integrations().upsertRefillOrder(
                RefillOrderEntity(order.id, targetMed.id, provider.info.id, rx.id, order.state.name, order.placedAt.toEpochMilli(), order.message, order.trackingUrl),
            )
        }
        message = order.message
        loadPrescriptions()
    }

    fun refreshOrders() = launchTask {
        val acct = c.accounts.get(provider.info.id) ?: return@launchTask
        orders.value.forEach { o ->
            val fresh = provider.order(acct, o.externalId)
            c.db.integrations().upsertRefillOrder(o.copy(state = fresh.state.name, trackingUrl = fresh.trackingUrl, message = fresh.message))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharmacyScreen(petId: Long, medId: Long?, onBack: () -> Unit) {
    val vm = appViewModel { PharmacyViewModel(it, petId, medId) }
    val accounts by vm.account.collectAsStateWithLifecycle()
    val orders by vm.orders.collectAsStateWithLifecycle()
    val linked = accounts[vm.provider.info.id]
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) { vm.message?.let { snackbar.showSnackbar(it); vm.message = null } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pharmacy") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (linked != null) IconButton(onClick = { vm.refreshOrders() }) { Icon(Icons.Filled.Refresh, "Refresh orders") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                SectionCard(vm.provider.info.displayName, action = { if (vm.provider.info.isDemo) StatusPill("Demo", urgencyColor(Urgency.BOOK_VISIT)) }) {
                    if (linked == null) {
                        Text("Connect your pharmacy account to see prescriptions and request refills in one tap.")
                        Button(onClick = { vm.link() }, enabled = !vm.busy) { Text("Connect account") }
                    } else {
                        Text("Connected as ${linked.displayName}")
                        TextButton(onClick = { vm.unlink() }) { Text("Disconnect") }
                    }
                }
            }
            vm.medication?.let { m ->
                item {
                    Text(
                        if (m.pharmacyPrescriptionId == null) "Pick the prescription that matches ${m.name}:" else "${m.name} is linked to prescription ${m.pharmacyPrescriptionId}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (linked != null) {
                item { Text("Prescriptions", style = MaterialTheme.typography.titleMedium) }
                items(vm.prescriptions, key = { it.id }) { rx -> PrescriptionCard(rx, vm) }
            }
            if (orders.isNotEmpty()) {
                item { Text("Orders", style = MaterialTheme.typography.titleMedium) }
                items(orders, key = { it.externalId }) { OrderRow(it) }
            }
        }
    }
}

@Composable
private fun PrescriptionCard(rx: Prescription, vm: PharmacyViewModel) {
    val med = vm.medication
    SectionCard(rx.drugName) {
        Text("${rx.strength} · ${rx.directions}")
        Text(
            "${rx.refillsRemaining} refills left · expires ${rx.expiresOn.format(dateFormat)} · ${rx.prescribingClinic}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refill(rx) }, enabled = !vm.busy && med != null) { Text(if (rx.refillsRemaining > 0) "Request refill" else "Ask vet to renew") }
            if (med != null && med.pharmacyPrescriptionId != rx.id) OutlinedButton(onClick = { vm.attach(rx) }) { Text("Link to ${med.name}") }
        }
        if (med == null) Text("Open this from a medication to order a refill for it.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OrderRow(o: RefillOrderEntity) {
    val state = runCatching { OrderState.valueOf(o.state) }.getOrDefault(OrderState.SUBMITTED)
    val color = when (state) {
        OrderState.DELIVERED, OrderState.SHIPPED -> urgencyColor(Urgency.MONITOR)
        OrderState.DECLINED, OrderState.CANCELLED -> urgencyColor(Urgency.EMERGENCY)
        else -> urgencyColor(Urgency.BOOK_VISIT)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Order ${o.externalId}")
            o.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            o.trackingUrl?.let { Text("Tracking: $it", style = MaterialTheme.typography.bodySmall) }
        }
        StatusPill(state.name.lowercase().replace('_', ' '), color)
    }
}
