# SMS Dispatch for own Android phone — scope and decisions

Date: 2026-11-05. Fork: `ryba-22/android-sms-gateway`. Upstream: `capcom6/android-sms-gateway`.

**Execution plan (2026-11-09):** [IMPLEMENTATION_PLAN_2026-11-09.md](IMPLEMENTATION_PLAN_2026-11-09.md) — small PRs, invariants, tests, risks and acceptance gates.

## Reality / intent

Monthly batches of approximately 1,100 SMS can run over multiple days. Ad hoc messages (roughly 10–50) need to be queued quickly during the week. External clients (Excel, CRD, n8n and a compact operator UI) should submit over one API. The Android phone uses its SIM to send. Full independence from SMS8 and control over *when no message may be sent* matter more than throughput.

## Facts from upstream v1.78.x

- Android Local Server exists; messages can be queued with caller-provided `id`, `scheduleAt`, priority, SIM and optional delivery reports. The local endpoint rejects duplicate message IDs with HTTP 409.
- Room persists messages; state callbacks are handled in Kotlin; there is an extracted `MessageStateService`.
- Existing Working Hours consists of enabled/start/end only, a single daily window read from the phone's default timezone.
- `MessagesService.sendPendingMessages` skips Working Hours for `priority >= 100`.
- Cancellations and sent/delivered events have recent race-condition fixes. This is evidence of engineering activity, **not** proof of end-to-end exactly-once SMS delivery.
- Local gateway can independently hold a queue; stopping only new server enqueues cannot guarantee a no-send period.

Source: upstream `MessagesSettings.kt`, `MessagesService.kt`, `MessagesRoutes.kt`, `MessageStateService.kt`, PR #390 and PR #424.

## Invariants — must remain true

1. No **new physical SMS attempt** starts while global pause, campaign pause, blocked date/interval or outside permitted windows applies.
2. Every execution path, including priority >=100, FCM, worker wakeup, retry and scheduled messages, must obey the same gate immediately before sending.
3. A policy change during an existing phone queue affects the next unsent message; reevaluate at execution time, not only enqueue time.
4. Server request accepted != phone queued != physical attempt != Android sent callback != delivery report.
5. If a crash occurs after a physical send attempt but before acknowledgement, mark/reconcile `Unknown`; never blindly retry a potentially transmitted SMS.
6. Only one logical dispatch ID per message; retries of HTTP enqueue use the same ID. A 409 triggers read/reconciliation, not a new send.
7. No API credentials or message contents in Git, public CI logs, or unredacted error telemetry.

## Proposed sending policy model (hypothesis for first implementation)

`SendingPolicy { timezone, weeklyAllowedWindows[], blackoutDates[], blackoutIntervals[], globalPaused, version }`

- `weeklyAllowedWindows`: weekday + local start/end, multiple disjoint windows allowed.
- `blackoutDates`: whole local calendar dates; manual holidays and exceptional days; do not assume all national holidays are automatically closed.
- `blackoutIntervals`: date/time range or time window on a specific date; must override positive windows.
- `timezone`: explicit IANA name, default operator choice `Europe/Warsaw` but no fallback to UTC when invalid.
- `globalPaused`: authoritative block for new attempts until operator resumes.
- Precedence: `pause > blackout > weekly allowed window > reject/defer`. No implicit "express" bypass.
- If policy is invalid, unavailable or not yet applied: **do not start new physical attempts**. The device can use a previously confirmed, versioned policy only under an explicit freshness contract.
- Time windows are interpreted as half-open `[start, end)`, including midnight crossings and DST. Decide and test fall-back repeated hour behavior.
- Persist each scheduling decision with policy version and time for audit/reconstruction.
- Implement in small dedicated policy module; do not overload `MessagesSettings` with a second orchestration subsystem.

This model is not yet an implementation. Upstream's existing Working Hours should remain intact until a replacement is tested.

## Thin API/dispatcher (separate concern, later phase)

