package com.petpulse.core.billing

import kotlinx.coroutines.delay

data class ShippingAddress(val name: String, val line1: String, val line2: String?, val city: String, val region: String, val postalCode: String, val country: String)
data class PrintQuote(val productId: String, val itemCents: Int, val shippingCents: Int, val taxCents: Int) { val totalCents get() = itemCents + shippingCents + taxCents }
data class PrintOrder(val id: String, val productId: String, val checkoutUrl: String, val status: String)

/**
 * Print-on-demand partner for photo books, calendars, mugs and ID tags (candidates: Prodigi,
 * Gelato, Printful; see docs/MONETIZATION.md). App stores don't allow their billing for physical
 * goods, so payment goes through a web checkout (Stripe) that the partner fulfils.
 */
interface PrintPartner {
    @Throws(Exception::class) suspend fun quote(productId: String, address: ShippingAddress): PrintQuote
    /** imageUrls: signed links to the photos to print. Returns a checkout page to open in the browser. */
    @Throws(Exception::class) suspend fun createOrder(productId: String, imageUrls: List<String>, address: ShippingAddress): PrintOrder
}

class MockPrintPartner(private val latencyMs: Long = 300) : PrintPartner {
    private var next = 1000
    override suspend fun quote(productId: String, address: ShippingAddress): PrintQuote {
        delay(latencyMs)
        val p = Catalog.byId(productId) ?: throw IllegalArgumentException("Unknown product $productId")
        require(p.kind == ProductKind.PHYSICAL) { "$productId is not a print product" }
        val shipping = if (address.country == "US") 599 else 1499
        return PrintQuote(p.id, p.priceCents, shipping, taxCents = p.priceCents * 8 / 100)
    }
    override suspend fun createOrder(productId: String, imageUrls: List<String>, address: ShippingAddress): PrintOrder {
        require(imageUrls.isNotEmpty()) { "Pick at least one photo" }
        quote(productId, address)
        val id = "demo-print-${next++}"
        return PrintOrder(id, productId, checkoutUrl = "https://example.com/checkout/$id", status = "DEMO")
    }
}
