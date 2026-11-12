package me.capcom.smsgateway.modules.messages.attempts

import androidx.room.Room
import me.capcom.smsgateway.data.AppDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room/SQLite, no Android phone, SIM, WorkManager or external network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AttemptLedgerRoomTest {
    private lateinit var db: AppDatabase
    private lateinit var name: String

    @Before fun setup() {
        name = "test-sms-attempt-ledger-" + System.nanoTime()
        val context = RuntimeEnvironment.getApplication()
        db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO Message(id, content, state, type, source, createdAt) " +
                "VALUES ('m1', '{}', 'Pending', 'Text', 'Local', 0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO MessageRecipient(messageId, phoneNumber, state) " +
                "VALUES ('m1', '+48111111111', 'Pending')"
        )
    }

    @After fun cleanup() {
        db.close()
        RuntimeEnvironment.getApplication().deleteDatabase(name)
    }

    @Test fun oneRecipientCanBeReservedOnlyOnce() {
        val dao = db.messageAttemptsDao()
        val first = dao.reserve("m1", "+48111111111", 1, 7)
        assertNotNull(first)
        assertNull(dao.reserve("m1", "+48111111111", 1, 7))
        assertEquals("Processed", dao.recipientState("m1", "+48111111111"))
        assertEquals("Processed", db.messagesDao().get("m1")!!.message.state.name)
        assertEquals(1, dao.byMessageId("m1").size)
    }

    @Test fun restartCannotReclaimReservedMessage() {
        val dao = db.messageAttemptsDao()
        val first = dao.reserve("m1", "+48111111111", 1, 1)!!
        assertEquals(1, dao.markInvoking(first.attemptId, System.currentTimeMillis()))
        db.close()

        db = Room.databaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        assertNull(db.messageAttemptsDao().reserve("m1", "+48111111111", 1, 1))
        assertEquals(first.attemptId, db.messageAttemptsDao().byMessageId("m1").single().attemptId)
    }

    @Test fun multipartIsNotSentUntilAllPartsReported() {
        val dao = db.messageAttemptsDao()
        val first = dao.reserve("m1", "+48111111111", 3, 1)!!
        assertEquals("PARTIAL", dao.recordSendCallback(first.attemptId, 1, true))
        assertEquals("PARTIAL", dao.recordSendCallback(first.attemptId, 0, true))
        assertEquals("SENT", dao.recordSendCallback(first.attemptId, 2, true))
        assertEquals("SENT", dao.byAttemptId(first.attemptId)!!.state)
    }

    @Test fun failedSegmentNeverBecomesSuccessfulByDuplicateCallback() {
        val dao = db.messageAttemptsDao()
        val first = dao.reserve("m1", "+48111111111", 2, 1)!!
        assertEquals("UNKNOWN", dao.recordSendCallback(first.attemptId, 0, false))
        assertEquals("UNKNOWN", dao.recordSendCallback(first.attemptId, 0, true))
        assertEquals("UNKNOWN", dao.recordSendCallback(first.attemptId, 1, true))
        assertNull(dao.reserve("m1", "+48111111111", 2, 1))
    }

    @Test fun partiallySentMultipartIsUnknownNotDefinitelyFailed() {
        val dao = db.messageAttemptsDao()
        val first = dao.reserve("m1", "+48111111111", 2, 1)!!
        assertEquals("UNKNOWN", dao.recordSendCallback(first.attemptId, 0, false))
        assertEquals("UNKNOWN", dao.recordSendCallback(first.attemptId, 1, true))
        assertEquals("UNKNOWN", dao.byAttemptId(first.attemptId)!!.state)
        assertNull(dao.reserve("m1", "+48111111111", 2, 1))
    }

    @Test fun cancelledRecipientCannotBeReserved() {
        val dao = db.messageAttemptsDao()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE MessageRecipient SET state = 'Cancelled' " +
                "WHERE messageId = 'm1' AND phoneNumber = '+48111111111'"
        )
        assertNull(dao.reserve("m1", "+48111111111", 1, 1))
        assertEquals("Cancelled", dao.recipientState("m1", "+48111111111"))
        assertTrue(dao.byMessageId("m1").isEmpty())
    }

    @Test fun onceReservedRecipientCannotBeCancelledAsPending() {
        val dao = db.messageAttemptsDao()
        assertNotNull(dao.reserve("m1", "+48111111111", 1, 1))
        // A pending-only cancellation must affect zero rows once the physical
        // attempt is reserved. The API state machine additionally rejects it.
        val updated = db.openHelper.writableDatabase.compileStatement(
            "UPDATE MessageRecipient SET state = 'Cancelled' " +
                "WHERE messageId = 'm1' AND phoneNumber = '+48111111111' AND state = 'Pending'"
        ).executeUpdateDelete()
        assertEquals(0, updated)
        assertEquals("Processed", dao.recipientState("m1", "+48111111111"))
    }

    @Test fun staleInvokingIsQuarantinedNotResent() {
        val dao = db.messageAttemptsDao()
        val first = dao.reserve("m1", "+48111111111", 1, 1)!!
        dao.markInvoking(first.attemptId, 100L)
        assertEquals(1, dao.quarantineAbandoned(System.currentTimeMillis() + 1000))
        assertEquals("UNKNOWN", dao.byAttemptId(first.attemptId)!!.state)
        assertNull(dao.reserve("m1", "+48111111111", 1, 1))
    }

    @Test fun knownNotInvokedIsSafelyReleased() {
        val dao = db.messageAttemptsDao()
        val first = dao.reserve("m1", "+48111111111", 1, 1)!!
        dao.markInvoking(first.attemptId, 123L)
        dao.releaseKnownNotInvoked(first.attemptId)
        assertNull(dao.byAttemptId(first.attemptId))
        assertEquals("Pending", dao.recipientState("m1", "+48111111111"))
        assertNotNull(dao.reserve("m1", "+48111111111", 1, 1))
    }
}
