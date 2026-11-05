# Custom fork checkpoint — 2026-11-05

- Fork created: https://github.com/ryba-22/android-sms-gateway (public, upstream capcom6/android-sms-gateway).
- Local clone: `/home/ryba-22/lab/android-sms-gateway` on computer Patryk.
- `origin`: own fork, `upstream`: original project.
- Baseline cloned at commit `adaa6c41`, upstream master as of 2026-11-05; latest stable verified before fork: v1.78.1.
- Planning branch: `docs/custom-sms-roadmap`. No SMS sending changes or tests executed yet.
- Java 17 present. Android SDK not found at usual paths; validate SDK/toolchain separately.
- Requirements and decisions are in `docs/custom/ROADMAP.md`.
- Next engineering task: confirm Android toolchain, create isolated branch `feat/hard-sending-calendar`, write first parameterized tests for weekday windows/blackouts/priority bypass, then implement smallest enforcement change.
- Safety: no APK installed, no test SMS sent, no production API contacted.
