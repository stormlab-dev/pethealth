package com.petpulse.core.health

import com.petpulse.core.model.DoseSchedule
import com.petpulse.core.model.Medication
import com.petpulse.core.model.Vaccination
import com.petpulse.core.model.WeightEntry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.temporal.ChronoUnit

object DoseScheduler {

    /** The next dose strictly after [after], or null if the course has ended / is as-needed. */
    fun nextDose(med: Medication, after: LocalDateTime): LocalDateTime? {
        val candidate = when (val s = med.schedule) {
            is DoseSchedule.AsNeeded -> return null
            is DoseSchedule.Daily -> {
                if (s.times.isEmpty()) return null
                val sorted = s.times.sorted()
                var day = maxOf(after.toLocalDate(), med.startDate)
                var found: LocalDateTime? = null
                while (found == null) {
                    found = sorted.map { day.atTime(it) }.firstOrNull { it.isAfter(after) }
                    day = day.plusDays(1)
                }
                found
            }
            is DoseSchedule.EveryNDays -> {
                require(s.intervalDays > 0) { "intervalDays must be positive" }
                var at = s.startDate.atTime(s.time)
                if (!at.isAfter(after)) {
                    val days = ChronoUnit.DAYS.between(s.startDate, after.toLocalDate())
                    val steps = days / s.intervalDays
                    at = s.startDate.plusDays(steps * s.intervalDays).atTime(s.time)
                    while (!at.isAfter(after)) at = at.plusDays(s.intervalDays.toLong())
                }
                at
            }
        }
        val end = med.endDate
        return if (end != null && candidate.toLocalDate().isAfter(end)) null else candidate
    }

    fun dosesPerDay(schedule: DoseSchedule): Double = when (schedule) {
        is DoseSchedule.Daily -> schedule.times.size.toDouble()
        is DoseSchedule.EveryNDays -> 1.0 / schedule.intervalDays
        DoseSchedule.AsNeeded -> 0.0
    }
}

data class RefillForecast(val daysLeft: Int, val runsOutOn: LocalDate, val shouldReorder: Boolean)

object RefillPredictor {
    /** Default lead time: pharmacies typically need ~5 days to approve and ship. */
    const val DEFAULT_LEAD_DAYS = 7

    fun forecast(med: Medication, today: LocalDate, leadDays: Int = DEFAULT_LEAD_DAYS): RefillForecast? {
        val remaining = med.unitsRemaining ?: return null
        val perDay = DoseScheduler.dosesPerDay(med.schedule) * med.unitsPerDose
        if (perDay <= 0.0) return null
        val days = (remaining / perDay).toInt()
        val runsOut = today.plusDays(days.toLong())
        val courseEndsFirst = med.endDate?.let { !it.isAfter(runsOut) } ?: false
        return RefillForecast(days, runsOut, shouldReorder = !courseEndsFirst && days <= leadDays)
    }
}

enum class VaccineStatus { CURRENT, DUE_SOON, OVERDUE, NO_BOOSTER }

object VaccineTracker {
    const val DUE_SOON_DAYS = 30L

    fun status(v: Vaccination, today: LocalDate): VaccineStatus {
        val due = v.dueOn ?: return VaccineStatus.NO_BOOSTER
        return when {
            due.isBefore(today) -> VaccineStatus.OVERDUE
            !due.isAfter(today.plusDays(DUE_SOON_DAYS)) -> VaccineStatus.DUE_SOON
            else -> VaccineStatus.CURRENT
        }
    }

    /** Most recent dose of each vaccine name; older doses are superseded. */
    fun latestByName(all: List<Vaccination>): List<Vaccination> =
        all.groupBy { it.name.trim().lowercase() }
            .map { (_, doses) -> doses.maxBy { it.administeredOn } }
            .sortedBy { it.dueOn ?: LocalDate.MAX }
}

data class WeightTrend(val latestKg: Double, val changeKg: Double, val changePercent: Double, val overDays: Long)

object WeightAnalyzer {
    /** Compares the latest weight with the closest reading at least [windowDays] earlier (or the oldest). */
    fun trend(entries: List<WeightEntry>, windowDays: Long = 30): WeightTrend? {
        if (entries.size < 2) return null
        val sorted = entries.sortedBy { it.date }
        val latest = sorted.last()
        val cutoff = latest.date.minusDays(windowDays)
        val baseline = sorted.lastOrNull { !it.date.isAfter(cutoff) } ?: sorted.first()
        if (baseline === latest || baseline.kilograms <= 0) return null
        val change = latest.kilograms - baseline.kilograms
        return WeightTrend(
            latestKg = latest.kilograms,
            changeKg = change,
            changePercent = change / baseline.kilograms * 100,
            overDays = ChronoUnit.DAYS.between(baseline.date, latest.date),
        )
    }
}

object PetAge {
    fun years(birthDate: LocalDate?, today: LocalDate): Double? =
        birthDate?.let { ChronoUnit.DAYS.between(it, today) / 365.25 }

    fun label(birthDate: LocalDate?, today: LocalDate): String? {
        val p = Period.between(birthDate ?: return null, today)
        return when {
            p.years > 0 -> "${p.years} yr" + if (p.months > 0) " ${p.months} mo" else ""
            p.months > 0 -> "${p.months} mo"
            else -> "${p.days} days"
        }
    }
}
