package com.petpulse.app.util

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// The shared core uses kotlinx-datetime (so it also runs on iOS). These helpers keep the
// Android UI code short and use the device locale for display.

val zone: TimeZone get() = TimeZone.currentSystemDefault()
fun today(): LocalDate = Clock.System.todayIn(zone)
fun nowLocal(): LocalDateTime = Clock.System.now().toLocalDateTime(zone)
fun Instant.local(): LocalDateTime = toLocalDateTime(zone)

private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

fun LocalDate.pretty(): String = toJavaLocalDate().format(dateFmt)
fun LocalTime.pretty(): String = toJavaLocalTime().format(timeFmt)
fun LocalDateTime.pretty(): String = toJavaLocalDateTime().format(dateTimeFmt)
fun Instant.pretty(): String = local().pretty()

fun Long.epochDayToDate(): LocalDate = LocalDate.fromEpochDays(toInt())
fun LocalDate.epochDay(): Long = toEpochDays().toLong()
fun Long.millisToInstant(): Instant = Instant.fromEpochMilliseconds(this)
