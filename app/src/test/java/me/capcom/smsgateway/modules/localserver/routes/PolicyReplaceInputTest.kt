package me.capcom.smsgateway.modules.localserver.routes

import me.capcom.smsgateway.modules.messages.policy.DenialReason
import me.capcom.smsgateway.modules.messages.policy.SendingPolicyEvaluator
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PolicyReplaceInputTest {
    private val sample = PolicyReplaceInput(
        version = 1L,
        timezone = "Europe/Warsaw",
        weeklyAllowedWindows = listOf(PolicyWindowInput("MONDAY", "08:00", "21:00")),
        blackoutDates = listOf("2026-11-11"),
        blackoutIntervals = listOf(PolicyIntervalInput("2026-11-10T12:00:00Z", "2026-11-10T14:00:00Z")),
    )

    @Test fun dtoCreatesVersionedPolicy() {
        val model = sample.toModel()
        assertEquals(1L, model.version)
        assertEquals("Europe/Warsaw", model.timezone)
        assertEquals(1, model.weeklyAllowedWindows.size)
        assertEquals(1, model.blackoutDates.size)
        assertEquals(1, model.blackoutIntervals.size)
    }

    @Test fun invalidWindowDayRejected() {
        try {
            sample.copy(weeklyAllowedWindows = listOf(PolicyWindowInput("SOMEDAY", "08:00", "21:00"))).toModel()
            fail("bad day should be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun blackoutDateRejectsAndInvalidTimeFailsClosed() {
        val model = sample.toModel()
        assertEquals(
            DenialReason.BLACKOUT_DATE,
            SendingPolicyEvaluator().evaluate(model, Instant.parse("2026-11-11T10:00:00Z")).reason,
        )
        try {
            sample.copy(blackoutDates = listOf("not a date")).toModel()
            fail("bad date should be rejected")
        } catch (_: RuntimeException) {}
    }
}
