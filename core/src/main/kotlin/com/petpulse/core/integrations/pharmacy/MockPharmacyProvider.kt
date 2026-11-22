package com.petpulse.core.integrations.pharmacy

import com.petpulse.core.integrations.Capability
import com.petpulse.core.integrations.IntegrationException
import com.petpulse.core.integrations.LinkedAccount
import com.petpulse.core.integrations.ProviderInfo
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory pharmacy for demos and tests. Orders advance through approval and shipping based on
 * elapsed time, so the UI can exercise every state without a real partner.
 */
class MockPharmacyProvider(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val latencyMs: Long = 400,
    /** How long each order step takes in the simulation. */
    private val stepDuration: Duration = Duration.ofMinutes(2),
) : PharmacyProvider {

    override val info = ProviderInfo(
        id = "mock-pharmacy",
        displayName = "Demo Pet Pharmacy",
        capabilities = setOf(Capability.PRESCRIPTION_SYNC, Capability.REFILL_ORDERING, Capability.ORDER_TRACKING),
        isDemo = true,
    )

    private val orders = ConcurrentHashMap<String, RefillOrder>()
    private val refillsUsed = ConcurrentHashMap<String, Int>()
    private val seq = AtomicInteger(1000)
    private val today get() = LocalDate.now(clock)

    private val rx = listOf(
        Prescription("rx-101", "Any pet", "Carprofen", "75 mg chewable", "1 tablet by mouth every 12 hours with food", "Demo Animal Hospital", 3, today.plusMonths(9), 60),
        Prescription("rx-102", "Any pet", "Apoquel (oclacitinib)", "16 mg", "1 tablet by mouth once daily", "Demo Animal Hospital", 5, today.plusMonths(11), 30),
        Prescription("rx-103", "Any pet", "Heartgard Plus", "Medium dog", "1 chewable monthly", "Demo Animal Hospital", 1, today.plusMonths(6), 6),
        Prescription("rx-104", "Any pet", "Methimazole", "5 mg", "1/2 tablet by mouth twice daily", "Demo Cat Clinic", 0, today.plusMonths(2), 60),
    )

    private val catalog = listOf(
        CatalogItem("sku-flea-1", "Flea & tick topical, 3-pack", 4599, false),
        CatalogItem("sku-dental-1", "Enzymatic dental chews, 30 ct", 2299, false),
        CatalogItem("sku-joint-1", "Joint supplement soft chews, 90 ct", 3499, false),
        CatalogItem("sku-rx-carprofen", "Carprofen 75 mg, 60 ct", 3899, true),
        CatalogItem("sku-rx-apoquel", "Apoquel 16 mg, 30 ct", 8999, true),
    )

    override suspend fun link(authCode: String): LinkedAccount {
        delay(latencyMs)
        if (authCode.isBlank()) throw IntegrationException.Rejected("Sign-in was cancelled")
        return LinkedAccount(info.id, "demo-${authCode.hashCode().toUInt()}", "Demo account")
    }

    override suspend fun prescriptions(account: LinkedAccount): List<Prescription> {
        delay(latencyMs)
        requireLinked(account)
        return rx.map { it.copy(refillsRemaining = it.refillsRemaining - (refillsUsed[it.id] ?: 0)) }
    }

    override suspend fun requestRefill(account: LinkedAccount, prescriptionId: String, quantity: Int?): RefillOrder {
        delay(latencyMs)
        requireLinked(account)
        val p = prescriptions(account).firstOrNull { it.id == prescriptionId }
            ?: throw IntegrationException.NotFound("Prescription $prescriptionId")
        val now = clock.instant()
        // No refills left: the pharmacy asks the vet for a new prescription instead of refusing outright.
        val noRefills = p.refillsRemaining <= 0
        if (p.expiresOn.isBefore(today)) throw IntegrationException.Rejected("This prescription has expired. Ask your vet for a new one.")
        val order = RefillOrder(
            id = "ord-${seq.incrementAndGet()}",
            prescriptionId = p.id,
            quantity = quantity ?: p.quantityPerFill,
            state = OrderState.AWAITING_VET_APPROVAL,
            placedAt = now,
            message = if (noRefills) "No refills left, so we've asked ${p.prescribingClinic} to renew it." else "Sent to ${p.prescribingClinic} for approval.",
        )
        if (!noRefills) refillsUsed.merge(p.id, 1, Int::plus)
        orders[order.id] = order
        return order
    }

    override suspend fun order(account: LinkedAccount, orderId: String): RefillOrder {
        delay(latencyMs)
        requireLinked(account)
        val o = orders[orderId] ?: throw IntegrationException.NotFound("Order $orderId")
        val steps = Duration.between(o.placedAt, clock.instant()).toMillis() / stepDuration.toMillis().coerceAtLeast(1)
        val state = when {
            o.state == OrderState.CANCELLED || o.state == OrderState.DECLINED -> o.state
            steps < 1 -> OrderState.AWAITING_VET_APPROVAL
            steps < 2 -> OrderState.APPROVED
            steps < 3 -> OrderState.SHIPPED
            else -> OrderState.DELIVERED
        }
        val shipped = state == OrderState.SHIPPED || state == OrderState.DELIVERED
        return o.copy(
            state = state,
            trackingUrl = if (shipped) "https://example.com/track/${o.id}" else null,
            estimatedDelivery = o.placedAt.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(4),
        ).also { orders[orderId] = it }
    }

    override suspend fun searchCatalog(query: String): List<CatalogItem> {
        delay(latencyMs)
        val q = query.trim().lowercase()
        return if (q.isEmpty()) catalog else catalog.filter { q in it.name.lowercase() }
    }

    private fun requireLinked(account: LinkedAccount) {
        if (account.providerId != info.id) throw IntegrationException.NotLinked(info.id)
    }
}
