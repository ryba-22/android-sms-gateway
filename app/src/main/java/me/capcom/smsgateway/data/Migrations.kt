package me.capcom.smsgateway.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            UPDATE message
            SET validUntil = strftime('%FT%TZ', createdAt / 1000 + 86400, 'unixepoch')
            WHERE validUntil IS NULL AND state = 'Pending'
        """.trimIndent()
        )
    }
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create a new table with the desired schema (without the text column)
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS message_new (
                `id` TEXT NOT NULL,
                `withDeliveryReport` INTEGER NOT NULL DEFAULT 1,
                `simNumber` INTEGER,
                `validUntil` TEXT,
                `isEncrypted` INTEGER NOT NULL DEFAULT 0,
                `skipPhoneValidation` INTEGER NOT NULL DEFAULT 0,
                `priority` INTEGER NOT NULL DEFAULT 0,
                `type` TEXT NOT NULL DEFAULT 'Text',
                `source` TEXT NOT NULL DEFAULT 'Local',
                `content` TEXT NOT NULL,
                `state` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL DEFAULT 0,
                `processedAt` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )

        // Copy data from the old table to the new table
        database.execSQL(
            """
            INSERT INTO message_new (
                id,
                withDeliveryReport,
                simNumber,
                validUntil,
                isEncrypted,
                skipPhoneValidation,
                priority,
                source,
                type,
                content,
                state,
                createdAt,
                processedAt
            ) SELECT
                id,
                withDeliveryReport,
                simNumber,
                validUntil,
                isEncrypted,
                skipPhoneValidation,
                priority,
                source,
                'Text' AS type,
                '{"text": "' || replace(replace(text, '\\', '\\\\'), '"', '\\"') || '"}' AS content,
                state,
                createdAt,
                processedAt
            FROM message
        """.trimIndent()
        )

        // Drop the old table and rename the new table to the original name
        database.execSQL("DROP TABLE IF EXISTS message")
        database.execSQL("ALTER TABLE message_new RENAME TO message")

        // Create indices
        database.execSQL("CREATE INDEX IF NOT EXISTS index_Message_state ON message (state)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_Message_createdAt ON message (createdAt)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_Message_processedAt ON message (processedAt)")
    }
}

val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            DELETE FROM incoming_messages WHERE 1
        """.trimIndent()
        )
    }
}
// Physical-send intent ledger is additive; existing message rows are not rewritten.
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS message_attempts (
                messageId TEXT NOT NULL, phoneNumber TEXT NOT NULL, attemptId TEXT NOT NULL,
                reservedAt INTEGER NOT NULL, invokedAt INTEGER, state TEXT NOT NULL, policyVersion INTEGER NOT NULL,
                PRIMARY KEY(messageId, phoneNumber),
                FOREIGN KEY(messageId, phoneNumber) REFERENCES messagerecipient(messageId, phoneNumber)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_message_attempts_attemptId ON message_attempts(attemptId)")
        database.execSQL("CREATE INDEX IF NOT EXISTS index_message_attempts_messageId_phoneNumber ON message_attempts(messageId, phoneNumber)")
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS message_attempt_parts (
                attemptId TEXT NOT NULL, partIndex INTEGER NOT NULL, sendState TEXT NOT NULL,
                deliveryState TEXT NOT NULL, PRIMARY KEY(attemptId, partIndex),
                FOREIGN KEY(attemptId) REFERENCES message_attempts(attemptId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        database.execSQL("CREATE INDEX IF NOT EXISTS index_message_attempt_parts_attemptId ON message_attempt_parts(attemptId)")
    }
}
