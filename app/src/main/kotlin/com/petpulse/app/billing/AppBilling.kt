package com.petpulse.app.billing

import android.app.Activity
import android.content.Context
import com.petpulse.app.BuildConfig
import com.petpulse.core.billing.BillingProvider
import com.petpulse.core.billing.Catalog
import com.petpulse.core.billing.CustomerState
import com.petpulse.core.billing.Period
import com.petpulse.core.billing.Plan
import com.petpulse.core.billing.ProductKind
import com.petpulse.core.billing.PurchaseResult
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.PeriodType
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitCustomerInfo
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.days

/**
 * The app's view of the user's plan. Uses RevenueCat (Google Play Billing) when a RevenueCat key
 * is built in (CFP_REVENUECAT_ANDROID_KEY), otherwise a demo store that charges nothing.
 */
class AppBilling(context: Context) {
    private val appContext = context.applicationContext
    private var activity = WeakReference<Activity>(null)
    fun attach(a: Activity) { activity = WeakReference(a) }

    val provider: BillingProvider =
        if (BuildConfig.REVENUECAT_ANDROID_KEY.isNotEmpty()) RevenueCatBillingProvider(appContext, BuildConfig.REVENUECAT_ANDROID_KEY) { activity.get() }
        else DemoBillingProvider(appContext)

    private val _state = MutableStateFlow(CustomerState(Plan.FREE))
    val state: StateFlow<CustomerState> = _state

    suspend fun refresh() { runCatching { provider.customer() }.onSuccess { _state.value = it } }
    suspend fun purchase(productId: String): PurchaseResult =
        provider.purchase(productId).also { if (it is PurchaseResult.Success) _state.value = it.state }
    suspend fun restore() { runCatching { provider.restore() }.onSuccess { _state.value = it } }

    /** Ties purchases to the cloud account so the server knows the plan. */
    suspend fun logIn(userId: String) {
        (provider as? RevenueCatBillingProvider)?.logIn(userId)?.let { _state.value = it }
    }
}

/** Demo purchases kept on the device. Nothing is charged; good for previews and testing. */
class DemoBillingProvider(context: Context) : BillingProvider {
    override val isDemo = true
    private val prefs = context.getSharedPreferences("demo_billing", Context.MODE_PRIVATE)

    override suspend fun customer(): CustomerState {
        val plan = runCatching { Plan.valueOf(prefs.getString("plan", "FREE")!!) }.getOrDefault(Plan.FREE)
        val expires = prefs.getLong("expires", 0).takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
        val owned = prefs.getStringSet("owned", emptySet())!!.toSet()
        if (expires != null && expires < Clock.System.now()) return CustomerState(Plan.FREE, owned = owned)
        return CustomerState(plan, prefs.getString("period", null)?.let { Period.valueOf(it) }, expires, prefs.getBoolean("trial", false), owned)
    }

    override suspend fun purchase(productId: String): PurchaseResult {
        val p = Catalog.byId(productId) ?: return PurchaseResult.Failed("Unknown product")
        val current = customer()
        when (p.kind) {
            ProductKind.SUBSCRIPTION -> {
                val trial = p.trialDays > 0 && current.plan == Plan.FREE
                val days = if (trial) p.trialDays else if (p.period == Period.YEARLY) 365 else 30
                prefs.edit().putString("plan", p.plan!!.name).putString("period", p.period!!.name).putBoolean("trial", trial)
                    .putLong("expires", (Clock.System.now() + days.days).toEpochMilliseconds()).apply()
            }
            ProductKind.ONE_TIME -> prefs.edit().putStringSet("owned", current.owned + p.id).apply()
            ProductKind.PHYSICAL -> return PurchaseResult.Failed("Prints are ordered from the print shop, not the app store.")
        }
        return PurchaseResult.Success(customer())
    }

    override suspend fun restore() = customer()
    fun reset() = prefs.edit().clear().apply()
}

/** Google Play Billing through RevenueCat. Entitlement ids in RevenueCat: "premium" and "family". */
class RevenueCatBillingProvider(context: Context, apiKey: String, private val activity: () -> Activity?) : BillingProvider {
    override val isDemo = false

    init {
        if (!Purchases.isConfigured) Purchases.configure(PurchasesConfiguration.Builder(context, apiKey).build())
    }

    override suspend fun customer(): CustomerState = Purchases.sharedInstance.awaitCustomerInfo().toState()

    override suspend fun purchase(productId: String): PurchaseResult {
        val act = activity() ?: return PurchaseResult.Failed("Open the app and try again.")
        if (Catalog.byId(productId)?.kind == ProductKind.PHYSICAL) return PurchaseResult.Failed("Prints are ordered from the print shop.")
        return try {
            val product = Purchases.sharedInstance.awaitGetProducts(listOf(productId)).firstOrNull()
                ?: return PurchaseResult.Failed("This item isn't available in your store yet.")
            val result = Purchases.sharedInstance.awaitPurchase(PurchaseParams.Builder(act, product).build())
            PurchaseResult.Success(result.customerInfo.toState())
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled) PurchaseResult.Cancelled else PurchaseResult.Failed(e.message ?: "Purchase failed")
        } catch (e: PurchasesException) {
            PurchaseResult.Failed(e.message ?: "Purchase failed")
        }
    }

    override suspend fun restore(): CustomerState = Purchases.sharedInstance.awaitRestore().toState()

    suspend fun logIn(userId: String): CustomerState? =
        runCatching { Purchases.sharedInstance.awaitLogIn(userId).customerInfo.toState() }.getOrNull()

    private fun CustomerInfo.toState(): CustomerState {
        val active = entitlements.active
        val ent = active["family"] ?: active["premium"]
        val plan = when { active.containsKey("family") -> Plan.FAMILY; active.containsKey("premium") -> Plan.PREMIUM; else -> Plan.FREE }
        val sub = ent?.productIdentifier?.let { Catalog.byId(it) }
        return CustomerState(
            plan = plan,
            period = sub?.period,
            expiresAt = ent?.expirationDate?.time?.let { Instant.fromEpochMilliseconds(it) },
            inTrial = ent?.periodType == PeriodType.TRIAL,
            owned = nonSubscriptionTransactions.map { it.productIdentifier }.toSet(),
        )
    }
}
