package me.capcom.smsgateway.modules.messages.attempts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.util.UUID

/**
 * A reservation and recipient transition are one Room transaction.
 * If the process crashes AFTER this returns, the worker must never claim this recipient again.
 */
@Dao
interface MessageAttemptsDao {
    @Query("SELECT state FROM messagerecipient WHERE messageId = :id AND phoneNumber = :phone LIMIT 1")
    fun recipientState(id: String, phone: String): String?

    @Query("SELECT * FROM message_attempts WHERE messageId = :id AND phoneNumber = :phone LIMIT 1")
    fun existing(id: String, phone: String): MessageAttempt?

    @Query("SELECT * FROM message_attempts WHERE attemptId = :attemptId LIMIT 1")
    fun byAttemptId(attemptId: String): MessageAttempt?

    @Query("SELECT * FROM message_attempts WHERE messageId = :messageId ORDER BY reservedAt ASC")
    fun byMessageId(messageId: String): List<MessageAttempt>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAttempt(attempt: MessageAttempt): Long

    @Insert
    fun insertParts(parts: List<MessageAttemptPart>)

    @Query("UPDATE messagerecipient SET state = 'Processed' WHERE messageId = :id AND phoneNumber = :phone AND state = 'Pending'")
    fun claimRecipient(id: String, phone: String): Int

    @Query(
        "UPDATE message SET state = 'Processed', processedAt = :whenMs " +
            "WHERE id = :id AND state = 'Pending' " +
            "AND NOT EXISTS (SELECT 1 FROM messagerecipient WHERE messageId = :id AND state = 'Pending')"
    )
    fun markMessageProcessedIfReady(id: String, whenMs: Long): Int

    @Query("INSERT OR IGNORE INTO recipientstate(messageId, phoneNumber, state, updatedAt) VALUES (:id, :phone, 'Processed', :at)")
    fun recordRecipientClaim(id: String, phone: String, at: Long)

    @Transaction
    fun reserve(id: String, phone: String, partCount: Int, policyVersion: Long): MessageAttempt? {
        require(partCount > 0)
        require(policyVersion > 0)
        if (recipientState(id, phone) != "Pending" || existing(id, phone) != null) return null
        val now = System.currentTimeMillis()
        val attempt = MessageAttempt(id, phone, UUID.randomUUID().toString(), now, null, "RESERVED", policyVersion)
        if (insertAttempt(attempt) == -1L) return null
        insertParts((0 until partCount).map { MessageAttemptPart(attempt.attemptId, it, "PENDING", "PENDING") })
        check(claimRecipient(id, phone) == 1) { "Recipient claim must be atomic with reservation" }
        recordRecipientClaim(id, phone, now)
        markMessageProcessedIfReady(id, now)
        return attempt
    }

    @Query("UPDATE message_attempts SET invokedAt = :at, state = 'INVOKING' WHERE attemptId = :id AND state = 'RESERVED'")
    fun markInvoking(id: String, at: Long): Int

    @Query("UPDATE message_attempts SET state = 'UNKNOWN' WHERE attemptId = :id AND state IN ('RESERVED', 'INVOKING')")
    fun markUnknown(id: String): Int

    @Query("UPDATE message_attempt_parts SET sendState = :result WHERE attemptId = :id AND partIndex = :part AND sendState = 'PENDING'")
    fun markPartSent(id: String, part: Int, result: String): Int

    @Query("UPDATE message_attempt_parts SET deliveryState = :result WHERE attemptId = :id AND partIndex = :part AND deliveryState = 'PENDING'")
    fun markPartDelivered(id: String, part: Int, result: String): Int

    @Query("SELECT COUNT(*) FROM message_attempt_parts WHERE attemptId = :id AND sendState = 'PENDING'")
    fun pendingSendParts(id: String): Int

    @Query("SELECT COUNT(*) FROM message_attempt_parts WHERE attemptId = :id AND sendState = 'FAILED'")
    fun failedSendParts(id: String): Int

    @Query("SELECT COUNT(*) FROM message_attempt_parts WHERE attemptId = :id AND deliveryState = 'PENDING'")
    fun pendingDeliveryParts(id: String): Int

    @Query("SELECT COUNT(*) FROM message_attempt_parts WHERE attemptId = :id AND deliveryState = 'FAILED'")
    fun failedDeliveryParts(id: String): Int

    @Query("UPDATE message_attempts SET state = :status WHERE attemptId = :id")
    fun setAttemptStatus(id: String, status: String): Int

    @Transaction
    fun recordSendCallback(id: String, part: Int, succeeded: Boolean): String? {
        val attempt = byAttemptId(id) ?: return null
        markPartSent(id, part, if (succeeded) "SENT" else "FAILED")
        if (failedSendParts(id) > 0) {
            setAttemptStatus(id, "FAILED")
            return "FAILED"
        }
        if (pendingSendParts(id) == 0) {
            setAttemptStatus(id, "SENT")
            return "SENT"
        }
        // Only some multipart segments have reported; no premature overall SENT.
        return "PARTIAL"
    }

    @Transaction
    fun recordDeliveryCallback(id: String, part: Int, succeeded: Boolean): String? {
        val attempt = byAttemptId(id) ?: return null
        markPartDelivered(id, part, if (succeeded) "DELIVERED" else "FAILED")
        if (failedDeliveryParts(id) > 0) return "FAILED"
        return if (pendingDeliveryParts(id) == 0) "DELIVERED" else "PARTIAL"
    }
}
