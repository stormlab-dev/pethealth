package com.petpulse.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.petpulse.app.AppContainer
import com.petpulse.app.billing.DemoBillingProvider
import com.petpulse.app.ui.appViewModel
import com.petpulse.app.util.pretty
import com.petpulse.core.billing.Catalog
import com.petpulse.core.billing.Plan
import com.petpulse.core.billing.Product
import com.petpulse.core.billing.PurchaseResult
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class PlansViewModel(private val c: AppContainer) : ViewModel() {
    val state = c.billing.state
    val isDemo = c.billing.provider.isDemo
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    fun buy(p: Product) = viewModelScope.launch {
        busy = true
        message = when (val r = c.billing.purchase(p.id)) {
            is PurchaseResult.Success -> if (isDemo) "Demo purchase: ${p.title} is active. Nothing was charged." else "Thanks! ${p.title} is active."
            PurchaseResult.Cancelled -> null
            is PurchaseResult.Failed -> r.message
        }
        busy = false
    }
    fun restore() = viewModelScope.launch { c.billing.restore(); message = "Purchases restored." }
    fun resetDemo() = viewModelScope.launch { (c.billing.provider as? DemoBillingProvider)?.reset(); c.billing.refresh(); message = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlansScreen(onBack: () -> Unit) {
    val vm = appViewModel { PlansViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Plans and extras") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your plan: ${s.plan.displayName}${if (s.inTrial) " (free trial)" else ""}", style = MaterialTheme.typography.titleMedium)
                    s.expiresAt?.let { Text((if (s.inTrial) "Trial ends " else "Renews or ends ") + it.toLocalDateTime(TimeZone.currentSystemDefault()).date.pretty(), style = MaterialTheme.typography.bodySmall) }
                    if (vm.isDemo) Text("Demo store: purchases are simulated and nothing is charged.", style = MaterialTheme.typography.bodySmall)
                    vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    Row {
                        TextButton(onClick = { vm.restore() }) { Text("Restore purchases") }
                        if (vm.isDemo) TextButton(onClick = { vm.resetDemo() }) { Text("Reset demo") }
                    }
                }
            }
            PlanCard("Premium", listOf("Unlimited photos and videos, clips up to 3 minutes", "AI symptom checker with photo analysis", "Vet-ready health reports", "No ads"),
                listOf(Catalog.premiumYearly, Catalog.premiumMonthly), current = s.plan == Plan.PREMIUM, busy = vm.busy, onBuy = vm::buy)
            PlanCard("Family", listOf("Everything in Premium", "Share with up to 6 people", "Unlimited pets"),
                listOf(Catalog.familyYearly, Catalog.familyMonthly), current = s.plan == Plan.FAMILY, busy = vm.busy, onBuy = vm::buy)

            Text("Extras, bought once", style = MaterialTheme.typography.titleMedium)
            Catalog.extras.forEach { p ->
                ProductRow(p, owned = p.id in s.owned && !p.id.startsWith("cfp_reel") && p.id != "cfp_contest_boost", busy = vm.busy) { vm.buy(p) }
            }
            Text("Prints, shipped to your door", style = MaterialTheme.typography.titleMedium)
            Text("Paid on our website, because app stores don't sell physical goods.", style = MaterialTheme.typography.bodySmall)
            Catalog.prints.forEach { p ->
                ProductRow(p, owned = false, busy = false) { vm.message = "The print shop opens soon. We'll let you know in the app." }
            }
            Text("Subscriptions renew automatically through Google Play until cancelled in Play Store → Payments and subscriptions.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlanCard(name: String, perks: List<String>, options: List<Product>, current: Boolean, busy: Boolean, onBuy: (Product) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), border = if (current) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else CardDefaults.outlinedCardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name + if (current) " · your plan" else "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            perks.forEach { Text("✓ $it") }
            options.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.priceLabel} / ${if (p.period?.name == "YEARLY") "year" else "month"}", fontWeight = FontWeight.SemiBold)
                        Text(p.description, style = MaterialTheme.typography.bodySmall)
                    }
                    if (p.trialDays > 0) Button(onClick = { onBuy(p) }, enabled = !current && !busy) { Text("Try free") }
                    else OutlinedButton(onClick = { onBuy(p) }, enabled = !current && !busy) { Text("Choose") }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(p: Product, owned: Boolean, busy: Boolean, onBuy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(p.title)
            Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (owned) Text("Owned", style = MaterialTheme.typography.labelLarge)
        else OutlinedButton(onClick = onBuy, enabled = !busy) { Text(p.priceLabel) }
    }
}
