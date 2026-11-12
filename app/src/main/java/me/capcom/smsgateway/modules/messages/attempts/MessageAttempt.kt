package me.capcom.smsgateway.modules.messages.attempts

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.capcom.smsgateway.data.entities.MessageRecipient

/**
 * One physical transmission reservation per logical message/recipient.
 * A unique index on attemptId supports callback correlation without exposing PII in URIs.
 * Never delete a record to "retry" unless an operator explicitly reconciles it.
 */
@Entity(
    tableName = "message_attempts",
    primaryKeys = ["messageId", "phoneNumber"],
    foreignKeys = [
        ForeignKey(
            entity = MessageRecipient::class,
            parentColumns = ["messageId", "phoneNumber"],
            childColumns = ["messageId", "phoneNumber"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["attemptId"], unique = true), Index(value = ["messageId", "phoneNumber"])]
)
data class MessageAttempt(
    val messageId: String,
    val phoneNumber: String,
    val attemptId: String,
    val reservedAt: Long,
    val invokedAt: Long?,
    val state: String,
    val policyVersion: Long,
)

@Entity(
    tableName = "message_attempt_parts",
    primaryKeys = ["attemptId", "partIndex"],
    foreignKeys = [
        ForeignKey(
            entity = MessageAttempt::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["attemptId"])]
)
data class MessageAttemptPart(
    val attemptId: String,
    val partIndex: Int,
    val sendState: String,
    val deliveryState: String,
)
