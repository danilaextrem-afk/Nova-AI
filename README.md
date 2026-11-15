# NOVA AI

An Android app for chats and fictional AI characters. **Under active development; not production-ready.** The preview APK is test-signed and must not be used for Play Store publication or real payments.

## Implementation

- **Backend:** Fastify, TypeScript, PostgreSQL, Prisma, session/token authentication, character catalog and creator, AI gateway for configurable model providers, persisted streamed chat, memory, multi-character conversations, branching scenes, wallet ledger, moderation controls and guarded community APIs.
- **Android:** Kotlin, Jetpack Compose and Retrofit; character catalog/creator, chats, profiles, wallet and other preview screens. Mock-server UI tests run on Android emulators in CI.
- **Dialogue:** natural character response prompts, limited retry for benign messages that receive a stock refusal, and atomic streaming recovery. Upstream provider rules remain in force; explicit adult content is not guaranteed or advertised.
- **Tests:** backend TypeScript/unit/integration, Android unit/UI, container migration and restart smoke tests. A CI pass is not evidence that the public Render service has received the new commit.

For verified status and unresolved blockers, see [beta release evidence](docs/NOVA-AI-RELEASE-STATUS.md).

## Get the preview APK

The rolling [NOVA AI prerelease](https://github.com/danilaextrem-afk/Nova-AI/releases/tag/nova-preview) includes an installable TEST-signed `NOVA-AI-FINAL.apk` and a source ZIP. The name of the APK is historical; this is **not a final commercial release**.

A green CI publishes the APK even if Render is offline or running old code. Read the release notes and `deployment-status.json` before testing: `COMMIT_MATCHED` indicates the deployed backend SHA matches the build at the time of the check; `STALE_BACKEND` or `UNAVAILABLE` means the current APK has **not** been verified with the expected backend. Even matching SHA does not replace live AI / Android E2E validation.

## Run a local backend

1. Copy `backend/.env.example` to `backend/.env` if present and configure your own strong server credentials; never commit provider API keys, JWT secrets or database passwords.
2. Follow the local Docker instructions to run the backend and PostgreSQL.
3. Confirm `GET /health`, `GET /ready` and the migration status.
4. Run backend tests against an isolated test database. Never use test cleanup scripts against a real user database.

The committed `render.yaml` describes a **short-lived developer preview** on Render Free, not a production deployment. Backend keys remain server-side. Render dashboard access is needed to repair deployment configuration if it points to a stale revision.

## Release checks

- **CI:** runs on changes to GitHub; checks code, tests, Android build/UI and Docker migration.
- **Live check (scheduled uptime):** checks basic server availability, not release readiness.
- **Live check (push/manual release):** requires deployed SHA and live integration validation; the Android live E2E stage depends on it.
- **Dialogue quality (manual):** run with `mode=banter` and `expect=<deployed SHA>` to test benign Russian profanity/flirting against the real configured model, plus free token charges and message persistence. It creates and removes a disposable test account.

## Before public release

A real customer launch still needs a stable backend with durable backups, security and privacy review, safe moderation/content policies, verified store billing and ads, provider budget and quota planning, production signing, load testing and real-device acceptance tests.

See [Render preview setup](docs/DEPLOY_RENDER_PREVIEW.md) and [release status](docs/NOVA-AI-RELEASE-STATUS.md).
