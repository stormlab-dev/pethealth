package com.petpulse.core.integrations.vet

import com.petpulse.core.integrations.ProviderInfo
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

data class Clinic(
    val id: String,
    val name: String,
    val address: String,
    val phone: String,
    val latitude: Double,
    val longitude: Double,
    val isEmergency24h: Boolean,
    val acceptsOnlineBooking: Boolean,
)

data class ClinicQuery(val latitude: Double? = null, val longitude: Double? = null, val text: String? = null, val emergencyOnly: Boolean = false)

/** The clinic's id for a pet, obtained after the owner verifies they are the client. */
data class PatientLink(val clinicId: String, val patientId: String, val petName: String)

data class RecordVaccination(val recordId: String, val name: String, val administeredOn: LocalDate, val nextDueOn: LocalDate?)
data class RecordWeight(val recordId: String, val kilograms: Double, val date: LocalDate)
data class RecordVisit(val recordId: String, val date: LocalDate, val reason: String, val summary: String, val veterinarian: String)
data class RecordPrescription(val recordId: String, val drugName: String, val directions: String, val prescribedOn: LocalDate)

data class MedicalRecord(
    val patient: PatientLink,
    val vaccinations: List<RecordVaccination>,
    val weights: List<RecordWeight>,
    val visits: List<RecordVisit>,
    val prescriptions: List<RecordPrescription>,
    val fetchedAt: Instant,
)

data class AppointmentSlot(val clinicId: String, val start: LocalDateTime, val minutes: Int, val vetName: String?)

enum class AppointmentStatus { REQUESTED, CONFIRMED, CANCELLED }

data class Appointment(
    val id: String,
    val clinicId: String,
    val patientId: String?,
    val start: LocalDateTime,
    val minutes: Int,
    val reason: String,
    val status: AppointmentStatus,
)

/**
 * A vet clinic network or practice-management system (PIMS). One provider can front many clinics.
 */
interface VetClinicProvider {
    val info: ProviderInfo

    @Throws(Exception::class)

    suspend fun searchClinics(query: ClinicQuery): List<Clinic>

    /** Verifies the owner with the clinic (e.g. code sent to the phone on file) and returns the pet's patient id. */
    @Throws(Exception::class)
    suspend fun linkPatient(clinicId: String, ownerPhone: String, petName: String, verificationCode: String): PatientLink

    @Throws(Exception::class)

    suspend fun records(link: PatientLink): MedicalRecord

    @Throws(Exception::class)

    suspend fun availableSlots(clinicId: String, from: LocalDate, days: Int = 7): List<AppointmentSlot>

    /**
     * Books a slot. [triageSummary] lets the owner share their symptom log so the clinic can prepare.
     */
    @Throws(Exception::class)
    suspend fun book(slot: AppointmentSlot, link: PatientLink?, petName: String, reason: String, triageSummary: String? = null): Appointment

    @Throws(Exception::class)

    suspend fun cancel(appointmentId: String): Appointment
}
