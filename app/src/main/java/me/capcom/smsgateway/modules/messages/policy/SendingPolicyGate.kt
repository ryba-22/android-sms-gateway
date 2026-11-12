package me.capcom.smsgateway.modules.messages.policy

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

interface SendingPolicyRepository {
    fun read(): SendingPolicy?
    fun write(policy: SendingPolicy)
}

sealed class GateResult {
    object Invoked : GateResult()
    data class Deferred(val decision: SendingDecision, val nextAllowedAt: Instant?) : GateResult()
}

class SendingDeferredException(val deferred: GateResult.Deferred) :
    RuntimeException("Physical sending blocked: " + deferred.decision.reason)

/**
 * All policy applications and synchronous modem invocations are serialized by one lock.
 * No suspend/async wait is allowed inside the critical section.
 */
class SendingPolicyGate(
    private val repository: SendingPolicyRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val evaluator: SendingPolicyEvaluator = SendingPolicyEvaluator(clock),
) {
    private val lock = Any()

    fun invokeModem(send: () -> Unit): GateResult = synchronized(lock) {
        val policy = readFailClosed()
        val decision = evaluator.evaluate(policy, clock.instant())
        if (!decision.allowed) {
            return@synchronized GateResult.Deferred(decision, evaluator.nextAllowedAt(policy, decision.checkedAt))
        }
        send()
        GateResult.Invoked
    }

    fun requireAllowed(send: () -> Unit) {
        when (val result = invokeModem(send)) {
            GateResult.Invoked -> Unit
            is GateResult.Deferred -> throw SendingDeferredException(result)
        }
    }

    fun currentDecision(): SendingDecision = synchronized(lock) {
        evaluator.evaluate(readFailClosed(), clock.instant())
    }

    fun nextAllowedAt(): Instant? = synchronized(lock) {
        evaluator.nextAllowedAt(readFailClosed(), clock.instant())
    }

    fun applyPolicy(policy: SendingPolicy) = synchronized(lock) {
        require(policy.version > 0) { "Policy version must be positive" }
        require(policy.weeklyAllowedWindows.isNotEmpty() || policy.paused) { "No allowed windows" }
        ZoneId.of(policy.timezone)
        val old = readFailClosed()
        require(old == null || policy.version > old.version) { "Policy versions must increase" }
        repository.write(policy)
    }

    private fun readFailClosed(): SendingPolicy? =
        try { repository.read() } catch (_: Exception) { null }
}
