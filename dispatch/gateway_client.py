"""Android Local Server client, never instantiated by the synthetic dry-run CLI.

Host-side transport errors are deliberately treated as indeterminate. A timeout
does not prove that the Android endpoint failed to persist a message.
"""
from __future__ import annotations

import base64
import ipaddress
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

from .outbox import GatewayResponse


class AndroidLocalServer:
    def __init__(
        self,
        base_url: str,
        *,
        username: str,
        password: str,
        allow_private_http: bool = False,
        timeout: int = 8,
    ):
        url = urlsplit(base_url)
        if not username or not password or not url.hostname or url.username or url.password:
            raise ValueError("Invalid endpoint or missing credentials")
        if url.query or url.fragment or url.path not in ("", "/"):
            raise ValueError("Android endpoint must be a bare origin without query/path")
        if url.scheme != "https":
            ip = None
            try:
                ip = ipaddress.ip_address(url.hostname)
            except ValueError:
                pass
            if not (allow_private_http and url.scheme == "http" and ip is not None
                    and (ip.is_private or ip.is_loopback)):
                raise ValueError("HTTPS required except explicitly enabled private-IP HTTP")
        if timeout < 1 or timeout > 30:
            raise ValueError("Unsupported timeout")
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        credentials = f"{username}:{password}".encode("utf-8")
        self.auth_header = "Basic " + base64.b64encode(credentials).decode("ascii")

    def _request(self, method: str, path: str, body: dict | None = None) -> GatewayResponse:
        if not path.startswith("/") or "//" in path:
            raise ValueError("Invalid path")
        data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
        request = Request(
            self.base_url + path,
            method=method,
            data=data,
            headers={
                "Authorization": self.auth_header,
                "Accept": "application/json",
                "Content-Type": "application/json",
            },
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:
                return GatewayResponse(response.status, json.loads(response.read(2_000_000)))
        except HTTPError as exc:
            # 409 has semantic meaning: the message ID may already exist.
            raw = exc.read(65_536)
            try:
                parsed = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                parsed = {}
            return GatewayResponse(exc.code, parsed)

    def policy(self) -> dict:
        response = self._request("GET", "/policy")
        if response.status != 200 or not isinstance(response.payload, dict):
            raise RuntimeError("Android policy preflight unavailable")
        return response.payload

    def post_message(self, message_id: str, phone: str, body: str) -> GatewayResponse:
        return self._request(
            "POST", "/messages",
            {
                "id": message_id,
                "phoneNumbers": [phone],
                "textMessage": {"text": body},
                "priority": 0,
                "withDeliveryReport": True,
            },
        )

    def get_message(self, message_id: str) -> GatewayResponse:
        return self._request("GET", "/messages/" + quote(message_id, safe=""))

    def get_attempts(self, message_id: str) -> GatewayResponse:
        response = self._request("GET", "/messages/" + quote(message_id, safe="") + "/attempts")
        if response.status == 200 and isinstance(response.payload, list):
            return GatewayResponse(200, {"attempts": response.payload})
        return response
