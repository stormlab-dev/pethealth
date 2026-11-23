package com.petpulse.core.integrations

/** Describes a partner so the UI can show it and gate features it supports. */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val capabilities: Set<Capability>,
    /** True for the built-in demo providers. Shown as a "Demo" badge in the UI. */
    val isDemo: Boolean = false,
)

enum class Capability {
    // Pharmacy
    PRESCRIPTION_SYNC, REFILL_ORDERING, ORDER_TRACKING, AUTOSHIP,
    // Clinic
    CLINIC_SEARCH, RECORDS_IMPORT, ONLINE_BOOKING, SEND_TRIAGE_SUMMARY,
}

/** Account the owner has connected (OAuth token, member id, etc.). Secrets live in secure storage, not here. */
data class LinkedAccount(val providerId: String, val accountId: String, val displayName: String)

sealed class IntegrationException(message: String) : Exception(message) {
    class NotLinked(providerId: String) : IntegrationException("No account linked for $providerId")
    class NotFound(what: String) : IntegrationException("$what not found")
    class Rejected(reason: String) : IntegrationException(reason)
    class Unavailable(reason: String) : IntegrationException(reason)
}
