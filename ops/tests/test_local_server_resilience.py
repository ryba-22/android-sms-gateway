"""Non-SMS regression guard for Android Local Server lifecycle.

These source-contract checks do not replace actual Samsung reboot/Doze tests.
"""
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/me/capcom/smsgateway"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class LocalServerResilienceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
        cls.web = (SRC / "modules/localserver/WebService.kt").read_text(encoding="utf8")
        cls.boot = (SRC / "receivers/BootReceiver.kt").read_text(encoding="utf8")
        cls.orchestrator = (SRC / "modules/orchestrator/OrchestratorService.kt").read_text(encoding="utf8")

    def test_service_not_exported_to_other_apps(self):
        services = self.manifest.findall("./application/service")
        server = [s for s in services if (s.get(ANDROID + "name") or "").endswith(".WebService")]
        self.assertEqual(len(server), 1)
        self.assertEqual(server[0].get(ANDROID + "exported"), "false")

    def test_shutdown_does_not_relaunch_gateway(self):
        boot = [r for r in self.manifest.findall("./application/receiver")
                if (r.get(ANDROID + "name") or "").endswith(".BootReceiver")]
        self.assertEqual(len(boot), 1)
        actions = [a.get(ANDROID + "name") for a in boot[0].findall(".//action")]
        self.assertIn("android.intent.action.BOOT_COMPLETED", actions)
        self.assertNotIn("android.intent.action.ACTION_SHUTDOWN", actions)
        self.assertNotIn("Intent.ACTION_SHUTDOWN", self.boot)

    def test_foreground_notification_precedes_http_server(self):
        start = self.web.index("override fun onCreate()")
        end = self.web.index("override fun onStartCommand(", start)
        create = self.web[start:end]
        self.assertLess(create.index("promoteToForeground()"), create.index("server.start()"))
        self.assertIn("START_STICKY", self.web[end:self.web.index("private fun promoteToForeground()", end)])

    def test_cleanup_is_guarded_when_startup_fails(self):
        shutdown = self.web[self.web.index("override fun onDestroy()"):]
        self.assertIn("if (wifiLock.isHeld)", shutdown)
        self.assertIn("if (wakeLock.isHeld)", shutdown)
        self.assertIn("if (serverStarted)", shutdown)

    def test_local_server_failure_does_not_suppress_independent_ping(self):
        block = self.orchestrator[self.orchestrator.index("localServerSvc.start(context)"):]
        self.assertRegex(block, r"(?s)catch \(e: Exception\).*?Local Server failed to start")
        self.assertRegex(block, r"(?s)pingSvc.start\(context\).*?catch \(e: Exception\)")
        self.assertIn("Ping service failed to start", block)


if __name__ == "__main__":
    unittest.main()
