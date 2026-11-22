package com.petpulse.core.integrations.vet

import com.petpulse.core.integrations.Capability
import com.petpulse.core.integrations.IntegrationException
import com.petpulse.core.integrations.ProviderInfo
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * In-memory clinic network. Verification code for linking is always [DEMO_CODE].
 * Not thread-safe; call it from one dispatcher (the UI's), as the app does.
 */
class MockVetClinicProvider(
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
    private val latencyMs: Long = 400,
) : VetClinicProvider {

    override val info = ProviderInfo(
        id = "mock-clinics",
        displayName = "Demo Clinic Network",
        capabilities = setOf(Capability.CLINIC_SEARCH, Capability.RECORDS_IMPORT, Capability.ONLINE_BOOKING, Capability.SEND_TRIAGE_SUMMARY),
        isDemo = true,
    )

    private val clinics = listOf(
        Clinic("clinic-1", "Demo Animal Hospital", "100 Main St", "+1 555 0100", 37.7749, -122.4194, isEmergency24h = false, acceptsOnlineBooking = true),
        Clinic("clinic-2", "Demo Cat Clinic", "22 Whisker Ave", "+1 555 0122", 37.7849, -122.4094, isEmergency24h = false, acceptsOnlineBooking = true),
        Clinic("clinic-3", "Demo 24/7 Emergency Vets", "9 Harbor Rd", "+1 555 0911", 37.7649, -122.4294, isEmergency24h = true, acceptsOnlineBooking = false),
    )

    private val booked = mutableMapOf<String, Appointment>()
    private var seq = 500
    private val today get() = clock.todayIn(timeZone)
    private fun monthsAgo(n: Int) = today.minus(n, DateTimeUnit.MONTH)

    override suspend fun searchClinics(query: ClinicQuery): List<Clinic> {
        delay(latencyMs)
        var result = clinics.filter { !query.emergencyOnly || it.isEmergency24h }
        query.text?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()?.let { t ->
            result = result.filter { t in it.name.lowercase() || t in it.address.lowercase() }
        }
        val lat = query.latitude
        val lon = query.longitude
        if (lat != null && lon != null) result = result.sortedBy { distanceKm(lat, lon, it.latitude, it.longitude) }
        return result
    }

    override suspend fun linkPatient(clinicId: String, ownerPhone: String, petName: String, verificationCode: String): PatientLink {
        delay(latencyMs)
        clinic(clinicId)
        if (verificationCode != DEMO_CODE) throw IntegrationException.Rejected("That code didn't match. (Demo code: $DEMO_CODE)")
        return PatientLink(clinicId, "pt-${(clinicId + petName.lowercase()).hashCode().toUInt()}", petName)
    }

    override suspend fun records(link: PatientLink): MedicalRecord {
        delay(latencyMs)
        clinic(link.clinicId)
        val p = link.patientId
        val lastYear = monthsAgo(11)
        return MedicalRecord(
            patient = link,
            vaccinations = listOf(
                RecordVaccination("$p-v1", "Rabies", lastYear, lastYear.plus(3, DateTimeUnit.YEAR)),
                RecordVaccination("$p-v2", "DHPP / FVRCP", lastYear, lastYear.plus(1, DateTimeUnit.YEAR)),
                RecordVaccination("$p-v3", "Leptospirosis / FeLV", monthsAgo(13), monthsAgo(1)),
            ),
            weights = listOf(
                RecordWeight("$p-w1", 12.4, lastYear),
                RecordWeight("$p-w2", 12.9, monthsAgo(5)),
            ),
            visits = listOf(
                RecordVisit("$p-x1", lastYear, "Annual wellness exam", "Healthy. Mild dental tartar; recheck in 12 months.", "Dr. Demo"),
                RecordVisit("$p-x2", monthsAgo(5), "Itchy skin", "Suspected allergic dermatitis. Started on Apoquel.", "Dr. Demo"),
            ),
            prescriptions = listOf(
                RecordPrescription("$p-r1", "Apoquel 16 mg", "1 tablet once daily", monthsAgo(5)),
            ),
            fetchedAt = clock.now(),
        )
    }

    override suspend fun availableSlots(clinicId: String, from: LocalDate, days: Int): List<AppointmentSlot> {
        delay(latencyMs)
        val c = clinic(clinicId)
        if (!c.acceptsOnlineBooking) return emptyList()
        val taken = booked.values.filter { it.clinicId == clinicId && it.status != AppointmentStatus.CANCELLED }.map { it.start }.toSet()
        val now = clock.now().toLocalDateTime(timeZone)
        return (0 until days).map { from.plus(it, DateTimeUnit.DAY) }
            .filter { it.dayOfWeek != DayOfWeek.SUNDAY }
            .flatMap { day ->
                (9 until 17).flatMap { h -> listOf(0, 30).map { m -> LocalDateTime(day, LocalTime(h, m)) } }
                    // Deterministic "busy" pattern so some slots look taken.
                    .filter { (it.hour * 7 + it.minute + it.dayOfMonth) % 3 != 0 }
            }
            .filter { it > now && it !in taken }
            .map { AppointmentSlot(clinicId, it, 30, if (it.hour < 13) "Dr. Demo" else "Dr. Sample") }
    }

    override suspend fun book(slot: AppointmentSlot, link: PatientLink?, petName: String, reason: String, triageSummary: String?): Appointment {
        delay(latencyMs)
        clinic(slot.clinicId)
        val clash = booked.values.any { it.clinicId == slot.clinicId && it.start == slot.start && it.status != AppointmentStatus.CANCELLED }
        if (clash) throw IntegrationException.Rejected("Sorry, that time was just taken. Please pick another.")
        val appt = Appointment(
            id = "appt-${++seq}",
            clinicId = slot.clinicId,
            patientId = link?.patientId,
            start = slot.start,
            minutes = slot.minutes,
            reason = reason,
            // New clients need the clinic to confirm; linked patients are confirmed immediately.
            status = if (link != null) AppointmentStatus.CONFIRMED else AppointmentStatus.REQUESTED,
        )
        booked[appt.id] = appt
        return appt
    }

    override suspend fun cancel(appointmentId: String): Appointment {
        delay(latencyMs)
        val a = booked[appointmentId] ?: throw IntegrationException.NotFound("Appointment $appointmentId")
        return a.copy(status = AppointmentStatus.CANCELLED).also { booked[appointmentId] = it }
    }

    private fun clinic(id: String) = clinics.firstOrNull { it.id == id } ?: throw IntegrationException.NotFound("Clinic $id")

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        // Equirectangular approximation; plenty for sorting nearby clinics.
        val rad = PI / 180
        val x = (lon2 - lon1) * rad * cos((lat1 + lat2) / 2 * rad)
        val y = (lat2 - lat1) * rad
        return sqrt(x * x + y * y) * 6371
    }

    companion object {
        const val DEMO_CODE = "123456"
    }
}
