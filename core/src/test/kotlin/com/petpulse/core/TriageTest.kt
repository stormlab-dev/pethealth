package com.petpulse.core

import com.petpulse.core.model.*
import com.petpulse.core.triage.RuleBasedTriageEngine
import com.petpulse.core.triage.TriageContext
import com.petpulse.core.triage.Urgency
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TriageTest {
    private val engine = RuleBasedTriageEngine()
    private val now = Instant.parse("2026-11-22T12:00:00Z")
    private val dog = Pet(1, "Rex", Species.DOG, sex = Sex.MALE)
    private val maleCat = Pet(2, "Milo", Species.CAT, sex = Sex.MALE)
    private val femaleCat = Pet(3, "Luna", Species.CAT, sex = Sex.FEMALE)

    private fun entry(vararg s: Symptom, severity: Severity = Severity.MILD, hoursAgo: Long = 2, petId: Long = 1) =
        SymptomEntry(petId = petId, symptoms = s.toSet(), severity = severity, startedAt = now.minus(Duration.ofHours(hoursAgo)), loggedAt = now)

    private fun assess(pet: Pet, e: SymptomEntry, age: Double? = 4.0, recent: List<SymptomEntry> = emptyList(), weight: Double? = null) =
        engine.assessNow(e, TriageContext(pet, age, recent, weight))

    @Test fun breathingIsEmergency() = assertEquals(Urgency.EMERGENCY, assess(dog, entry(Symptom.DIFFICULTY_BREATHING)).urgency)

    @Test fun dogRetchingIsBloatEmergency() {
        val r = assess(dog, entry(Symptom.UNPRODUCTIVE_RETCHING))
        assertEquals(Urgency.EMERGENCY, r.urgency)
        assertTrue(r.reasons.first().contains("bloat"))
    }

    @Test fun maleCatStrainingIsEmergencyButFemaleIsSameDay() {
        assertEquals(Urgency.EMERGENCY, assess(maleCat, entry(Symptom.STRAINING_TO_URINATE)).urgency)
        assertEquals(Urgency.SEE_VET_24H, assess(femaleCat, entry(Symptom.STRAINING_TO_URINATE)).urgency)
    }

    @Test fun briefMildVomitingInAdultIsMonitor() {
        val r = assess(dog, entry(Symptom.VOMITING))
        assertEquals(Urgency.MONITOR, r.urgency)
        assertTrue(r.watchFor.isNotEmpty())
    }

    @Test fun vomitingInPuppyIsSameDay() = assertEquals(Urgency.SEE_VET_24H, assess(dog, entry(Symptom.VOMITING), age = 0.3).urgency)

    @Test fun vomitingOverADayIsSameDay() = assertEquals(Urgency.SEE_VET_24H, assess(dog, entry(Symptom.VOMITING, hoursAgo = 30)).urgency)

    @Test fun catNotEatingADay() = assertEquals(Urgency.SEE_VET_24H, assess(femaleCat, entry(Symptom.NOT_EATING, hoursAgo = 26)).urgency)

    @Test fun rabbitNotEatingIsEmergency() =
        assertEquals(Urgency.EMERGENCY, assess(Pet(4, "Bun", Species.RABBIT), entry(Symptom.NOT_EATING)).urgency)

    @Test fun thirstIsBookVisit() = assertEquals(Urgency.BOOK_VISIT, assess(dog, entry(Symptom.DRINKING_MORE)).urgency)

    @Test fun recurringItchingIsBookVisit() {
        val past = listOf(entry(Symptom.SNEEZING), entry(Symptom.SNEEZING))
        val r = assess(dog, entry(Symptom.SNEEZING), recent = past)
        assertEquals(Urgency.BOOK_VISIT, r.urgency)
        assertTrue(r.reasons.any { "keeps coming back" in it })
    }

    @Test fun bigWeightChangeIsBookVisit() = assertEquals(Urgency.BOOK_VISIT, assess(dog, entry(Symptom.SNEEZING), weight = -12.0).urgency)

    @Test fun highestUrgencyWinsAndReasonsAreOrdered() {
        val r = assess(dog, entry(Symptom.ITCHING, Symptom.SEIZURE))
        assertEquals(Urgency.EMERGENCY, r.urgency)
        assertTrue(r.reasons.first().contains("Seizure"))
        assertTrue(r.watchFor.isEmpty())
    }
}
