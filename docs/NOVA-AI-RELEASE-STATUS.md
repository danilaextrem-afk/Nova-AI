# NOVA AI — beta release evidence and blockers

Updated: 2026-11-15. **Developer preview, not a commercial release.** This document separates code/build checks from real deployment and device validation.

## What is actually implemented

- Backend: Fastify + PostgreSQL/Prisma, authentication and refresh sessions, private character/chat ownership, character creator and catalog, AI model gateway, single/group messages, streaming, memory and story branches, token ledger and gated monetization routes.
- Android: Kotlin/Compose application, test-signed preview APK, creator, chat, catalog, wallet, selected social/media features, and instrumented UI tests using local mock HTTP responses.
- Character dialogue: PR #102 (unwarranted generic refusals), PR #103 (manual commit-bound real-model banter checks) and PR #105 (atomic buffering of speculative streaming recovery) have been merged into main as of this update.
- None of those statements alone proves that the deployed Render backend contains the new code.

## Where to check independent evidence

| Evidence | Means | Does not mean |
| --- | --- | --- |
| GitHub `CI` success on latest `main` | Backend/Android builds, unit and mocked UI tests, Docker migration smoke test passed | The deployed backend has updated or real provider output is good |
| Scheduled `Live check` **uptime** success | Target API was available when probed | The deployed commit equals main or real Android E2E passed |
| Push/manual `Live check` **release validation** success | The deployed commit is the expected SHA and live smoke validation passed | A paid purchase or public store release is approved |
| `Android E2E (expected deployed commit)` success | Live Android scenario passed following release validation | A real user's phone, all providers, or full regulatory requirements are tested |
| `nova-preview` GitHub prerelease | Test-signed APK and source ZIP from a fully green CI | Production signing, payment readiness or server synchronization |
| `deployment-status.json` attached to preview | Observed backend SHA and health/readiness at APK publication time | A replacement for real-model and Android E2E test reports |

**Important:** after a main push, a preview APK can be published while Render is still on a different commit. The preview publishing workflow must label this as `STALE_BACKEND`, `UNAVAILABLE` or `UNVERIFIED`, not as beta-ready. `COMMIT_MATCHED` only confirms version and health/readiness at one point in time.

## Snapshot on 2026-11-15 (check fresh before using)

- Last known main before this documentation change: `a230ee7` (PR #105).
- Live Render endpoint: `https://nova-ai-api-bxlk.onrender.com`.
- Most recently independently observed Render version: `4605f3d`, and intermittent HTTP 503. This is a **stale deployment** relative to `main`.
- A scheduled uptime job may be green while release validation is red; that is expected and truthful.
- Current P0 owner/infrastructure issue: [#104](https://github.com/danilaextrem-afk/Nova-AI/issues/104).
- The existing preview download is a **test APK** signed with a committed test key, not a production upload signing key.

## Fastest path to a usable Android beta

1. **Sol / Render service owner:** inspect the actual Render service linked repository/branch, auto-deploy mode, build/deploy logs and environment. Fix the stale backend. No new hosting charges or secret replacement without owner approval. A GitHub commit alone cannot correct an unrelated Render dashboard configuration.
2. Confirm `/health` HTTP 200 with `commit` matching the current main SHA prefix and `/ready` HTTP 200. `health` with an old SHA is **not** a successful release.
3. Run GitHub **Live check → workflow_dispatch** with the exact expected commit; require successful release validation and subsequent Android live E2E.
4. Run GitHub **Dialogue quality → workflow_dispatch**, `mode=banter`, `expect=<deployed 7-char SHA>`. This creates and deletes a disposable account and tests real model responses to ordinary slang/flirting. It does not test or promise unrestricted explicit content.
5. Build/install the test-signed APK on real Android devices. Verify login, one-sentence creator, memory, conversations, images, chat history, logout, and recovery from disconnected or suspended networks.
6. Track failures with reproducible logs, test SHA and device version. Do not advertise production readiness until payment, age/content safeguards, moderation, backups, store policies, security and production signing are separately validated.

## Preview / production limitations

- Preview Render Free database has expiry and is not suitable for real customer balances or purchases; backup/restore must be tested against a durable database before public release.
- Provider costs/rate limits apply even if chat is free to users. Retry recovery is limited to short safe messages; it does **not** bypass upstream content filtering.
- Mature character banter/romance is still bound by provider policies; graphic adult content support must not be claimed without provider, platform and age-gating validation.
- Purchases, AdMob rewards, refunds, admin moderation, service scalability and abuse controls need independent live and security testing.
- The `NOVA-AI-FINAL.apk` filename is historical; the published file is only a **preview**. CI uses a public TEST signing key. Never use it for a store submission.
