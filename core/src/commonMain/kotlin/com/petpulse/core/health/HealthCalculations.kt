package com.petpulse.core.health

import com.petpulse.core.model.DoseSchedule
import com.petpulse.core.model.Medication
import com.petpulse.core.model.Vaccination
import com.petpulse.core.model.WeightEntry
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.daysUntil
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus
import kotlinx.datetime.minus

object DoseScheduler {

    /** The next dose strictly after [after], or null if the course has ended / is as-needed. */
    fun nextDose(med: Medication, after: LocalDateTime): LocalDateTime? {
        val candidate = when (val s = med.schedule) {
            is DoseSchedule.AsNeeded -> return null
            is DoseSchedule.Daily -> {
                if (s.times.isEmpty()) return null
                val sorted = s.times.sorted()
                var day = maxOf(after.date, med.startDate)
                var found: LocalDateTime? = null
                while (found == null) {
                    found = sorted.map { LocalDateTime(day, it) }.firstOrNull { it > after }
                    day = day.plus(1, DateTimeUnit.DAY)
                }
                found
            }
            is DoseSchedule.EveryNDays -> {
                require(s.intervalDays > 0) { "intervalDays must be positive" }
                var at = LocalDateTime(s.startDate, s.time)
                if (at <= after) {
                    val days = s.startDate.daysUntil(after.date)
                    val steps = days / s.intervalDays
                    at = LocalDateTime(s.startDate.plus(steps * s.intervalDays, DateTimeUnit.DAY), s.time)
                    while (at <= after) at = LocalDateTime(at.date.plus(s.intervalDays, DateTimeUnit.DAY), s.time)
                }
                at
            }
        }
        val end = med.endDate
        return if (end != null && candidate.date > end) null else candidate
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
        val runsOut = today.plus(days, DateTimeUnit.DAY)
        val courseEndsFirst = med.endDate?.let { it <= runsOut } ?: false
        return RefillForecast(days, runsOut, shouldReorder = !courseEndsFirst && days <= leadDays)
    }
}

enum class VaccineStatus { CURRENT, DUE_SOON, OVERDUE, NO_BOOSTER }

object VaccineTracker {
    const val DUE_SOON_DAYS = 30

    fun status(v: Vaccination, today: LocalDate): VaccineStatus {
        val due = v.dueOn ?: return VaccineStatus.NO_BOOSTER
        return when {
            due < today -> VaccineStatus.OVERDUE
            due <= today.plus(DUE_SOON_DAYS, DateTimeUnit.DAY) -> VaccineStatus.DUE_SOON
            else -> VaccineStatus.CURRENT
        }
    }

    /** Most recent dose of each vaccine name; older doses are superseded. Soonest due first. */
    fun latestByName(all: List<Vaccination>): List<Vaccination> =
        all.groupBy { it.name.trim().lowercase() }
            .map { (_, doses) -> doses.maxBy { it.administeredOn } }
            .sortedWith(compareBy(nullsLast()) { it.dueOn })
}

data class WeightTrend(val latestKg: Double, val changeKg: Double, val changePercent: Double, val overDays: Int)

object WeightAnalyzer {
    /** Compares the latest weight with the closest reading at least [windowDays] earlier (or the oldest). */
    fun trend(entries: List<WeightEntry>, windowDays: Int = 30): WeightTrend? {
        if (entries.size < 2) return null
        val sorted = entries.sortedBy { it.date }
        val latest = sorted.last()
        val cutoff = latest.date.minus(windowDays, DateTimeUnit.DAY)
        val baseline = sorted.lastOrNull { it.date <= cutoff } ?: sorted.first()
        if (baseline === latest || baseline.kilograms <= 0) return null
        val change = latest.kilograms - baseline.kilograms
        return WeightTrend(
            latestKg = latest.kilograms,
            changeKg = change,
            changePercent = change / baseline.kilograms * 100,
            overDays = baseline.date.daysUntil(latest.date),
        )
    }
}

object PetAge {
    fun years(birthDate: LocalDate?, today: LocalDate): Double? =
        birthDate?.let { it.daysUntil(today) / 365.25 }

    fun label(birthDate: LocalDate?, today: LocalDate): String? {
        val p = (birthDate ?: return null).periodUntil(today)
        return when {
            p.years > 0 -> "${p.years} yr" + if (p.months > 0) " ${p.months} mo" else ""
            p.months > 0 -> "${p.months} mo"
            else -> "${p.days} days"
        }
    }
}
