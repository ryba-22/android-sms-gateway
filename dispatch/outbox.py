"""Minimal durable E6 outbox: no automatic retries after possible HTTP submission.

This is an isolated pilot prototype backed by SQLite. It does not initiate
a real SMS unless an operator explicitly supplies a transport and enables
one dispatch call. Production dispatch needs encrypted storage and security
review. No secrets, live phone numbers or network access in tests.
"""
from __future__ import annotations

import hashlib
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import sqlite3
import time
import uuid
from dataclasses import dataclass
from typing import Any, Protocol

IN_FLIGHT = ("SUBMITTING", "DEVICE_ACCEPTED", "SUBMISSION_UNKNOWN", "ATTEMPT_UNKNOWN")
TERMINAL = ("SENT", "DELIVERED", "FAILED")
PHONE_RE = re.compile(r"^\+[1-9]\d{7,14}$")


class Conflict(ValueError):
    """An idempotency key was reused with a different payload."""


class PreflightBlocked(RuntimeError):
    """A real handset has not confirmed its policy allows sending."""


@dataclass(frozen=True)
class GatewayResponse:
    status: int
    payload: dict[str, Any]


@dataclass(frozen=True)
class DispatchRecord:
    message_id: str
    campaign_id: str
    business_key: str
    phone: str
    body: str
    state: str


class Gateway(Protocol):
    def policy(self) -> dict[str, Any]: ...
    def post_message(self, message_id: str, phone: str, body: str) -> GatewayResponse: ...
    def get_message(self, message_id: str) -> GatewayResponse: ...
    def get_attempts(self, message_id: str) -> GatewayResponse: ...


