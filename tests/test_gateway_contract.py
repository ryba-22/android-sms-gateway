"""No-network contract checks for the Android Local Server adapter."""
import unittest
from unittest.mock import patch

from dispatch.gateway_client import AndroidLocalServer
from dispatch.outbox import GatewayResponse


class AndroidGatewayContractTest(unittest.TestCase):
    def setUp(self):
        self.client = AndroidLocalServer(
            "https://phone.example.test:443", username="placeholder", password="test-secret"
        )

    def test_post_is_one_recipient_same_id_with_low_priority(self):
        with patch.object(self.client, "_request", return_value=GatewayResponse(202, {"id": "key-one"})) as call:
            result = self.client.post_message("key-one", "+480000000001", "synthetic only")
        self.assertEqual(result.status, 202)
        method, path, payload = call.call_args.args
        self.assertEqual((method, path), ("POST", "/messages"))
        self.assertEqual(payload["id"], "key-one")
        self.assertEqual(payload["phoneNumbers"], ["+480000000001"])
        self.assertEqual(payload["textMessage"], {"text": "synthetic only"})
        self.assertEqual(payload["priority"], 0)
        self.assertTrue(payload["withDeliveryReport"])

    def test_attempt_response_list_is_normalized(self):
        with patch.object(self.client, "_request",
                          return_value=GatewayResponse(200, [{"attemptId": "a", "state": "UNKNOWN"}])):
            response = self.client.get_attempts("safe-id")
        self.assertEqual(response.payload["attempts"][0]["state"], "UNKNOWN")

    def test_uses_read_only_get_for_status_lookup(self):
        with patch.object(self.client, "_request",
                          return_value=GatewayResponse(404, {})) as call:
            self.client.get_message("one")
        self.assertEqual(call.call_args.args, ("GET", "/messages/one"))

    def test_query_path_is_encoded_not_interpreted_as_url(self):
        with patch.object(self.client, "_request",
                          return_value=GatewayResponse(404, {})) as call:
            self.client.get_message("id /?!")
        self.assertEqual(call.call_args.args, ("GET", "/messages/id%20%2F%3F%21"))


if __name__ == "__main__":
    unittest.main()
