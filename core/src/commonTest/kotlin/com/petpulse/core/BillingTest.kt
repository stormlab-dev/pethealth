package com.petpulse.core

import com.petpulse.core.billing.Catalog
import com.petpulse.core.billing.Entitlements
import com.petpulse.core.billing.Gate
import com.petpulse.core.billing.MockBillingProvider
import com.petpulse.core.billing.MockPrintPartner
import com.petpulse.core.billing.Plan
import com.petpulse.core.billing.PurchaseResult
import com.petpulse.core.billing.ShippingAddress
import com.petpulse.core.triage.AiTriageResult
import com.petpulse.core.triage.TriageResult
import com.petpulse.core.triage.Urgency
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BillingTest {
    @Test fun yearlyPlansSaveAbout37Percent() {
        assertEquals(37, Catalog.yearlySavingsPercent(Catalog.premiumMonthly, Catalog.premiumYearly))
        assertEquals("$29.99", Catalog.premiumYearly.priceLabel)
        assertEquals(Catalog.premiumYearly, Catalog.byId("cfp_premium_yearly:yearly-base"))
    }

    @Test fun freePlanLimits() {
        assertIs<Gate.Allowed>(Entitlements.canAddMedia(Plan.FREE, 199, isVideo = false))
        assertIs<Gate.NeedsUpgrade>(Entitlements.canAddMedia(Plan.FREE, 200, isVideo = false))
        assertIs<Gate.NeedsUpgrade>(Entitlements.canAddMedia(Plan.FREE, 0, isVideo = true, clipSeconds = 16))
        assertIs<Gate.Allowed>(Entitlements.canAddMedia(Plan.PREMIUM, 5000, isVideo = true, clipSeconds = 120))
        assertIs<Gate.NeedsUpgrade>(Entitlements.canUseAiPhotoCheck(Plan.FREE))
        assertEquals(Plan.FAMILY, (Entitlements.canShareWith(Plan.PREMIUM, 1) as Gate.NeedsUpgrade).suggested)
        assertIs<Gate.Allowed>(Entitlements.canShareWith(Plan.FAMILY, 4))
    }

    @Test fun mockPurchasesStartTrialAndOwnExtras() = runTest {
        val billing = MockBillingProvider(latencyMs = 0)
        val sub = billing.purchase("cfp_premium_yearly")
        assertIs<PurchaseResult.Success>(sub)
        assertEquals(Plan.PREMIUM, sub.state.plan)
        assertTrue(sub.state.inTrial)
        billing.purchase("cfp_portrait")
        assertTrue("cfp_portrait" in billing.customer().owned)
        assertIs<PurchaseResult.Failed>(billing.purchase("print_mug"))
    }

    @Test fun printQuote() = runTest {
        val q = MockPrintPartner(0).quote("print_photo_book", ShippingAddress("A", "1 Main", null, "Austin", "TX", "78701", "US"))
        assertEquals(2999 + 599 + 239, q.totalCents)
    }

    @Test fun aiCanRaiseButNeverLowerUrgency() {
        val rule = TriageResult(Urgency.BOOK_VISIT, listOf("Skin"), listOf("Spreading"))
        assertEquals(Urgency.SEE_VET_24H, AiTriageResult.merge(rule, Urgency.SEE_VET_24H, listOf("Hot spot"), listOf("Red patch"), emptyList(), null).urgency)
        val lower = AiTriageResult.merge(rule, Urgency.MONITOR, emptyList(), emptyList(), emptyList(), null)
        assertEquals(Urgency.BOOK_VISIT, lower.urgency)
        assertEquals(false, lower.raisedByAi)
    }
}
