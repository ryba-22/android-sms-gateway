"""Synthetic E6 contract tests. Never contact Android, SMS8 or a real SIM."""
import os
import tempfile
import threading
import unittest
from pathlib import Path

from dispatch.gateway_client import AndroidLocalServer
from dispatch.outbox import (
    Conflict, DispatchStore, Dispatcher, GatewayResponse, PreflightBlocked,
)


def phone(index: int) -> str:
    # Non-real synthetic E.164-style sequence for deterministic tests.
    return "+480000" + f"{index:06d}"


class FakeGateway:
    def __init__(self):
        self.submissions = []
        self.messages = {}
        self.allowed = True
        self.outage = False
        self.response_lost_after_accept = False

    def policy(self):
        if self.outage:
            raise TimeoutError("simulated offline")
        return {
            "configured": True, "paused": False, "allowedNow": self.allowed,
            "version": 1, "timezone": "Europe/Warsaw",
        }

    def post_message(self, message_id, destination, body):
        self.submissions.append(message_id)
        self.messages[message_id] = {
            "id": message_id,
            "state": "Pending",
            "recipients": [{"phoneNumber": destination, "state": "Pending"}],
        }
        if self.response_lost_after_accept:
            raise TimeoutError("reply lost after server accepted the SMS")
        return GatewayResponse(202, {"id": message_id})

    def get_message(self, message_id):
        message = self.messages.get(message_id)
        return GatewayResponse(200, message) if message else GatewayResponse(404, {})

    def get_attempts(self, message_id):
        return GatewayResponse(200, {"attempts": []})

    def sent(self, message_id):
        self.messages[message_id]["recipients"][0]["state"] = "Sent"


