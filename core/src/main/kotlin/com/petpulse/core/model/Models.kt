package com.petpulse.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class Species(val label: String) {
    DOG("Dog"), CAT("Cat"), RABBIT("Rabbit"), BIRD("Bird"), REPTILE("Reptile"), OTHER("Other")
}

enum class Sex { MALE, FEMALE, UNKNOWN }

data class Pet(
    val id: Long = 0,
    val name: String,
    val species: Species,
    val breed: String? = null,
    val sex: Sex = Sex.UNKNOWN,
    val neutered: Boolean = false,
    val birthDate: LocalDate? = null,
    val microchipId: String? = null,
    val notes: String? = null,
    /** Clinic linked through a [com.petpulse.core.integrations.vet.VetClinicProvider], if any. */
    val linkedClinicId: String? = null,
)

/** Observable signs an owner can log. Keys are stable so they can be stored. */
enum class Symptom(val key: String, val label: String) {
    VOMITING("vomiting", "Vomiting"),
    DIARRHEA("diarrhea", "Diarrhea"),
    NOT_EATING("not_eating", "Not eating"),
    LETHARGY("lethargy", "Lethargy / low energy"),
    COUGHING("coughing", "Coughing"),
    SNEEZING("sneezing", "Sneezing"),
    LIMPING("limping", "Limping"),
    ITCHING("itching", "Itching / scratching"),
    DRINKING_MORE("drinking_more", "Drinking more than usual"),
    URINATING_MORE("urinating_more", "Urinating more than usual"),
    STRAINING_TO_URINATE("straining_urinate", "Straining to urinate / little or no urine"),
    BLOOD_IN_STOOL("blood_stool", "Blood in stool or vomit"),
    DIFFICULTY_BREATHING("breathing", "Difficulty breathing"),
    PALE_GUMS("pale_gums", "Pale, blue or grey gums"),
    SEIZURE("seizure", "Seizure"),
    COLLAPSE("collapse", "Collapse / can't stand"),
    UNPRODUCTIVE_RETCHING("retching", "Retching without bringing anything up"),
    SWOLLEN_ABDOMEN("swollen_abdomen", "Swollen or hard belly"),
    TOXIN_INGESTION("toxin", "Ate something toxic (chocolate, xylitol, lilies, meds...)"),
    TRAUMA("trauma", "Hit by car / fall / major injury"),
    EYE_PROBLEM("eye", "Eye redness, squinting or discharge"),
    EAR_PROBLEM("ear", "Ear scratching, odor or head shaking"),
    SKIN_LUMP("lump", "New lump or bump"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String): Symptom = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

enum class Severity { MILD, MODERATE, SEVERE }

data class SymptomEntry(
    val id: Long = 0,
    val petId: Long,
    val symptoms: Set<Symptom>,
    val severity: Severity,
    /** When the owner first noticed the problem. */
    val startedAt: Instant,
    val loggedAt: Instant = Instant.now(),
    val notes: String? = null,
)

data class WeightEntry(
    val id: Long = 0,
    val petId: Long,
    val kilograms: Double,
    val date: LocalDate,
)

data class Vaccination(
    val id: Long = 0,
    val petId: Long,
    val name: String,
    val administeredOn: LocalDate,
    /** Null for one-off vaccines. */
    val validForMonths: Int?,
    val clinicName: String? = null,
    /** Set when imported from a clinic's records rather than typed in. */
    val sourceRecordId: String? = null,
) {
    val dueOn: LocalDate? get() = validForMonths?.let { administeredOn.plusMonths(it.toLong()) }
}

/** How often a medication is given. */
sealed interface DoseSchedule {
    /** Same times every day, e.g. 08:00 and 20:00. */
    data class Daily(val times: List<LocalTime>) : DoseSchedule

    /** Once every [intervalDays] days at [time], starting [startDate] (e.g. monthly flea/heartworm). */
    data class EveryNDays(val intervalDays: Int, val time: LocalTime, val startDate: LocalDate) : DoseSchedule

    /** Given as needed; no reminders. */
    data object AsNeeded : DoseSchedule
}

data class Medication(
    val id: Long = 0,
    val petId: Long,
    val name: String,
    val dosage: String,
    val schedule: DoseSchedule,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
    /** Units (tablets, doses, ml) on hand, used to predict refills. Null if not tracked. */
    val unitsRemaining: Double? = null,
    val unitsPerDose: Double = 1.0,
    val remindersEnabled: Boolean = true,
    /** Prescription held at a pharmacy partner, enabling one-tap refills. */
    val pharmacyPrescriptionId: String? = null,
    val pharmacyProviderId: String? = null,
)

data class DoseLog(
    val id: Long = 0,
    val medicationId: Long,
    val scheduledFor: Instant,
    val takenAt: Instant?,
    val skipped: Boolean = false,
)
