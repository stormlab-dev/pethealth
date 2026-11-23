package com.petpulse.core.triage

import com.petpulse.core.model.Severity
import com.petpulse.core.model.Sex
import com.petpulse.core.model.Species
import com.petpulse.core.model.Symptom
import com.petpulse.core.model.Symptom.*
import com.petpulse.core.model.SymptomEntry
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Conservative, offline triage based on widely published veterinary red flags.
 * Every rule that fires adds a reason; the highest urgency wins.
 */
class RuleBasedTriageEngine : TriageEngine {

    override suspend fun assess(entry: SymptomEntry, context: TriageContext): TriageResult = assessNow(entry, context)

    /** Non-suspending variant, handy for previews and tests. */
    fun assessNow(entry: SymptomEntry, context: TriageContext): TriageResult {
        val findings = mutableListOf<Pair<Urgency, String>>()
        val s = entry.symptoms
        val pet = context.pet
        val duration = (entry.loggedAt - entry.startedAt).coerceAtLeast(Duration.ZERO)
        val hours = duration.inWholeHours
        fun add(u: Urgency, why: String) = findings.add(u to why)

        // --- Emergencies -------------------------------------------------------------
        if (DIFFICULTY_BREATHING in s) add(Urgency.EMERGENCY, "Difficulty breathing is always an emergency.")
        if (PALE_GUMS in s) add(Urgency.EMERGENCY, "Pale, blue or grey gums can mean shock or poor oxygen.")
        if (SEIZURE in s) add(Urgency.EMERGENCY, "Seizures need a vet straight away, especially a first seizure.")
        if (COLLAPSE in s) add(Urgency.EMERGENCY, "Collapse or being unable to stand needs immediate care.")
        if (TOXIN_INGESTION in s) add(
            Urgency.EMERGENCY,
            "Possible poisoning: call your vet or a pet poison hotline now, even if your pet seems fine.",
        )
        if (TRAUMA in s) add(Urgency.EMERGENCY, "Major injuries can cause internal bleeding that isn't visible.")
        if (UNPRODUCTIVE_RETCHING in s && pet.species == Species.DOG) add(
            Urgency.EMERGENCY,
            "Retching without vomiting can be a sign of bloat (GDV), which is life-threatening in dogs.",
        )
        if (SWOLLEN_ABDOMEN in s && (UNPRODUCTIVE_RETCHING in s || LETHARGY in s)) add(
            Urgency.EMERGENCY,
            "A swollen belly with retching or weakness needs emergency assessment.",
        )
        if (STRAINING_TO_URINATE in s) {
            if (pet.species == Species.CAT && pet.sex == Sex.MALE) add(
                Urgency.EMERGENCY,
                "Male cats straining to pee may have a urinary blockage, which can be fatal within a day.",
            ) else add(Urgency.SEE_VET_24H, "Straining to urinate should be checked by a vet today.")
        }
        if (NOT_EATING in s && pet.species == Species.RABBIT) add(
            Urgency.EMERGENCY,
            "Rabbits that stop eating can develop GI stasis quickly.",
        )
        if (BLOOD_IN_STOOL in s) {
            if (entry.severity == Severity.SEVERE || LETHARGY in s) add(
                Urgency.EMERGENCY,
                "Large amounts of blood, or blood with weakness, needs immediate care.",
            ) else add(Urgency.SEE_VET_24H, "Blood in stool or vomit should be seen by a vet today.")
        }

        // --- Same day --------------------------------------------------------------
        val young = context.ageYears?.let { it < 0.5 } ?: false
        val senior = context.ageYears?.let { it >= 10 } ?: false
        val giSigns = s.intersect(setOf(VOMITING, DIARRHEA))

        if (VOMITING in s && DIARRHEA in s) add(Urgency.SEE_VET_24H, "Vomiting and diarrhea together can dehydrate pets fast.")
        if (giSigns.isNotEmpty() && hours >= 24) add(Urgency.SEE_VET_24H, "GI signs lasting more than a day need a vet check.")
        if (giSigns.isNotEmpty() && (young || senior)) add(
            Urgency.SEE_VET_24H,
            if (young) "Puppies and kittens dehydrate quickly." else "Senior pets are less able to cope with GI illness.",
        )
        if (NOT_EATING in s && pet.species == Species.CAT && hours >= 24) add(
            Urgency.SEE_VET_24H,
            "Cats that don't eat for a day or more risk liver problems (hepatic lipidosis).",
        )
        if (NOT_EATING in s && hours >= 48) add(Urgency.SEE_VET_24H, "Not eating for two days or more needs a vet check.")
        if (LETHARGY in s && NOT_EATING in s) add(Urgency.SEE_VET_24H, "Low energy combined with not eating is a sign of real illness.")
        if (EYE_PROBLEM in s) add(Urgency.SEE_VET_24H, "Eye problems can get worse quickly and threaten sight.")
        if (entry.severity == Severity.SEVERE) add(Urgency.SEE_VET_24H, "You rated this as severe.")

        // --- This week -------------------------------------------------------------
        if (DRINKING_MORE in s || URINATING_MORE in s) add(
            Urgency.BOOK_VISIT,
            "Drinking or peeing more can point to kidney disease, diabetes or hormone problems; bloodwork helps.",
        )
        if (LIMPING in s) add(
            if (entry.severity == Severity.MILD && hours < 48) Urgency.MONITOR else Urgency.BOOK_VISIT,
            "Limping that is more than mild, or lasts over two days, should be examined.",
        )
        if (COUGHING in s && (hours >= 72 || senior)) add(Urgency.BOOK_VISIT, "A cough lasting several days should be examined.")
        if (ITCHING in s || EAR_PROBLEM in s) add(Urgency.BOOK_VISIT, "Skin and ear problems rarely clear up without treatment.")
        if (SKIN_LUMP in s) add(Urgency.BOOK_VISIT, "New lumps should be checked; a quick needle test can tell what they are.")
        context.recentWeightChangePercent?.let {
            if (abs(it) >= 10) add(
                Urgency.BOOK_VISIT,
                "Weight changed ${abs(it).roundToInt()}% in the last month, which is worth discussing with your vet.",
            )
        }
        val repeats = recurringSymptoms(entry, context.recentEntries)
        if (repeats.isNotEmpty()) add(
            Urgency.BOOK_VISIT,
            "This keeps coming back (${repeats.joinToString { it.label.lowercase() }}), 3+ times in two weeks.",
        )
        if (entry.severity == Severity.MODERATE) add(Urgency.BOOK_VISIT, "You rated this as moderate.")

        if (findings.isEmpty()) add(Urgency.MONITOR, "Nothing here suggests an urgent problem right now.")

        val top = findings.minOf { it.first.ordinal }.let { Urgency.entries[it] }
        val reasons = findings.sortedBy { it.first.ordinal }.map { it.second }.distinct()
        return TriageResult(urgency = top, reasons = reasons, watchFor = watchList(s, top))
    }

    private fun recurringSymptoms(entry: SymptomEntry, recent: List<SymptomEntry>): List<Symptom> {
        val since = entry.loggedAt - 14.days
        val window = recent.filter { it.loggedAt > since }
        return entry.symptoms.filter { sym -> sym != OTHER && window.count { sym in it.symptoms } >= 2 }
    }

    private fun watchList(s: Set<Symptom>, top: Urgency): List<String> {
        if (top == Urgency.EMERGENCY) return emptyList()
        val list = mutableListOf(
            "Trouble breathing, collapse, or pale gums: go to an emergency vet.",
        )
        if (VOMITING in s || DIARRHEA in s) list += "Can't keep water down, blood, or getting weaker: call your vet today."
        if (NOT_EATING in s) list += "Still not eating tomorrow: call your vet."
        if (LIMPING in s) list += "Won't put any weight on the leg, or swelling: book a visit."
        return list
    }
}
