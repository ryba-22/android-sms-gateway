package me.capcom.smsgateway.modules.messages.policy

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A half-open permitted interval, anchored to its starting local weekday. */
data class WeeklyAllowedWindow(
    val day: DayOfWeek,
    val start: LocalTime,
    val endExclusive: LocalTime,
) {
    init {
        require(start != endExclusive) { "Zero-length weekly window is ambiguous" }
    }

    val crossesMidnight: Boolean get() = endExclusive < start
}

data class BlackoutInterval(
    val start: Instant,
    val endExclusive: Instant,
) {
    init {
        require(endExclusive > start) { "Blackout interval must have positive duration" }
    }
}

/**
 * The phone owns the enforcement policy. A missing/invalid policy never means "allow".
 * An explicit IANA zone avoids silently changing the meaning of a local time window.
 */
data class SendingPolicy(
    val version: Long,
    val timezone: String,
    val weeklyAllowedWindows: List<WeeklyAllowedWindow>,
    val blackoutDates: Set<LocalDate> = emptySet(),
    val blackoutIntervals: List<BlackoutInterval> = emptyList(),
    val paused: Boolean = false,
)

enum class DenialReason {
    MISSING_POLICY,
    INVALID_POLICY,
    PAUSED,
    BLACKOUT_DATE,
    BLACKOUT_INTERVAL,
    OUTSIDE_ALLOWED_WINDOW,
}

data class SendingDecision(
    val allowed: Boolean,
    val policyVersion: Long?,
    val checkedAt: Instant,
    val reason: DenialReason? = null,
)

/**
 * Pure, deterministic time-policy evaluator. It does not enqueue, send, or mark an SMS failed.
 *
 * DST semantics: the same local clock interval applies to both occurrences of an overlapping
 * hour; a non-existent spring-forward local time is never used as a physical instant.
 */
class SendingPolicyEvaluator(private val clock: Clock = Clock.systemUTC()) {

    fun evaluate(policy: SendingPolicy?, at: Instant = clock.instant()): SendingDecision {
        if (policy == null) {
            return denied(at, null, DenialReason.MISSING_POLICY)
        }
        val zone = zoneOrNull(policy)
            ?: return denied(at, policy.version, DenialReason.INVALID_POLICY)
        if (policy.paused) return denied(at, policy.version, DenialReason.PAUSED)

        val local = at.atZone(zone)
        if (local.toLocalDate() in policy.blackoutDates) {
            return denied(at, policy.version, DenialReason.BLACKOUT_DATE)
        }
        if (policy.blackoutIntervals.any { at >= it.start && at < it.endExclusive }) {
            return denied(at, policy.version, DenialReason.BLACKOUT_INTERVAL)
        }
        if (!withinWeeklyWindow(policy.weeklyAllowedWindows, local)) {
            return denied(at, policy.version, DenialReason.OUTSIDE_ALLOWED_WINDOW)
        }
        return SendingDecision(true, policy.version, at)
    }

    /**
     * Find the next permitted instant from boundaries of allowed windows and blackout endings.
     * Returns null for a paused/invalid policy or for no allowed instant in the search horizon.
     * This is for scheduling worker wake-ups, not permission to skip checking when it wakes.
     */
    fun nextAllowedAt(
        policy: SendingPolicy?,
        from: Instant = clock.instant(),
        horizonDays: Long = 21,
    ): Instant? {
        if (policy == null || policy.paused || horizonDays !in 1L..60L) return null
        val zone = zoneOrNull(policy) ?: return null
        if (evaluate(policy, from).allowed) return from
        val localStart = from.atZone(zone).toLocalDate()
        val candidates = mutableSetOf<Instant>()
        for (dayOffset in -1L..horizonDays) {
            val date = localStart.plusDays(dayOffset)
            for (window in policy.weeklyAllowedWindows) {
                if (date.dayOfWeek != window.day) continue
                val localStartTime = LocalDateTime.of(date, window.start)
                val offsets = zone.rules.getValidOffsets(localStartTime)
                if (offsets.isEmpty()) {
                    // Spring-forward gap. atZone shifts to the first real instant afterwards.
                    candidates += localStartTime.atZone(zone).toInstant()
                } else {
                    // Fall-back overlap: both physical occurrences must be considered.
                    for (offset in offsets) {
                        candidates += ZonedDateTime.ofLocal(localStartTime, zone, offset).toInstant()
                    }
                }
            }
        }
        for (blackout in policy.blackoutIntervals) {
            candidates += blackout.endExclusive
        }
        for (blackoutDate in policy.blackoutDates) {
            candidates += blackoutDate.plusDays(1).atStartOfDay(zone).toInstant()
        }
        val until = from.atZone(zone).plusDays(horizonDays).toInstant()
        return candidates.asSequence()
            .filter { it > from && it <= until }
            .sorted()
            .firstOrNull { evaluate(policy, it).allowed }
    }

    private fun zoneOrNull(policy: SendingPolicy): ZoneId? {
        if (policy.version <= 0 || policy.timezone.isBlank()) return null
        return try {
            ZoneId.of(policy.timezone)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun withinWeeklyWindow(
        windows: List<WeeklyAllowedWindow>,
        at: ZonedDateTime,
    ): Boolean {
        val time = at.toLocalTime()
        val weekday = at.dayOfWeek
        val previousWeekday = weekday.minus(1)
        return windows.any { window ->
            when {
                window.day == weekday && !window.crossesMidnight ->
                    time >= window.start && time < window.endExclusive
                window.day == weekday && window.crossesMidnight ->
                    time >= window.start
                window.day == previousWeekday && window.crossesMidnight ->
                    time < window.endExclusive
                else -> false
            }
        }
    }

    private fun denied(
        at: Instant,
        version: Long?,
        reason: DenialReason,
    ) = SendingDecision(false, version, at, reason)
}
