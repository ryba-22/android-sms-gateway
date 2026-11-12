package me.capcom.smsgateway.modules.messages.policy

import android.content.Context
import com.google.gson.Gson
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Stores only primitive/string JSON DTOs, never reflective java.time objects. */
class AndroidSendingPolicyRepository(context: Context) : SendingPolicyRepository {
    private val preferences = context.getSharedPreferences("hard_sending_policy_v1", Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun read(): SendingPolicy? {
        return try {
            val value = preferences.getString(KEY, null) ?: return null
            val stored = gson.fromJson(value, StoredPolicy::class.java) ?: return null
            SendingPolicy(
                version = stored.version,
                timezone = stored.timezone,
                weeklyAllowedWindows = stored.windows.map {
                    WeeklyAllowedWindow(DayOfWeek.valueOf(it.day), LocalTime.parse(it.start), LocalTime.parse(it.endExclusive))
                },
                blackoutDates = stored.blackoutDates.map(LocalDate::parse).toSet(),
                blackoutIntervals = stored.blackoutIntervals.map {
                    BlackoutInterval(Instant.parse(it.start), Instant.parse(it.endExclusive))
                },
                paused = stored.paused,
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    override fun write(policy: SendingPolicy) {
        val dto = StoredPolicy(
            version = policy.version,
            timezone = policy.timezone,
            windows = policy.weeklyAllowedWindows.map {
                StoredWindow(it.day.name, it.start.toString(), it.endExclusive.toString())
            },
            blackoutDates = policy.blackoutDates.map { it.toString() },
            blackoutIntervals = policy.blackoutIntervals.map {
                StoredInterval(it.start.toString(), it.endExclusive.toString())
            },
            paused = policy.paused,
        )
        check(preferences.edit().putString(KEY, gson.toJson(dto)).commit()) {
            "Cannot durably save the sending policy"
        }
    }

    private data class StoredWindow(val day: String, val start: String, val endExclusive: String)
    private data class StoredInterval(val start: String, val endExclusive: String)
    private data class StoredPolicy(
        val version: Long,
        val timezone: String,
        val windows: List<StoredWindow>,
        val blackoutDates: List<String>,
        val blackoutIntervals: List<StoredInterval>,
        val paused: Boolean,
    )
    private companion object { const val KEY = "current" }
}
