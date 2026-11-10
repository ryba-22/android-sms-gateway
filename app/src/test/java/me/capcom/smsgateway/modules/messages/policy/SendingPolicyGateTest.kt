package me.capcom.smsgateway.modules.messages.policy

import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

class SendingPolicyGateTest {
    private val zone = ZoneId.of("Europe/Warsaw")

    private class Repo(var policy: SendingPolicy? = null) : SendingPolicyRepository {
        var failure = false
        override fun read(): SendingPolicy? {
            if (failure) throw IllegalStateException("storage unavailable")
            return policy
        }
        override fun write(policy: SendingPolicy) { this.policy = policy }
    }

    private fun policy(version: Long = 1, paused: Boolean = false) = SendingPolicy(
        version, "Europe/Warsaw",
        listOf(WeeklyAllowedWindow(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(21, 0))),
        paused = paused,
    )

    private fun clock(local: String) = Clock.fixed(
        java.time.LocalDateTime.parse(local).atZone(zone).toInstant(), zone,
    )

    @Test fun missingPolicyBlocksModem() {
        val gate = SendingPolicyGate(Repo(), clock("2026-11-09T12:00:00"))
        var sent = 0
        val result = gate.invokeModem { sent++ }
        assertTrue(result is GateResult.Deferred)
        assertEquals(0, sent)
        assertEquals(DenialReason.MISSING_POLICY, (result as GateResult.Deferred).decision.reason)
    }

    @Test fun afterHoursBlocksModem() {
        val gate = SendingPolicyGate(Repo(policy()), clock("2026-11-09T21:00:00"))
        var sent = 0
        val result = gate.invokeModem { sent++ }
        assertEquals(0, sent)
        assertEquals(DenialReason.OUTSIDE_ALLOWED_WINDOW, (result as GateResult.Deferred).decision.reason)
    }

    @Test fun allowedSynchronousInvocationRunsOnce() {
        val gate = SendingPolicyGate(Repo(policy()), clock("2026-11-09T12:00:00"))
        var sent = 0
        assertEquals(GateResult.Invoked, gate.invokeModem { sent++ })
        assertEquals(1, sent)
    }

    @Test fun policyChangeBetweenRecipientsBlocksSecond() {
        val gate = SendingPolicyGate(Repo(policy()), clock("2026-11-09T12:00:00"))
        var sent = 0
        gate.requireAllowed { sent++ }
        gate.applyPolicy(policy(2, true))
        try {
            gate.requireAllowed { sent++ }
            fail("second recipient must be deferred")
        } catch (e: SendingDeferredException) {
            assertEquals(DenialReason.PAUSED, e.deferred.decision.reason)
        }
        assertEquals(1, sent)
    }

    @Test fun unreadableStorageAlwaysBlocks() {
        val repo = Repo(policy())
        repo.failure = true
        val gate = SendingPolicyGate(repo, clock("2026-11-09T12:00:00"))
        var sent = 0
        assertTrue(gate.invokeModem { sent++ } is GateResult.Deferred)
        assertEquals(0, sent)
    }

    @Test fun stalePolicyVersionRejected() {
        val gate = SendingPolicyGate(Repo(policy()), clock("2026-11-09T12:00:00"))
        try {
            gate.applyPolicy(policy(1))
            fail("stale version")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun expeditedCallerHasNoBypassParameter() {
        val gate = SendingPolicyGate(Repo(policy()), clock("2026-11-09T22:00:00"))
        assertTrue(gate.invokeModem { fail("modem must not be called") } is GateResult.Deferred)
    }

    @Test fun deferReturnsNextWindow() {
        val gate = SendingPolicyGate(Repo(policy()), clock("2026-11-09T22:00:00"))
        val result = gate.invokeModem { fail("modem must not be called") } as GateResult.Deferred
        val expected = java.time.LocalDateTime.parse("2026-11-16T08:00:00").atZone(zone).toInstant()
        assertEquals(expected, result.nextAllowedAt)
    }
}
