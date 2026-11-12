"""Purely synthetic E6 demonstration. Cannot reach a phone or send an SMS."""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

from .outbox import DispatchStore, Dispatcher, GatewayResponse


class SyntheticGateway:
    def __init__(self):
        self.messages: dict[str, dict] = {}
        self.post_count = 0

    def policy(self):
        return {
            "configured": True,
            "paused": False,
            "allowedNow": True,
            "version": 1,
            "timezone": "Europe/Warsaw",
        }

    def post_message(self, message_id, phone, body):
        self.post_count += 1
        self.messages[message_id] = {
            "id": message_id,
            "recipients": [{"phoneNumber": phone, "state": "Pending"}],
        }
        return GatewayResponse(202, {"id": message_id})

    def get_message(self, message_id):
        return GatewayResponse(200, self.messages[message_id])

    def get_attempts(self, message_id):
        return GatewayResponse(200, {"attempts": []})

    def mark_sent(self, message_id):
        self.messages[message_id]["recipients"][0]["state"] = "Sent"


def main():
    parser = argparse.ArgumentParser(description="No-network synthetic SMS dispatcher demonstration")
    parser.add_argument("--count", type=int, default=1100)
    args = parser.parse_args()
    if args.count <= 0 or args.count > 10000:
        parser.error("Count must be between 1 and 10000")
    with tempfile.TemporaryDirectory(prefix="crd-sms-dispatch-sim-") as directory:
        store = DispatchStore(Path(directory) / "outbox.sqlite3")
        store.create_campaign("SYNTHETIC")
        for index in range(args.count):
            store.add("SYNTHETIC", f"synthetic:{index}", "+480000" + f"{index:06d}",
                      f"Synthetic, non-real message {index}")
        store.approve("SYNTHETIC", count=args.count, operator="synthetic-test")
        gateway = SyntheticGateway()
        dispatcher = Dispatcher(store, gateway)
        for _ in range(args.count):
            message_id = dispatcher.dispatch_one(explicitly_enabled=True)
            if message_id is None:
                raise AssertionError("Unexpected empty queue")
            gateway.mark_sent(message_id)
            dispatcher.reconcile_one()
        print(json.dumps({
            "mode": "SYNTHETIC_NO_NETWORK_NO_SMS",
            "prepared": args.count,
            "simulated_http_posts": gateway.post_count,
            "states": store.count_by_state(),
            "all_logical_ids_unique": len(gateway.messages) == args.count,
        }, indent=2))
        if store.count_by_state() != {"SENT": args.count}:
            raise AssertionError("Unexpected final state")


if __name__ == "__main__":
    main()
