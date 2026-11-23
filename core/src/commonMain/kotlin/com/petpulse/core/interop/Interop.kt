package com.petpulse.core.interop

import com.petpulse.core.integrations.IntegrationRegistry
import com.petpulse.core.integrations.pharmacy.MockPharmacyProvider
import com.petpulse.core.integrations.vet.MockVetClinicProvider
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.time.Duration.Companion.hours

/**
 * Small, Swift-friendly entry points. Kotlin default arguments and value classes don't export
 * nicely to Swift, so the iPhone app goes through these instead of raw constructors.
 */
object Dates {
    private val zone get() = TimeZone.currentSystemDefault()

    fun today(): LocalDate = Clock.System.todayIn(zone)
    fun now(): Instant = Clock.System.now()
    fun nowLocal(): LocalDateTime = Clock.System.now().toLocalDateTime(zone)

    fun date(year: Int, month: Int, day: Int): LocalDate = LocalDate(year, month, day)
    fun time(hour: Int, minute: Int): LocalTime = LocalTime(hour, minute)
    fun dateTime(date: LocalDate, time: LocalTime): LocalDateTime = LocalDateTime(date, time)

    fun fromEpochDays(days: Int): LocalDate = LocalDate.fromEpochDays(days)
    fun epochDays(date: LocalDate): Int = date.toEpochDays()
    fun plusDays(date: LocalDate, days: Int): LocalDate = date.plus(days, DateTimeUnit.DAY)

    fun instant(epochMillis: Long): Instant = Instant.fromEpochMilliseconds(epochMillis)
    fun epochMillis(instant: Instant): Long = instant.toEpochMilliseconds()
    fun hoursAgo(hours: Int): Instant = Clock.System.now() - hours.hours

    /** Local wall-clock time to epoch millis (for scheduling notifications). */
    fun epochMillis(dateTime: LocalDateTime): Long = dateTime.toInstant(zone).toEpochMilliseconds()
    fun local(instant: Instant): LocalDateTime = instant.toLocalDateTime(zone)
}

/** The partner set both apps start with. Replace with real providers as deals land. */
object Partners {
    fun demo(): IntegrationRegistry = IntegrationRegistry(
        pharmacies = listOf(MockPharmacyProvider()),
        clinics = listOf(MockVetClinicProvider()),
    )
}

/** Swift entry points for billing and AI triage. */
object Billing {
    fun demo(): com.petpulse.core.billing.BillingProvider = com.petpulse.core.billing.MockBillingProvider()
    fun demoPrints(): com.petpulse.core.billing.PrintPartner = com.petpulse.core.billing.MockPrintPartner()
    fun limits(planName: String): com.petpulse.core.billing.PlanLimits =
        (com.petpulse.core.billing.Plan.entries.firstOrNull { it.name == planName } ?: com.petpulse.core.billing.Plan.FREE).limits
}