class DispatchStore:
    """One SQLite DB owner. Every mutation opens its own immediate transaction."""

    def __init__(self, db_path: str | Path):
        self.path = Path(db_path)
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not self.path.exists():
            fd = os.open(str(self.path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(fd)
        os.chmod(self.path, 0o600)
        self._initialize()

    @contextmanager
    def _connect(self):
        conn = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        conn.row_factory = sqlite3.Row
        try:
            conn.execute("PRAGMA busy_timeout = 15000")
            conn.execute("PRAGMA foreign_keys = ON")
            yield conn
            if conn.in_transaction:
                conn.commit()
        except BaseException:
            if conn.in_transaction:
                conn.rollback()
            raise
        finally:
            conn.close()

    def _initialize(self) -> None:
        with self._connect() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS campaign (
                    id TEXT PRIMARY KEY,
                    approved_by TEXT,
                    approved_at INTEGER
                );
                CREATE TABLE IF NOT EXISTS dispatch_message (
                    id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL REFERENCES campaign(id),
                    business_key TEXT NOT NULL,
                    phone TEXT NOT NULL,
                    body TEXT NOT NULL,
                    payload_hash TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, business_key)
                );
                CREATE INDEX IF NOT EXISTS idx_dispatch_ready
                    ON dispatch_message(state, created_at);
                CREATE TABLE IF NOT EXISTS dispatch_attempt (
                    message_id TEXT PRIMARY KEY REFERENCES dispatch_message(id),
                    reserved_at INTEGER NOT NULL,
                    outcome TEXT
                );
            """)

    def create_campaign(self, campaign_id: str) -> None:
        if not campaign_id or len(campaign_id) > 120:
            raise ValueError("Invalid campaign id")
        with self._connect() as db:
            db.execute("INSERT OR IGNORE INTO campaign(id) VALUES (?)", (campaign_id,))

    def add(self, campaign_id: str, business_key: str, phone: str, body: str) -> str:
        if not business_key or len(business_key) > 160:
            raise ValueError("Invalid business key")
        if not PHONE_RE.fullmatch(phone):
            raise ValueError("Only E.164 numbers are accepted")
        if not body.strip() or len(body) > 1600:
            raise ValueError("Invalid message body")
        payload = json.dumps([phone, body], ensure_ascii=False, separators=(",", ":"))
        digest = hashlib.sha256(payload.encode("utf-8")).hexdigest()
        message_id = str(uuid.uuid5(uuid.NAMESPACE_URL, "crd-sms/" + campaign_id + "/" + business_key))
        now = int(time.time())
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            campaign = db.execute("SELECT approved_at FROM campaign WHERE id=?", (campaign_id,)).fetchone()
            if campaign is None or campaign["approved_at"] is not None:
                raise ValueError("Campaign missing or already approved")
            existing = db.execute(
                "SELECT id, payload_hash FROM dispatch_message WHERE campaign_id=? AND business_key=?",
                (campaign_id, business_key),
            ).fetchone()
            if existing is not None:
                if existing["payload_hash"] != digest:
                    raise Conflict("Payload changed under existing idempotency key")
                return str(existing["id"])
            db.execute(
                """INSERT INTO dispatch_message
                   (id,campaign_id,business_key,phone,body,payload_hash,state,created_at,updated_at)
                   VALUES (?,?,?,?,?,?,'DRAFT',?,?)""",
                (message_id, campaign_id, business_key, phone, body, digest, now, now),
            )
        return message_id

    def approve(self, campaign_id: str, *, count: int, operator: str) -> None:
        if not operator.strip() or count <= 0:
            raise ValueError("Explicit operator and nonempty expected count required")
        now = int(time.time())
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            campaign = db.execute(
                "SELECT approved_at FROM campaign WHERE id=?", (campaign_id,)
            ).fetchone()
            if campaign is None or campaign["approved_at"] is not None:
                raise ValueError("Unknown or already approved campaign")
            actual = db.execute(
                "SELECT COUNT(*) FROM dispatch_message WHERE campaign_id=?", (campaign_id,)
            ).fetchone()[0]
            if actual != count:
                raise ValueError(f"Expected count mismatch: expected {count}, found {actual}")
            db.execute(
                "UPDATE campaign SET approved_by=?, approved_at=? WHERE id=?",
                (operator, now, campaign_id),
            )
            db.execute(
                "UPDATE dispatch_message SET state='READY', updated_at=? WHERE campaign_id=? AND state='DRAFT'",
                (now, campaign_id),
            )

    def reserve_next(self) -> DispatchRecord | None:
        """Claim at most one handset message globally; never recycle ambiguous claims."""
        now = int(time.time())
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            busy = db.execute(
                "SELECT 1 FROM dispatch_message WHERE state IN (?,?,?,?) LIMIT 1",
                IN_FLIGHT,
            ).fetchone()
            if busy is not None:
                return None
            row = db.execute(
                """SELECT id,campaign_id,business_key,phone,body,state
                   FROM dispatch_message WHERE state='READY'
                   ORDER BY rowid LIMIT 1"""
            ).fetchone()
            if row is None:
                return None
            changed = db.execute(
                "UPDATE dispatch_message SET state='SUBMITTING', updated_at=? WHERE id=? AND state='READY'",
                (now, row["id"]),
            )
            assert changed.rowcount == 1
            db.execute(
                "INSERT INTO dispatch_attempt(message_id,reserved_at) VALUES (?,?)",
                (row["id"], now),
            )
            return DispatchRecord(
                row["id"], row["campaign_id"], row["business_key"],
                row["phone"], row["body"], "SUBMITTING",
            )

    def update(self, message_id: str, from_states: tuple[str, ...], to_state: str) -> bool:
        if to_state not in IN_FLIGHT + TERMINAL:
            raise ValueError("Forbidden state change")
        placeholders = ",".join("?" for _ in from_states)
        now = int(time.time())
        with self._connect() as db:
            db.execute("BEGIN IMMEDIATE")
            cur = db.execute(
                f"UPDATE dispatch_message SET state=?, updated_at=? WHERE id=? AND state IN ({placeholders})",
                (to_state, now, message_id, *from_states),
            )
            return cur.rowcount == 1

    def get(self, message_id: str) -> dict[str, Any]:
        with self._connect() as db:
            row = db.execute("SELECT * FROM dispatch_message WHERE id=?", (message_id,)).fetchone()
            if row is None:
                raise KeyError(message_id)
            return dict(row)

    def pending_reconciliation(self) -> dict[str, Any] | None:
        with self._connect() as db:
            row = db.execute(
                "SELECT * FROM dispatch_message WHERE state IN (?,?,?,?) ORDER BY updated_at,id LIMIT 1",
                IN_FLIGHT,
            ).fetchone()
            return dict(row) if row else None

    def count_by_state(self) -> dict[str, int]:
        with self._connect() as db:
            return {
                str(row["state"]): int(row["n"])
                for row in db.execute(
                    "SELECT state,COUNT(*) AS n FROM dispatch_message GROUP BY state"
                )
            }


class Dispatcher:
    def __init__(self, store: DispatchStore, gateway: Gateway):
        self.store = store
        self.gateway = gateway

    def _check_policy(self) -> None:
        status = self.gateway.policy()
        if (status.get("configured") is not True
            or status.get("paused") is not False
            or status.get("allowedNow") is not True
            or not isinstance(status.get("version"), int)
            or status.get("version") <= 0
            or status.get("timezone") != "Europe/Warsaw"):
            raise PreflightBlocked("Device calendar is not verified open")

    def dispatch_one(self, *, explicitly_enabled: bool = False) -> str | None:
        """No transport side effects unless explicitly enabled.

        Any ambiguous HTTP result is *never* retried automatically. A reserved
        local claim stays quarantined across process restarts until a READ.
        """
        if not explicitly_enabled:
            raise PreflightBlocked("Transport disabled; synthetic dry-run only")
        self._check_policy()
        item = self.store.reserve_next()
        if item is None:
            return None
        try:
            response = self.gateway.post_message(item.message_id, item.phone, item.body)
            if response.status == 202 and response.payload.get("id") == item.message_id:
                self.store.update(item.message_id, ("SUBMITTING",), "DEVICE_ACCEPTED")
            elif response.status == 409:
                # A duplicate request was probably accepted earlier; only READ
                # can confirm this. Never make a second POST with another id.
                self.store.update(item.message_id, ("SUBMITTING",), "SUBMISSION_UNKNOWN")
            else:
                self.store.update(item.message_id, ("SUBMITTING",), "SUBMISSION_UNKNOWN")
        except Exception:
            self.store.update(item.message_id, ("SUBMITTING",), "SUBMISSION_UNKNOWN")
        return item.message_id

    def reconcile_one(self) -> str | None:
        """Read-only reconciliation. No POST, no automatic requeue."""
        item = self.store.pending_reconciliation()
        if item is None:
            return None
        message_id = item["id"]
        try:
            result = self.gateway.get_message(message_id)
        except Exception:
            self.store.update(message_id, IN_FLIGHT, "SUBMISSION_UNKNOWN")
            return message_id
        if result.status != 200 or result.payload.get("id") != message_id:
            self.store.update(message_id, IN_FLIGHT, "SUBMISSION_UNKNOWN")
            return message_id
        recipients = result.payload.get("recipients")
        if not isinstance(recipients, list) or len(recipients) != 1:
            self.store.update(message_id, IN_FLIGHT, "SUBMISSION_UNKNOWN")
            return message_id
        recipient = recipients[0]
        if recipient.get("phoneNumber") != item["phone"]:
            self.store.update(message_id, IN_FLIGHT, "SUBMISSION_UNKNOWN")
            return message_id
        state = recipient.get("state")
        if state in ("Sent", "Delivered"):
            self.store.update(message_id, IN_FLIGHT, state.upper())
        elif state in ("Failed", "Cancelled"):
            # Some segments may have succeeded. Never expose as automatically
            # retryable, even if API reports Failed.
            self.store.update(message_id, IN_FLIGHT, "FAILED")
        else:
            try:
                attempts = self.gateway.get_attempts(message_id)
                if attempts.status == 200 and isinstance(attempts.payload.get("attempts"), list):
                    if any(a.get("state") == "UNKNOWN" for a in attempts.payload["attempts"]):
                        self.store.update(message_id, IN_FLIGHT, "ATTEMPT_UNKNOWN")
                    else:
                        self.store.update(message_id, IN_FLIGHT, "DEVICE_ACCEPTED")
                else:
                    self.store.update(message_id, IN_FLIGHT, "DEVICE_ACCEPTED")
            except Exception:
                self.store.update(message_id, IN_FLIGHT, "DEVICE_ACCEPTED")
        return message_id
