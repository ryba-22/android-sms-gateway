package me.capcom.smsgateway.modules.messages.policy

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.capcom.smsgateway.data.AppDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Android runtime tests with fake modem lambdas. No actual SmsManager call. */
@RunWith(AndroidJUnit4::class)
class SafetyInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val zone = ZoneId.of("Europe/Warsaw")

    private fun clock(local: String): Clock =
        Clock.fixed(LocalDateTime.parse(local).atZone(zone).toInstant(), zone)

    private fun policy(version: Long) = SendingPolicy(
        version, zone.id,
        listOf(WeeklyAllowedWindow(DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(21, 0)))
    )

    @Before fun clearBefore() {
        context.getSharedPreferences("hard_sending_policy_v1", 0).edit().clear().commit()
    }

    @After fun clearAfter() {
        context.getSharedPreferences("hard_sending_policy_v1", 0).edit().clear().commit()
    }

    @Test fun missingPolicyNeverCallsFakeModem() {
        val gate = SendingPolicyGate(AndroidSendingPolicyRepository(context), clock("2026-11-09T12:00:00"))
        var invoked = 0
        val result = gate.invokeModem { invoked++ }
        assertTrue(result is GateResult.Deferred)
        assertEquals(0, invoked)
    }

    @Test fun persistedPolicySurvivesReloadAndClosesAt21() {
        val gate = SendingPolicyGate(AndroidSendingPolicyRepository(context), clock("2026-11-09T20:59:59"))
        gate.applyPolicy(policy(1))
        var invoked = 0
        assertEquals(GateResult.Invoked, gate.invokeModem { invoked++ })
        val reloaded = SendingPolicyGate(
            AndroidSendingPolicyRepository(context), clock("2026-11-09T21:00:00")
        )
        assertTrue(reloaded.invokeModem { invoked++ } is GateResult.Deferred)
        assertEquals(1, invoked)
        assertEquals(1L, reloaded.currentDecision().policyVersion)
    }

    @Test fun roomReservationSurvivesDatabaseRestart() {
        val databaseName = "safe-android-" + System.nanoTime()
        var db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO Message(id, content, state, type, source, createdAt) " +
                    "VALUES ('one', '{}', 'Pending', 'Text', 'Local', 0)"
            )
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO MessageRecipient(messageId, phoneNumber, state) " +
                    "VALUES ('one', '+48111111111', 'Pending')"
            )
            val first = db.messageAttemptsDao().reserve("one", "+48111111111", 1, 1)
            assertNotNull(first)
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .allowMainThreadQueries().build()
            assertNull(db.messageAttemptsDao().reserve("one", "+48111111111", 1, 1))
            assertEquals(first!!.attemptId, db.messageAttemptsDao().byMessageId("one").single().attemptId)
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }
}
