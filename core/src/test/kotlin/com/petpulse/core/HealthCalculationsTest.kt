package com.petpulse.core

import com.petpulse.core.health.*
import com.petpulse.core.model.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.*

class HealthCalculationsTest {
    private val start = LocalDate.of(2026, 11, 1)
    private fun med(schedule: DoseSchedule, end: LocalDate? = null, units: Double? = null, perDose: Double = 1.0) =
        Medication(1, 1, "Med", "1 tab", schedule, start, end, units, perDose)

    @Test fun dailyNextDoseSameDayAndRollover() {
        val m = med(DoseSchedule.Daily(listOf(LocalTime.of(20, 0), LocalTime.of(8, 0))))
        assertEquals(LocalDateTime.of(2026, 11, 22, 20, 0), DoseScheduler.nextDose(m, LocalDateTime.of(2026, 11, 22, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 11, 23, 8, 0), DoseScheduler.nextDose(m, LocalDateTime.of(2026, 11, 22, 20, 0)))
    }

    @Test fun dailyDoesNotStartBeforeStartDate() {
        val m = med(DoseSchedule.Daily(listOf(LocalTime.of(8, 0))))
        assertEquals(LocalDateTime.of(2026, 11, 1, 8, 0), DoseScheduler.nextDose(m, LocalDateTime.of(2026, 10, 20, 12, 0)))
    }

    @Test fun everyNDays() {
        val m = med(DoseSchedule.EveryNDays(30, LocalTime.of(9, 0), start))
        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 0), DoseScheduler.nextDose(m, LocalDateTime.of(2026, 11, 1, 9, 0)))
        assertEquals(LocalDateTime.of(2026, 11, 1, 9, 0), DoseScheduler.nextDose(m, LocalDateTime.of(2026, 10, 1, 0, 0)))
        assertEquals(LocalDateTime.of(2027, 1, 30, 9, 0), DoseScheduler.nextDose(m, LocalDateTime.of(2026, 12, 31, 10, 0)))
    }

    @Test fun courseEndStopsReminders() {
        val m = med(DoseSchedule.Daily(listOf(LocalTime.of(8, 0))), end = LocalDate.of(2026, 11, 22))
        assertNull(DoseScheduler.nextDose(m, LocalDateTime.of(2026, 11, 22, 9, 0)))
        assertNull(DoseScheduler.nextDose(med(DoseSchedule.AsNeeded), LocalDateTime.now()))
    }

    @Test fun refillForecast() {
        val m = med(DoseSchedule.Daily(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))), units = 10.0)
        val f = RefillPredictor.forecast(m, LocalDate.of(2026, 11, 22))!!
        assertEquals(5, f.daysLeft)
        assertTrue(f.shouldReorder)
        assertFalse(RefillPredictor.forecast(m.copy(unitsRemaining = 60.0), LocalDate.of(2026, 11, 22))!!.shouldReorder)
        // Course ends before the bottle runs out: no reorder nag.
        assertFalse(RefillPredictor.forecast(m.copy(endDate = LocalDate.of(2026, 11, 24)), LocalDate.of(2026, 11, 22))!!.shouldReorder)
        assertNull(RefillPredictor.forecast(m.copy(unitsRemaining = null), LocalDate.of(2026, 11, 22)))
    }

    @Test fun vaccineStatusAndLatest() {
        val today = LocalDate.of(2026, 11, 22)
        val old = Vaccination(1, 1, "Rabies", LocalDate.of(2023, 1, 1), 12)
        val new = Vaccination(2, 1, "rabies ", LocalDate.of(2026, 1, 1), 12)
        val soon = Vaccination(3, 1, "DHPP", LocalDate.of(2025, 12, 10), 12)
        assertEquals(VaccineStatus.OVERDUE, VaccineTracker.status(old, today))
        assertEquals(VaccineStatus.CURRENT, VaccineTracker.status(new, today))
        assertEquals(VaccineStatus.DUE_SOON, VaccineTracker.status(soon, today))
        assertEquals(VaccineStatus.NO_BOOSTER, VaccineTracker.status(old.copy(validForMonths = null), today))
        assertEquals(listOf(3L, 2L), VaccineTracker.latestByName(listOf(old, new, soon)).map { it.id })
    }

    @Test fun weightTrend() {
        val w = listOf(
            WeightEntry(1, 1, 10.0, LocalDate.of(2026, 9, 1)),
            WeightEntry(2, 1, 10.0, LocalDate.of(2026, 10, 20)),
            WeightEntry(3, 1, 8.8, LocalDate.of(2026, 11, 22)),
        )
        val t = WeightAnalyzer.trend(w)!!
        assertEquals(-12.0, t.changePercent, 0.01)
        assertEquals(33, t.overDays)
        assertNull(WeightAnalyzer.trend(w.take(1)))
    }

    @Test fun ageLabel() {
        assertEquals("3 yr 2 mo", PetAge.label(LocalDate.of(2023, 9, 1), LocalDate.of(2026, 11, 22)))
        assertEquals("4 mo", PetAge.label(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 11, 22)))
    }
}