The own self-hosted backend owns campaign/request acceptance, schedule, recipient de-duplication, operator approval, and audit. The Android phone owns the actual SIM operation and Android callbacks. Connect to Android Local Server using a trusted LAN/VPN and authenticated TLS; never open phone API unrestricted on the public internet.

Endpoints proposed on **our backend**, not claimed existing upstream endpoints:
- `POST /api/v1/messages` with `Idempotency-Key`, phone, text, optional campaign, optional not-before.
- `POST /api/v1/campaigns` preview + approve.
- `POST /api/v1/pause` / `POST /api/v1/resume`.
- `GET /api/v1/messages/{id}`, `GET /api/v1/queue`, `GET /api/v1/policy`.
- `PUT /api/v1/policy` under explicit operator authorization.

Send one message at a time, with stable ID and status reconciliation. Do not push an entire monthly campaign to the device queue in advance, especially before a long blackout. For a phone offline/paused: hold the message on server; do not mark it failed.

## Delivery phases

**Phase 0 — baseline (now):** fork, clone, upstream remote, evidence-backed plan, safe feature branch, build/toolchain check. No changes to SMS dispatch.

**Phase 1 — hard local policy:** implement standalone policy evaluator + tests; add per-weekday windows, blackout dates and intervals; enforce before every `SmsManager` attempt, incl. priority >=100, with deterministic time injection. Add pause semantics and a reliable next-allowed-time calculation.

**Phase 2 — control interface:** safe policy read/update on phone or versioned push from our backend; validate + persist before acknowledgement. Audit API authentication and policy propagation during reconnect/reboot.

**Phase 3 — own dispatcher:** PostgreSQL + one worker, campaign and ad hoc ingestion, one authenticated adapter to Local Server, stable IDs, backpressure and status reconciliation. No extra broker by default.

**Phase 4 — operator UX:** compact table, CSV/Excel preview, approval, sending calendar, pause/resume, daily progress and downloadable audit.

**Phase 5 — verification:** isolated device/test SIM; test timezone switch, DST, loss of Internet/WiFi, reboot, pause while sending, power loss after modem call, duplicate enqueue, delivery receipt, no-signal, queue resume and repeated messages. Only then consider real campaign migration.

## First-pass acceptance cases

- Monday 09:00 allowed; Monday 08:59 blocked; weekday 21:00 blocked (according to configured window).
- Saturday/Sunday blocked when not in weeklyAllowedWindows.
- Wednesday 12:00–15:00 blackout overrides Wednesday 09:00–18:00 allow.
- Entire 11 November blocked if explicitly listed as exception.
- After blackout, waiting messages resume at next valid window, preserving order and idempotency.
- New policy applied while worker sleeps; worker must reevaluate on wake.
- Priority 100/127 cannot bypass hard blocks.
- Timezone Europe/Warsaw handles March/October DST and overnight periods.
- Pause blocks new physical attempts while ongoing Android send callbacks can still arrive.
- Crash after `SmsManager` but before status report does not silently schedule another physical attempt.
- Duplicate POST with same ID remains one logical message; HTTP timeout followed by 409 requires reconcile.

## Open questions

1. Preferred phone and Android version for isolated test bench?
2. Own backend location (always-on home/office box or remote VPS)? Network conditions matter to Local Server.
3. Default weekly allowed windows and dates to exclude (operator configurable; not hardcode a guessed schedule).
4. Is a "force-send" administrative override ever allowed? **Default: no** for hard blocks; separate emergency authorization if later needed.
5. Status retention and privacy policy for phone numbers/SMS bodies, including who can access logs.
6. Does operator SIM contract allow the expected monthly volume? Project README warns about carrier restrictions.

## Keep upstream patches small

Do not change application ID, signing, Firebase or branding until the build and isolated phone have been verified. Upstream is Apache-2.0; retain attribution. Preserve remote `upstream` and rebase/review upstream changes; avoid one giant permanent fork diff.
