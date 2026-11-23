package com.petpulse.core.billing

import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

data class CustomerState(
    val plan: Plan,
    val period: Period? = null,
    val expiresAt: Instant? = null,
    val inTrial: Boolean = false,
    /** One-time products the user owns (stickers, portraits, albums...). */
    val owned: Set<String> = emptySet(),
)

sealed class PurchaseResult {
    data class Success(val state: CustomerState) : PurchaseResult()
    data object Cancelled : PurchaseResult()
    data class Failed(val message: String) : PurchaseResult()
}

/**
 * In-app purchases. The real implementations wrap RevenueCat (Purchases SDK) on Android and iOS,
 * which talks to Google Play Billing and StoreKit; see docs/MONETIZATION.md. The server's view of
 * the plan (entitlements table, written by the billing-webhook function) is the one that unlocks
 * cloud features, so a tampered client can't grant itself Premium.
 */
interface BillingProvider {
    val isDemo: Boolean
    @Throws(Exception::class) suspend fun customer(): CustomerState
    @Throws(Exception::class) suspend fun purchase(productId: String): PurchaseResult
    @Throws(Exception::class) suspend fun restore(): CustomerState
}

/** Store-free billing for demos, previews and tests. Purchases succeed instantly; nothing is charged. */
class MockBillingProvider(
    private val clock: Clock = Clock.System,
    private val latencyMs: Long = 300,
    initial: CustomerState = CustomerState(Plan.FREE),
) : BillingProvider {
    override val isDemo = true
    private var state = initial

    override suspend fun customer(): CustomerState { delay(latencyMs); return state.expireIfDue() }

    override suspend fun purchase(productId: String): PurchaseResult {
        delay(latencyMs)
        val p = Catalog.byId(productId) ?: return PurchaseResult.Failed("Unknown product $productId")
        state = when (p.kind) {
            ProductKind.SUBSCRIPTION -> {
                val trial = p.trialDays > 0 && state.plan == Plan.FREE
                val length = if (trial) p.trialDays.days else if (p.period == Period.YEARLY) 365.days else 30.days
                state.copy(plan = p.plan!!, period = p.period, expiresAt = clock.now() + length, inTrial = trial)
            }
            ProductKind.ONE_TIME -> state.copy(owned = state.owned + p.id)
            ProductKind.PHYSICAL -> return PurchaseResult.Failed("Prints are ordered through the print shop, not the app store.")
        }
        return PurchaseResult.Success(state)
    }

    override suspend fun restore(): CustomerState = customer()

    private fun CustomerState.expireIfDue(): CustomerState =
        if (expiresAt != null && expiresAt < clock.now()) CustomerState(Plan.FREE, owned = owned).also { state = it } else this
}
