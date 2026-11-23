package com.petpulse.core.billing

/**
 * CuteFamilyPet plans, store products and the limits each plan unlocks.
 * Keep in step with web/src/lib/plans.ts, backend/supabase/functions/billing-webhook/plans.ts and
 * the quota triggers in backend/supabase/migrations/0002_media_plans_ai.sql.
 */
enum class Plan(val displayName: String, val limits: PlanLimits) {
    FREE("Free", PlanLimits(maxMedia = 200, maxClipSeconds = 15, maxSharedPeople = 1, aiPhotoCheck = false, vetReports = false, ads = true)),
    PREMIUM("Premium", PlanLimits(maxMedia = null, maxClipSeconds = 180, maxSharedPeople = 1, aiPhotoCheck = true, vetReports = true, ads = false)),
    FAMILY("Family", PlanLimits(maxMedia = null, maxClipSeconds = 180, maxSharedPeople = 5, aiPhotoCheck = true, vetReports = true, ads = false)),
}

data class PlanLimits(
    /** Photos and videos across all pets; null means unlimited. */
    val maxMedia: Int?,
    val maxClipSeconds: Int,
    /** People besides the owner who can see a pet. */
    val maxSharedPeople: Int,
    val aiPhotoCheck: Boolean,
    val vetReports: Boolean,
    val ads: Boolean,
)

enum class ProductKind { SUBSCRIPTION, ONE_TIME, PHYSICAL }
enum class Period { MONTHLY, YEARLY }

data class Product(
    /** Same id in Google Play Console, App Store Connect and RevenueCat. */
    val id: String,
    val title: String,
    val description: String,
    val kind: ProductKind,
    /** US list price in cents; the stores localize the real price. */
    val priceCents: Int,
    val plan: Plan? = null,
    val period: Period? = null,
    val trialDays: Int = 0,
) {
    val priceLabel: String get() = "$" + (priceCents / 100) + "." + (priceCents % 100).toString().padStart(2, '0')
}

object Catalog {
    val premiumMonthly = Product("cfp_premium_monthly", "Premium monthly", "Unlimited photos and videos, AI photo checks, vet reports, no ads", ProductKind.SUBSCRIPTION, 399, Plan.PREMIUM, Period.MONTHLY)
    val premiumYearly = Product("cfp_premium_yearly", "Premium yearly", "Save 37%. 7-day free trial", ProductKind.SUBSCRIPTION, 2999, Plan.PREMIUM, Period.YEARLY, trialDays = 7)
    val familyMonthly = Product("cfp_family_monthly", "Family monthly", "Premium for up to 6 people and unlimited pets", ProductKind.SUBSCRIPTION, 599, Plan.FAMILY, Period.MONTHLY)
    val familyYearly = Product("cfp_family_yearly", "Family yearly", "Save 37%. 7-day free trial", ProductKind.SUBSCRIPTION, 4499, Plan.FAMILY, Period.YEARLY, trialDays = 7)

    val stickersSeasonal = Product("cfp_stickers_seasonal", "Seasonal sticker pack", "Frames, stickers and filters for every season", ProductKind.ONE_TIME, 99)
    val stickersBirthday = Product("cfp_stickers_birthday", "Birthday and gotcha-day pack", "Party hats, cakes and confetti", ProductKind.ONE_TIME, 199)
    val reelMonthly = Product("cfp_reel_monthly", "Monthly highlight reel", "An AI-made video of this month's best moments", ProductKind.ONE_TIME, 299)
    val reelYearly = Product("cfp_reel_yearly", "Year in review reel", "Your pet's year, set to music", ProductKind.ONE_TIME, 499)
    val portrait = Product("cfp_portrait", "AI pet portrait", "Cartoon, oil painting or superhero styles", ProductKind.ONE_TIME, 299)
    val memorialAlbum = Product("cfp_memorial_album", "Memorial album", "A lasting tribute for a pet who has passed", ProductKind.ONE_TIME, 499)
    val contestBoost = Product("cfp_contest_boost", "Contest boost", "Feature your Cutest Pet of the Week entry", ProductKind.ONE_TIME, 99)

    // Physical goods can't use app-store billing; they're paid on the web (Stripe) and made by a print partner.
    val photoBook = Product("print_photo_book", "Photo book (20 pages)", "Hardcover, 8×8 in", ProductKind.PHYSICAL, 2999)
    val calendar = Product("print_calendar", "Wall calendar", "12 months of your pet", ProductKind.PHYSICAL, 1999)
    val mug = Product("print_mug", "Photo mug", "11 oz ceramic", ProductKind.PHYSICAL, 1499)
    val idTag = Product("print_id_tag", "Engraved ID tag", "Stainless steel with photo back", ProductKind.PHYSICAL, 999)

    val subscriptions = listOf(premiumMonthly, premiumYearly, familyMonthly, familyYearly)
    val extras = listOf(stickersSeasonal, stickersBirthday, reelMonthly, reelYearly, portrait, memorialAlbum, contestBoost)
    val prints = listOf(photoBook, calendar, mug, idTag)
    val all = subscriptions + extras + prints

    fun byId(id: String): Product? = all.firstOrNull { it.id == id.substringBefore(':') }

    /** Yearly saving versus paying monthly for 12 months, in whole percent. */
    fun yearlySavingsPercent(monthly: Product, yearly: Product): Int =
        (monthly.priceCents * 12 - yearly.priceCents) * 100 / (monthly.priceCents * 12)
}

sealed class Gate {
    data object Allowed : Gate()
    data class NeedsUpgrade(val reason: String, val suggested: Plan) : Gate()
}

/** Client-side checks so the apps can show the paywall before an upload fails on the server. */
object Entitlements {
    fun canAddMedia(plan: Plan, currentCount: Int, isVideo: Boolean, clipSeconds: Int = 0): Gate {
        val l = plan.limits
        if (l.maxMedia != null && currentCount >= l.maxMedia)
            return Gate.NeedsUpgrade("The free plan holds ${l.maxMedia} photos and videos. Premium is unlimited.", Plan.PREMIUM)
        if (isVideo && clipSeconds > l.maxClipSeconds)
            return if (plan == Plan.FREE) Gate.NeedsUpgrade("Free clips can be up to ${l.maxClipSeconds} seconds. Premium allows 3 minutes.", Plan.PREMIUM)
            else Gate.NeedsUpgrade("Clips can be up to ${l.maxClipSeconds / 60} minutes. Trim it and try again.", plan)
        return Gate.Allowed
    }

    fun canUseAiPhotoCheck(plan: Plan): Gate =
        if (plan.limits.aiPhotoCheck) Gate.Allowed
        else Gate.NeedsUpgrade("AI photo checks are part of Premium. The free symptom checker still works.", Plan.PREMIUM)

    fun canShareWith(plan: Plan, alreadySharedWith: Int): Gate =
        if (alreadySharedWith < plan.limits.maxSharedPeople) Gate.Allowed
        else Gate.NeedsUpgrade("Family lets you share with up to 5 more people.", Plan.FAMILY)

    fun canExportVetReport(plan: Plan): Gate =
        if (plan.limits.vetReports) Gate.Allowed else Gate.NeedsUpgrade("Vet-ready PDF reports are part of Premium.", Plan.PREMIUM)
}
