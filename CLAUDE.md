# Custom Android SMS Gateway — agent instructions

This repository is a maintained fork of https://github.com/capcom6/android-sms-gateway.
The authoritative project-specific scope is in `docs/custom/ROADMAP.md`.

## Problem first
- Serve a single-operator SMS workflow: monthly batches (~1,100 messages over several days) and ad hoc sending (tens per week).
- External applications call our own REST API; a paired Android phone with a SIM sends the SMS.
- The core requirement is **hard, auditable no-send intervals by weekday, hour and exceptional date**, respecting `Europe/Warsaw` and DST.
- Preserve upstream SMS sending, Room persistence, callbacks, API, and state machine unless evidence justifies a change.
- Distinguish queued on server, accepted by phone, modem send attempt, sent callback, and delivered callback.

## Engineering constraints
- Never call a production SMS API or send a real message without explicit approval.
- Any path to `SmsManager` must enforce the same hard eligibility policy, **including priority >= 100**. No bypass for expedited messages.
- Server-side scheduling alone is insufficient: a queued phone message may execute later, after a policy change.
- Global pause and blacklist periods block *new physical attempts*; attempts already handed to Android may still complete.
- Persist policy version, attempted-at timestamps, dispatch ID, and outcome. Never automatically re-send on uncertain physical outcome.
- Validate race conditions with cancellation, worker rescheduling, crashes, reboots, overlapping wake-ups, and network failure.
- Use IANA timezone; no silent fallback to disabled policy or UTC; test DST and intervals spanning midnight.
- Keep patches focused and upstream-mergeable. New behavior behind safe defaults until verified. Do not break existing local server API.
- Never commit API keys, real phone numbers, messages, SIM credentials, Firebase secrets, or signing keys. Public GitHub fork.
- No real Android deployment or release from untested code.

## Workflow
1. Read `docs/custom/ROADMAP.md` and the specific upstream Kotlin implementation before changing architecture.
2. State facts, hypotheses and invariants; select the smallest viable change.
3. Write failing tests for calendar semantics and all send entrypoints.
4. Implement, run available unit/instrumented checks, and document any environment limitation.
5. Work in feature branches, submit PRs against `ryba-22:master`; keep `upstream` remote.
6. Update `docs/custom/CHECKPOINT.md` for resumable progress.

Important upstream files:
- `app/src/main/java/me/capcom/smsgateway/modules/messages/MessagesSettings.kt`
- `app/src/main/java/me/capcom/smsgateway/modules/messages/MessagesService.kt`
- `app/src/main/java/me/capcom/smsgateway/modules/messages/MessageStateService.kt`
- `app/src/main/java/me/capcom/smsgateway/modules/localserver/routes/MessagesRoutes.kt`
- `app/src/main/java/me/capcom/smsgateway/data/dao/MessagesDao.kt`
