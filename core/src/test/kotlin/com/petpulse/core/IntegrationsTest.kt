package com.petpulse.core

import com.petpulse.core.integrations.IntegrationException
import com.petpulse.core.integrations.pharmacy.MockPharmacyProvider
import com.petpulse.core.integrations.pharmacy.OrderState
import com.petpulse.core.integrations.vet.*
import com.petpulse.core.model.Vaccination
import kotlinx.coroutines.test.runTest
import java.time.*
import kotlin.test.*

class MutableClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone() = zone
    override fun withZone(zone: ZoneId) = MutableClock(now, zone)
    override fun instant() = now
}

class IntegrationsTest {
    private val clock = MutableClock(Instant.parse("2026-11-23T08:00:00Z")) // a Monday

    @Test fun refillLifecycle() = runTest {
        val p = MockPharmacyProvider(clock, latencyMs = 0, stepDuration = Duration.ofMinutes(10))
        val acct = p.link("code")
        val rx = p.prescriptions(acct).first { it.id == "rx-101" }
        val order = p.requestRefill(acct, rx.id)
        assertEquals(OrderState.AWAITING_VET_APPROVAL, order.state)
        assertEquals(rx.refillsRemaining - 1, p.prescriptions(acct).first { it.id == rx.id }.refillsRemaining)
        clock.now = clock.now.plus(Duration.ofMinutes(25))
        val shipped = p.order(acct, order.id)
        assertEquals(OrderState.SHIPPED, shipped.state)
        assertNotNull(shipped.trackingUrl)
    }

    @Test fun noRefillsAsksVetToRenew() = runTest {
        val p = MockPharmacyProvider(clock, latencyMs = 0)
        val acct = p.link("code")
        val order = p.requestRefill(acct, "rx-104")
        assertTrue(order.message!!.contains("renew"))
    }

    @Test fun clinicLinkRecordsAndImportDedupes() = runTest {
        val v = MockVetClinicProvider(clock, latencyMs = 0)
        assertFailsWith<IntegrationException.Rejected> { v.linkPatient("clinic-1", "555", "Rex", "000000") }
        val link = v.linkPatient("clinic-1", "555", "Rex", MockVetClinicProvider.DEMO_CODE)
        val record = v.records(link)
        val first = RecordImporter.plan(7, "Demo Animal Hospital", record, emptyList(), emptyList())
        assertEquals(3, first.newVaccinations.size)
        assertEquals(36, first.newVaccinations.first { it.name == "Rabies" }.validForMonths)
        val second = RecordImporter.plan(7, "Demo Animal Hospital", record, first.newVaccinations.mapIndexed { i, it -> it.copy(id = i + 1L) }, first.newWeights)
        assertTrue(second.isEmpty)
        // A dose the owner typed in by hand isn't duplicated either.
        val manual = Vaccination(petId = 7, name = "rabies", administeredOn = record.vaccinations.first().administeredOn, validForMonths = 36)
        assertEquals(2, RecordImporter.plan(7, "x", record, listOf(manual), emptyList()).newVaccinations.size)
    }

    @Test fun bookingRemovesSlotAndRejectsDoubleBooking() = runTest {
        val v = MockVetClinicProvider(clock, latencyMs = 0)
        val slots = v.availableSlots("clinic-1", LocalDate.of(2026, 11, 23), days = 2)
        assertTrue(slots.isNotEmpty())
        assertTrue(slots.all { it.start.isAfter(LocalDateTime.of(2026, 11, 23, 8, 0)) })
        val appt = v.book(slots.first(), null, "Rex", "Limping", triageSummary = "Limping 2 days")
        assertEquals(AppointmentStatus.REQUESTED, appt.status)
        assertFalse(v.availableSlots("clinic-1", LocalDate.of(2026, 11, 23), days = 2).any { it.start == appt.start })
        assertFailsWith<IntegrationException.Rejected> { v.book(slots.first(), null, "Rex", "again") }
        assertTrue(v.availableSlots("clinic-3", LocalDate.of(2026, 11, 23)).isEmpty())
        assertEquals("clinic-3", v.searchClinics(ClinicQuery(emergencyOnly = true)).single().id)
    }
}
