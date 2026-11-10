package me.capcom.smsgateway.modules.messages.attempts

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import me.capcom.smsgateway.data.AppDatabase
import me.capcom.smsgateway.data.MIGRATION_25_26
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Starts with the checked-in Room v25 schema and opens the real v26 Room DB.
 * Room validates migrated tables, indices and foreign-key reference names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AttemptLedgerMigrationTest {
    @Test fun existingV25MessagesSurviveUpgradeToV26() {
        val context = RuntimeEnvironment.getApplication()
        val name = "test-attempt-migration-" + System.nanoTime()
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val schema = File("schemas/me.capcom.smsgateway.data.AppDatabase/25.json")
        assertTrue("Room v25 schema missing: " + schema.absolutePath, schema.exists())
        val model = JSONObject(schema.readText()).getJSONObject("database")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = model.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                val sql = e.getString("createSql").replace("\${TABLE_NAME}", table)
                sqlite.execSQL(sql)
            }
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val indices = e.getJSONArray("indices")
                for (j in 0 until indices.length()) {
                    val index = indices.getJSONObject(j)
                    sqlite.execSQL(index.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                }
            }
            sqlite.execSQL(
                "INSERT INTO Message(id, content, state, type, source, createdAt) " +
                    "VALUES ('before-upgrade', '{}', 'Pending', 'Text', 'Local', 1)"
            )
            sqlite.execSQL(
                "INSERT INTO MessageRecipient(messageId, phoneNumber, state) " +
                    "VALUES ('before-upgrade', '+48123456789', 'Pending')"
            )
            sqlite.version = 25
        } finally {
            sqlite.close()
        }

        val upgraded = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_25_26)
            .allowMainThreadQueries()
            .build()
        try {
            val message = upgraded.messagesDao().get("before-upgrade")
            assertNotNull("Message must survive migration", message)
            assertEquals(1, message!!.recipients.size)
            assertNotNull(
                upgraded.messageAttemptsDao().reserve("before-upgrade", "+48123456789", 1, 1)
            )
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }
}
