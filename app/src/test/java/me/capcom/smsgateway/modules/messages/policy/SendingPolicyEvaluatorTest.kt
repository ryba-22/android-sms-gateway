package me.capcom.smsgateway.modules.messages.policy

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SendingPolicyEvaluatorTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    private fun instant(local: String): Instant =
        java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun window(
        day: DayOfWeek = DayOfWeek.MONDAY,
        start: String = "08:00",
        end: String = "21:00",
    ) = WeeklyAllowedWindow(day, LocalTime.parse(start), LocalTime.parse(end))

    private fun policy(
        windows: List<WeeklyAllowedWindow> = listOf(window()),
        dates: Set<LocalDate> = emptySet(),
        intervals: List<BlackoutInterval> = emptyList(),
        pause: Boolean = false,
        zoneName: String = "Europe/Warsaw",
        version: Long = 1,
    ) = SendingPolicy(version, zoneName, windows, dates, intervals, pause)

    private val evaluator = SendingPolicyEvaluator()

    @Test fun startsAtEightAndEndsAtNinePm() {
        val p = policy()
        assertFalse(evaluator.evaluate(p, instant("2026-11-09T07:59:59")).allowed)
        assertTrue(evaluator.evaluate(p, instant("2026-11-09T08:00:00")).allowed)
        assertTrue(evaluator.evaluate(p, instant("2026-11-09T20:59:59")).allowed)
        assertFalse(evaluator.evaluate(p, instant("2026-11-09T21:00:00")).allowed)
    }

    @Test fun weekendIsBlockedUnlessExplicitlyAllowed() {
        val p = policy()
        assertFalse(evaluator.evaluate(p, instant("2026-11-14T12:00:00")).allowed)
        assertTrue(evaluator.evaluate(policy(windows = listOf(window(DayOfWeek.SATURDAY))), instant("2026-11-14T12:00:00")).allowed)
    }

    @Test fun blackoutDateOverridesAllowedWindow() {
        val p = policy(dates = setOf(LocalDate.parse("2026-11-09")))
        val d = evaluator.evaluate(p, instant("2026-11-09T12:00:00"))
        assertFalse(d.allowed)
        assertEquals(DenialReason.BLACKOUT_DATE, d.reason)
    }

    @Test fun blackoutIntervalOverridesAllowedWindowAndIsHalfOpen() {
        val p = policy(intervals = listOf(BlackoutInterval(
            instant("2026-11-09T12:00:00"), instant("2026-11-09T15:00:00")
        )))
        assertFalse(evaluator.evaluate(p, instant("2026-11-09T14:59:59")).allowed)
        assertTrue(evaluator.evaluate(p, instant("2026-11-09T15:00:00")).allowed)
        assertEquals(instant("2026-11-09T15:00:00"),
            evaluator.nextAllowedAt(p, instant("2026-11-09T12:10:00")))
    }

    @Test fun crossingMidnightContinuesIntoFollowingWeekday() {
        val p = policy(windows = listOf(window(start = "22:00", end = "02:00")))
        assertTrue(evaluator.evaluate(p, instant("2026-11-09T22:30:00")).allowed)
        assertTrue(evaluator.evaluate(p, instant("2026-11-10T01:59:59")).allowed)
        assertFalse(evaluator.evaluate(p, instant("2026-11-10T02:00:00")).allowed)
        assertFalse(evaluator.evaluate(p, instant("2026-11-09T21:59:59")).allowed)
    }

    @Test fun blackoutDateStillOverridesCrossingMidnightWindow() {
        val p = policy(
            windows = listOf(window(start = "22:00", end = "02:00")),
            dates = setOf(LocalDate.parse("2026-11-10"))
        )
        assertTrue(evaluator.evaluate(p, instant("2026-11-09T23:59:59")).allowed)
        assertFalse(evaluator.evaluate(p, instant("2026-11-10T00:00:00")).allowed)
    }

    @Test fun pausedOverridesAllOtherRules() {
        val d = evaluator.evaluate(policy(pause = true), instant("2026-11-09T12:00:00"))
        assertEquals(DenialReason.PAUSED, d.reason)
        assertNull(evaluator.nextAllowedAt(policy(pause = true), instant("2026-11-09T12:00:00")))
    }

    @Test fun invalidOrMissingPolicyIsFailClosed() {
        val now = instant("2026-11-09T12:00:00")
        assertEquals(DenialReason.MISSING_POLICY, evaluator.evaluate(null, now).reason)
        assertEquals(DenialReason.INVALID_POLICY, evaluator.evaluate(policy(zoneName = "Not/AZone"), now).reason)
        assertEquals(DenialReason.INVALID_POLICY, evaluator.evaluate(policy(version = 0), now).reason)
        assertFalse(evaluator.evaluate(policy(windows = emptyList()), now).allowed)
    }

    @Test fun findsNextWeekdayWithoutBusyLoop() {
        val p = policy()
        assertEquals(instant("2026-11-16T08:00:00"),
            evaluator.nextAllowedAt(p, instant("2026-11-13T22:00:00")))
    }

    @Test fun blackedOutMondayDefersToNextMonday() {
        val p = policy(dates = setOf(LocalDate.parse("2026-11-16")))
        assertEquals(instant("2026-11-23T08:00:00"),
            evaluator.nextAllowedAt(p, instant("2026-11-13T22:00:00")))
    }

    @Test fun blackoutCrossingStartReturnsItsExactEnd() {
        val p = policy(intervals = listOf(BlackoutInterval(
            instant("2026-11-09T07:00:00"), instant("2026-11-09T09:15:00")
        )))
        assertEquals(instant("2026-11-09T09:15:00"),
            evaluator.nextAllowedAt(p, instant("2026-11-09T07:59:00")))
    }

    @Test fun springForwardNonexistentHourDoesNotPermitUnexpectedTime() {
        val p = policy(windows = listOf(window(DayOfWeek.SUNDAY, "01:30", "03:30")))
        assertTrue(evaluator.evaluate(p, instant("2026-03-29T01:45:00")).allowed)
        assertTrue(evaluator.evaluate(p, instant("2026-03-29T03:15:00")).allowed)
        assertFalse(evaluator.evaluate(p, instant("2026-03-29T03:30:00")).allowed)
    }

    @Test fun autumnRepeatedHourIsIncludedOnBothOffsets() {
        val p = policy(windows = listOf(window(DayOfWeek.SUNDAY, "02:15", "02:45")))
        val local = java.time.LocalDateTime.parse("2026-10-25T02:30:00")
        val offsets = zone.rules.getValidOffsets(local)
        assertEquals(2, offsets.size)
        for (offset in offsets) {
            assertTrue(evaluator.evaluate(p, local.atOffset(offset).toInstant()).allowed)
        }
    }

    @Test fun nextAllowedWorksAcrossFallBackOverlap() {
        val p = policy(windows = listOf(window(DayOfWeek.SUNDAY, "02:30", "02:45")))
        val local = java.time.LocalDateTime.parse("2026-10-25T02:30:00")
        val offsets = zone.rules.getValidOffsets(local)
        val first = local.atOffset(offsets[0]).toInstant()
        val second = local.atOffset(offsets[1]).toInstant()
        assertEquals(first, evaluator.nextAllowedAt(p, first.minusSeconds(1)))
        assertEquals(second, evaluator.nextAllowedAt(p, first.plusSeconds(20 * 60)))
    }

    @Test fun nextAllowedHonorsInjectedClock() {
        val now = instant("2026-11-09T07:30:00")
        val sut = SendingPolicyEvaluator(Clock.fixed(now, zone))
        assertEquals(now, sut.evaluate(policy()).checkedAt)
        assertEquals(instant("2026-11-09T08:00:00"), sut.nextAllowedAt(policy()))
    }

    @Test fun zeroLengthWindowsAreRejected() {
        try {
            window(start = "09:00", end = "09:00")
            fail("expected invalid window")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidBlackoutIntervalIsRejected() {
        val now = instant("2026-11-09T09:00:00")
        try {
            BlackoutInterval(now, now)
            fail("expected invalid interval")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun missingNextAllowedReturnsNull() {
        val now = instant("2026-11-09T09:00:00")
        assertNull(evaluator.nextAllowedAt(policy(windows = emptyList()), now))
        assertNull(evaluator.nextAllowedAt(null, now))
    }
}
