package com.petpulse.core.integrations.pharmacy

import com.petpulse.core.integrations.LinkedAccount
import com.petpulse.core.integrations.ProviderInfo
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

data class Prescription(
    val id: String,
    val petName: String,
    val drugName: String,
    val strength: String,
    val directions: String,
    val prescribingClinic: String,
    val refillsRemaining: Int,
    val expiresOn: LocalDate,
    val quantityPerFill: Int,
)

enum class OrderState { SUBMITTED, AWAITING_VET_APPROVAL, APPROVED, SHIPPED, DELIVERED, DECLINED, CANCELLED }

data class RefillOrder(
    val id: String,
    val prescriptionId: String,
    val quantity: Int,
    val state: OrderState,
    val placedAt: Instant,
    val trackingUrl: String? = null,
    val estimatedDelivery: LocalDate? = null,
    val message: String? = null,
)

data class CatalogItem(val sku: String, val name: String, val priceCents: Int, val requiresPrescription: Boolean)

/**
 * A pet pharmacy partner. Real implementations wrap a partner API behind OAuth account linking;
 * see docs/INTEGRATIONS.md for candidates.
 */
interface PharmacyProvider {
    val info: ProviderInfo

    /** Exchanges an OAuth authorization code (or demo login) for a linked account. */
    @Throws(Exception::class)
    suspend fun link(authCode: String): LinkedAccount

    @Throws(Exception::class)

    suspend fun prescriptions(account: LinkedAccount): List<Prescription>

    /**
     * Requests a refill. Most pharmacies route this to the prescribing vet for approval, so the
     * returned order usually starts in [OrderState.AWAITING_VET_APPROVAL].
     */
    @Throws(Exception::class)
    suspend fun requestRefill(account: LinkedAccount, prescriptionId: String, quantity: Int? = null): RefillOrder

    @Throws(Exception::class)

    suspend fun order(account: LinkedAccount, orderId: String): RefillOrder

    @Throws(Exception::class)

    suspend fun searchCatalog(query: String): List<CatalogItem>
}