class E6DispatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp.name) / "queue.sqlite"
        self.store = DispatchStore(self.db_path)
        self.gateway = FakeGateway()
        self.dispatcher = Dispatcher(self.store, self.gateway)
        self.store.create_campaign("October")

    def tearDown(self):
        self.temp.cleanup()

    def add(self, i: int) -> str:
        return self.store.add("October", f"invoice:{i}", phone(i), f"Synthetic invoice {i}")

    def approve(self, count: int):
        self.store.approve("October", count=count, operator="synthetic-test-operator")

    def test_1100_synthetic_messages_all_reconcile_once(self):
        all_ids = [self.add(i) for i in range(1100)]
        self.approve(1100)
        self.assertEqual(self.store.count_by_state(), {"READY": 1100})
        for message_id in all_ids:
            current = self.dispatcher.dispatch_one(explicitly_enabled=True)
            self.assertEqual(current, message_id)
            # No further device submit while a previous message is unresolved.
            self.assertIsNone(self.dispatcher.dispatch_one(explicitly_enabled=True))
            self.gateway.sent(message_id)
            self.assertEqual(self.dispatcher.reconcile_one(), message_id)
        self.assertEqual(self.store.count_by_state(), {"SENT": 1100})
        self.assertEqual(len(self.gateway.submissions), 1100)
        self.assertEqual(len(set(self.gateway.submissions)), 1100)

    def test_same_business_key_is_idempotent_until_approved(self):
        first = self.add(12)
        self.assertEqual(first, self.add(12))
        with self.assertRaises(Conflict):
            self.store.add("October", "invoice:12", phone(13), "Synthetic invoice 12")
        self.approve(1)
        with self.assertRaises(ValueError):
            self.add(12)

    def test_operator_cannot_approve_wrong_campaign_count(self):
        self.add(1)
        with self.assertRaises(ValueError):
            self.approve(2)
        self.assertEqual(self.store.count_by_state(), {"DRAFT": 1})
        self.approve(1)

    def test_dry_run_default_never_invokes_transport(self):
        self.add(1)
        self.approve(1)
        with self.assertRaises(PreflightBlocked):
            self.dispatcher.dispatch_one()
        self.assertEqual(self.gateway.submissions, [])
        self.assertEqual(self.store.count_by_state(), {"READY": 1})

    def test_closed_calendar_and_offline_gateway_do_not_claim(self):
        self.add(1)
        self.approve(1)
        self.gateway.allowed = False
        with self.assertRaises(PreflightBlocked):
            self.dispatcher.dispatch_one(explicitly_enabled=True)
        self.assertEqual(self.store.count_by_state(), {"READY": 1})
        self.gateway.allowed = True
        self.gateway.outage = True
        with self.assertRaises(TimeoutError):
            self.dispatcher.dispatch_one(explicitly_enabled=True)
        self.assertEqual(self.store.count_by_state(), {"READY": 1})

    def test_lost_http_response_is_not_retried(self):
        msg_id = self.add(1)
        self.approve(1)
        self.gateway.response_lost_after_accept = True
        self.dispatcher.dispatch_one(explicitly_enabled=True)
        self.assertEqual(self.store.get(msg_id)["state"], "SUBMISSION_UNKNOWN")
        self.assertIsNone(self.dispatcher.dispatch_one(explicitly_enabled=True))
        self.assertEqual(len(self.gateway.submissions), 1)
        # A read can reconcile without a second POST.
        self.gateway.sent(msg_id)
        self.dispatcher.reconcile_one()
        self.assertEqual(self.store.get(msg_id)["state"], "SENT")
        self.assertEqual(len(self.gateway.submissions), 1)

    def test_crash_after_durable_claim_never_retries_message(self):
        msg_id = self.add(1)
        self.approve(1)
        reserved = self.store.reserve_next()
        self.assertEqual(reserved.message_id, msg_id)
        restarted = Dispatcher(DispatchStore(self.db_path), self.gateway)
        self.assertIsNone(restarted.dispatch_one(explicitly_enabled=True))
        self.assertEqual(len(self.gateway.submissions), 0)
        self.assertEqual(restarted.reconcile_one(), msg_id)
        self.assertEqual(self.store.get(msg_id)["state"], "SUBMISSION_UNKNOWN")
        self.assertIsNone(restarted.dispatch_one(explicitly_enabled=True))

    def test_missing_remote_message_after_timeout_remains_quarantined(self):
        msg_id = self.add(1)
        self.approve(1)
        self.store.reserve_next()
        self.dispatcher.reconcile_one()
        self.assertEqual(self.store.get(msg_id)["state"], "SUBMISSION_UNKNOWN")
        self.assertIsNone(self.dispatcher.dispatch_one(explicitly_enabled=True))

    def test_parallel_workers_claim_only_one(self):
        self.add(1)
        self.add(2)
        self.approve(2)
        barrier = threading.Barrier(2)
        results = []
        lock = threading.Lock()

        def worker():
            barrier.wait(timeout=5)
            claim = DispatchStore(self.db_path).reserve_next()
            with lock:
                results.append(claim.message_id if claim else None)

        threads = [threading.Thread(target=worker) for _ in range(2)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=15)
        self.assertEqual(len(results), 2)
        self.assertEqual(sum(x is not None for x in results), 1)
        self.assertEqual(self.store.count_by_state(), {"READY": 1, "SUBMITTING": 1})

    def test_absence_of_automatic_retry_after_failed_recipient(self):
        msg_id = self.add(1)
        self.approve(1)
        self.dispatcher.dispatch_one(explicitly_enabled=True)
        self.gateway.messages[msg_id]["recipients"][0]["state"] = "Failed"
        self.dispatcher.reconcile_one()
        self.assertEqual(self.store.get(msg_id)["state"], "FAILED")
        self.assertEqual(self.gateway.submissions, [msg_id])

    def test_other_campaign_may_be_approved_but_not_submitted_while_unknown(self):
        first = self.add(1)
        self.approve(1)
        self.gateway.response_lost_after_accept = True
        self.dispatcher.dispatch_one(explicitly_enabled=True)
        self.store.create_campaign("November")
        self.store.add("November", "invoice:1", phone(2), "Synthetic invoice 2")
        self.store.approve("November", count=1, operator="synthetic-test-operator")
        self.assertIsNone(self.dispatcher.dispatch_one(explicitly_enabled=True))
        self.assertEqual(self.store.get(first)["state"], "SUBMISSION_UNKNOWN")
        self.assertEqual(self.store.count_by_state()["READY"], 1)

    def test_storage_is_owner_only_permissions(self):
        self.assertEqual(self.db_path.stat().st_mode & 0o777, 0o600)

    def test_private_http_requires_explicit_opt_in(self):
        with self.assertRaises(ValueError):
            AndroidLocalServer("http://192.168.1.44:8080", username="u", password="p")
        with self.assertRaises(ValueError):
            AndroidLocalServer("http://example.com", username="u", password="p",
                               allow_private_http=True)
        client = AndroidLocalServer("http://192.168.1.44:8080", username="u", password="p",
                                    allow_private_http=True)
        self.assertEqual(client.base_url, "http://192.168.1.44:8080")


if __name__ == "__main__":
    unittest.main()
