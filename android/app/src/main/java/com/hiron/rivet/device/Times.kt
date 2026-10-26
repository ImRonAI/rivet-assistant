package com.hiron.rivet.device

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Times the model sends and reads: ISO-8601 in the phone's zone, or epoch milliseconds. */
object Times {
    /** Epoch millis for `2026-10-26T15:00:00-06:00`, `2026-10-26T15:00` (phone's zone), `2026-10-26` or millis. */
    fun parse(value: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val v = value.trim()
        v.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
    }

    fun format(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
