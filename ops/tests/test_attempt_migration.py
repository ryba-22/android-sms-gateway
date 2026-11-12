"""Verify the actual additive SQL from MIGRATION_25_26, without Android or real SMS."""
import re
import sqlite3
import unittest
from pathlib import Path


MIGRATIONS = Path(__file__).resolve().parents[2] / "app/src/main/java/me/capcom/smsgateway/data/Migrations.kt"


class AttemptMigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript("""
            CREATE TABLE message(id TEXT NOT NULL PRIMARY KEY);
            CREATE TABLE messagerecipient(
                messageId TEXT NOT NULL, phoneNumber TEXT NOT NULL, state TEXT NOT NULL,
                PRIMARY KEY(messageId, phoneNumber),
                FOREIGN KEY(messageId) REFERENCES message(id) ON DELETE CASCADE
            );
            INSERT INTO message(id) VALUES ('message1');
            INSERT INTO messagerecipient(messageId, phoneNumber, state)
              VALUES ('message1', '+48111111111', 'Pending');
        """)
        code = MIGRATIONS.read_text(encoding="utf-8").split("val MIGRATION_25_26", 1)[1]
        create_sql = re.findall(r'database\.execSQL\(\s*"""(.*?)"""\.trimIndent\(\)\s*\)', code, re.S)
        index_sql = re.findall(r'database\.execSQL\("(CREATE INDEX[^"]*|CREATE UNIQUE INDEX[^"]*)"\)', code)
        self.assertEqual(len(create_sql), 2, "Expected two CREATE TABLE statements")
        self.assertEqual(len(index_sql), 3, "Expected three CREATE INDEX statements")
        for sql in create_sql + index_sql:
            self.db.execute(sql.strip())

    def tearDown(self):
        self.db.close()

    def test_unique_message_recipient_and_attempt_id(self):
        row = ("message1", "+48111111111", "attempt1", 1234, None, "RESERVED", 1)
        self.db.execute("INSERT INTO message_attempts VALUES (?,?,?,?,?,?,?)", row)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO message_attempts VALUES (?,?,?,?,?,?,?)", row)
        self.db.execute("INSERT INTO message(id) VALUES ('message2')")
        self.db.execute(
            "INSERT INTO messagerecipient VALUES ('message2','+48222222222','Pending')"
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "INSERT INTO message_attempts VALUES ('message2','+48222222222','attempt1',1,NULL,'RESERVED',1)"
            )

    def test_child_parts_are_unique_and_cascade(self):
        self.db.execute(
            "INSERT INTO message_attempts VALUES ('message1','+48111111111','attempt1',1,NULL,'RESERVED',1)"
        )
        self.db.execute(
            "INSERT INTO message_attempt_parts VALUES ('attempt1',0,'PENDING','PENDING')"
        )
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "INSERT INTO message_attempt_parts VALUES ('attempt1',0,'PENDING','PENDING')"
            )
        self.db.execute("DELETE FROM messagerecipient WHERE messageId='message1'")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM message_attempts").fetchone()[0], 0)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM message_attempt_parts").fetchone()[0], 0)

    def test_foreign_keys_prevent_orphaned_attempts(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(
                "INSERT INTO message_attempts VALUES ('unknown','+48000000000','attempt1',1,NULL,'RESERVED',1)"
            )


if __name__ == "__main__":
    unittest.main()
