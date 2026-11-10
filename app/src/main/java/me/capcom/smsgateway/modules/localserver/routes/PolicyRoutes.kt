package me.capcom.smsgateway.modules.localserver.routes

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import me.capcom.smsgateway.modules.localserver.auth.AuthScopes
import me.capcom.smsgateway.modules.localserver.auth.requireScope
import me.capcom.smsgateway.modules.messages.policy.BlackoutInterval
import me.capcom.smsgateway.modules.messages.policy.SendingPolicy
import me.capcom.smsgateway.modules.messages.policy.SendingPolicyGate
import me.capcom.smsgateway.modules.messages.policy.SendingPolicyRepository
import me.capcom.smsgateway.modules.messages.policy.WeeklyAllowedWindow
import me.capcom.smsgateway.modules.messages.workers.SendMessagesWorker
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

data class PolicyWindowInput(
    val day: String,
    val start: String,
    val endExclusive: String,
)

data class PolicyIntervalInput(
    val start: String,
    val endExclusive: String,
)

/** JSON schema for PUT /policy. Stored model never exposes Gson's reflective java.time internals. */
data class PolicyReplaceInput(
    val version: Long,
    val timezone: String,
    val weeklyAllowedWindows: List<PolicyWindowInput>,
    val blackoutDates: List<String> = emptyList(),
    val blackoutIntervals: List<PolicyIntervalInput> = emptyList(),
    val paused: Boolean = false,
) {
    fun toModel() = SendingPolicy(
        version = version,
        timezone = timezone,
        weeklyAllowedWindows = weeklyAllowedWindows.map {
            WeeklyAllowedWindow(DayOfWeek.valueOf(it.day), LocalTime.parse(it.start), LocalTime.parse(it.endExclusive))
        },
        blackoutDates = blackoutDates.map(LocalDate::parse).toSet(),
        blackoutIntervals = blackoutIntervals.map {
            BlackoutInterval(Instant.parse(it.start), Instant.parse(it.endExclusive))
        },
        paused = paused,
    )
}

data class PolicyVersionInput(val expectedVersion: Long)

class PolicyRoutes(
    private val gate: SendingPolicyGate,
    private val repository: SendingPolicyRepository,
    private val context: Context,
) {
    fun register(route: Route) {
        route.apply {
            get {
                if (!requireScope(AuthScopes.SettingsRead)) return@get
                val policy = repository.read()
                if (policy == null) {
                    call.respond(
                        mapOf(
                            "configured" to false,
                            "allowedNow" to false,
                            "reason" to "MISSING_OR_INVALID_POLICY",
                        )
                    )
                    return@get
                }
                val decision = gate.currentDecision()
                call.respond(
                    mapOf(
                        "configured" to true,
                        "version" to policy.version,
                        "timezone" to policy.timezone,
                        "paused" to policy.paused,
                        "weeklyAllowedWindows" to policy.weeklyAllowedWindows.map {
                            PolicyWindowInput(it.day.name, it.start.toString(), it.endExclusive.toString())
                        },
                        "blackoutDates" to policy.blackoutDates.map { it.toString() },
                        "blackoutIntervals" to policy.blackoutIntervals.map {
                            PolicyIntervalInput(it.start.toString(), it.endExclusive.toString())
                        },
                        "allowedNow" to decision.allowed,
                        "reason" to decision.reason?.name,
                        "checkedAt" to decision.checkedAt.toString(),
                    )
                )
            }

            put {
                if (!requireScope(AuthScopes.SettingsWrite)) return@put
                try {
                    val policy = call.receive<PolicyReplaceInput>().toModel()
                    gate.applyPolicy(policy)
                    wakeIfPermitted()
                    call.respond(mapOf("appliedVersion" to policy.version, "paused" to policy.paused))
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid or stale policy version"))
                } catch (_: RuntimeException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Invalid policy request"))
                }
            }

            post("pause") {
                if (!requireScope(AuthScopes.SettingsWrite)) return@post
                val input = call.receive<PolicyVersionInput>()
                val old = repository.read()
                if (old == null || old.version != input.expectedVersion) {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "Policy version mismatch"))
                    return@post
                }
                try {
                    gate.applyPolicy(old.copy(version = old.version + 1, paused = true))
                    call.respond(mapOf("appliedVersion" to old.version + 1, "paused" to true))
                } catch (_: RuntimeException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "Policy update conflict"))
                }
            }

            post("resume") {
                if (!requireScope(AuthScopes.SettingsWrite)) return@post
                val input = call.receive<PolicyVersionInput>()
                val old = repository.read()
                if (old == null || old.version != input.expectedVersion) {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "Policy version mismatch"))
                    return@post
                }
                try {
                    gate.applyPolicy(old.copy(version = old.version + 1, paused = false))
                    wakeIfPermitted()
                    call.respond(mapOf("appliedVersion" to old.version + 1, "paused" to false))
                } catch (_: RuntimeException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("message" to "Policy update conflict"))
                }
            }
        }
    }

    private fun wakeIfPermitted() {
        // Applying a policy at 22:00 must still wake the queue tomorrow at 08:00.
        // WorkManager timing is advisory; the hard gate checks again on execution.
        val next = gate.nextAllowedAt() ?: return
        SendMessagesWorker.start(context, true, next.toEpochMilli())
    }
}
