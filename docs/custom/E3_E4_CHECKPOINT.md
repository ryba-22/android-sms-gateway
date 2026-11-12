# E3/E4 checkpoint — Android policy and durable send attempts

Branch: feat/hard-sending-policy-implementation. PR #7 (Draft, not approved to merge/deploy).

## Changes

- E3: authenticated /policy read, versioned PUT, pause and resume.
- E4: MessageAttempt and MessageAttemptPart persisted in Room v26; additive migration 25 -> 26.
- Before each modem invocation, Room atomically reserves the recipient, recording attemptId and policy version.
- One reservation per (messageId, phoneNumber). A restart cannot re-open a reserved recipient for automatic sending.
- SMS text, multipart and data use distinct per-part PendingIntents keyed by attemptId and partIndex.
- The logical SMS becomes SENT only after all parts report success; mixed results stay UNKNOWN.
- Old RESERVED/INVOKING attempts become UNKNOWN after five minutes; later callbacks can still reconcile them.
- Only a synchronous gate denial before sendFn starts can release a reservation and requeue.
- Absent/invalid policy denies all; policy installation outside hours schedules wakeup at the next allowed instant.
- Read-only attempt history: GET /messages/{id}/attempts with MessagesRead authorization.

## Policy API (example, not for production use)

All routes are behind Local Server Basic/JWT authentication.
GET /policy requires SettingsRead; mutations require SettingsWrite.

PUT /policy body:

    {
      "version": 1,
      "timezone": "Europe/Warsaw",
      "weeklyAllowedWindows": [
        {"day":"MONDAY","start":"08:00","endExclusive":"21:00"},
        {"day":"TUESDAY","start":"08:00","endExclusive":"21:00"},
        {"day":"WEDNESDAY","start":"08:00","endExclusive":"21:00"},
        {"day":"THURSDAY","start":"08:00","endExclusive":"21:00"},
        {"day":"FRIDAY","start":"08:00","endExclusive":"21:00"}
      ],
      "blackoutDates": [],
      "blackoutIntervals": [],
      "paused": false
    }

POST /policy/pause body: {"expectedVersion":1} => version 2, paused.
POST /policy/resume body: {"expectedVersion":2} => version 3, resumed.

Versions must increase. A 200 response indicates a committed SharedPreferences policy change. The scheduled worker may wake later; it always checks the policy again.

## Constraints and NO-GO

- No Samsung device / Doze / reboot / SMS radio tests have run yet. JVM success does not validate physical behavior.
- MMS is intentionally blocked in the safe send path. Its group-atomic physical attempt still needs design and tests.
- UNKNOWN recovery is read-only: do not delete records or re-submit old logical IDs to force re-sends.
- Exactly-once carrier delivery cannot be promised; our contract prevents a second application-level modem invocation after durable reservation.
- Room v26 upgrade requires an isolated device check and a backup. Downgrading database schema is not supported.
- The original upstream Release GitHub workflow can report startup_failure without production signing secrets; only dedicated no-SMS Android validation pertains to debug build.
- Phone numbers are visible in authenticated attempt history; privacy, retention and access require a release review.
- Cancellation concurrently with a reserved modem attempt is a remaining race to test.
- No APK installed, no test or production SMS sent, and no SMS8 changes made.

Acceptance: successful debug build and Room tests (including v25-v26 migration), then dedicated-device fault injection (pause, 21:00 cutoff, 02:00 reconnect, stale attempts, multipart, timeout, Android reboot), then explicit operator approval for a limited pilot. Until then GO = NO.
