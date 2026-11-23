package com.petpulse.core.triage

import com.petpulse.core.model.Pet
import com.petpulse.core.model.SymptomEntry

enum class Urgency(val headline: String) {
    EMERGENCY("Go to an emergency vet now"),
    SEE_VET_24H("Call your vet today"),
    BOOK_VISIT("Book a vet visit this week"),
    MONITOR("Monitor at home"),
}

data class TriageResult(
    val urgency: Urgency,
    /** Plain-language reasons the owner sees, most important first. */
    val reasons: List<String>,
    /** Things to watch that would raise urgency. */
    val watchFor: List<String>,
    val disclaimer: String = DISCLAIMER,
) {
    companion object {
        const val DISCLAIMER =
            "CuteFamilyPet gives general guidance, not a diagnosis. If you are worried, call your vet. " +
                "In an emergency, go to the nearest emergency clinic."
    }
}

/** Context the engine can use beyond the current entry. */
data class TriageContext(
    val pet: Pet,
    val ageYears: Double?,
    /** Recent entries for the same pet, newest first, excluding the one being triaged. */
    val recentEntries: List<SymptomEntry> = emptyList(),
    /** Percent weight change over the last ~30 days, if known. */
    val recentWeightChangePercent: Double? = null,
)

/**
 * Decides how urgent a logged problem is. [RuleBasedTriageEngine] ships offline; an LLM-backed
 * engine can implement this too, and should fall back to the rules (and never rate below them).
 */
interface TriageEngine {
    suspend fun assess(entry: SymptomEntry, context: TriageContext): TriageResult
}
