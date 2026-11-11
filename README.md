# NOVA AI

NOVA AI is an Android AI-character platform under active development. **NOT production-ready.** Do not publish it or process real customer purchases yet.

## Backend
- Fastify + TypeScript + PostgreSQL + Prisma; Redis is provisioned.
- Registration, login, refresh sessions, wallet ledger (purchased/ad/bonus) and transaction history.
- Character creation, private collection, cloning, edit/delete, moderation-gated catalog, favorites.
- Google Play product verification and AdMob SSV server integrations are partial, and live flows have NOT been verified.
- PostgreSQL ledger/characters and cryptographic AdMob SSV tests in GitHub Actions: 16 tests passed in run 43045591399.

## Android
- Kotlin, Jetpack Compose, Material 3, Retrofit, SDK 36.
- Screens: login, registration, home, published catalog, character creator, wallet, profile.
- App requires a real HTTPS backend address in its login form, no fake AI replies or fake billing.
- Current access token is memory-only, and refresh persistence is not implemented.
- See android/README.md for build steps. Validate APK status in GitHub Actions.

## Run backend in development
1. Copy .env.example to .env and set strong JWT_SECRET and POSTGRES_PASSWORD.
2. Run docker compose up --build.
3. Check GET http://localhost:8080/health.
4. To reach the API from the Android app use an HTTPS endpoint with a valid certificate.

The Docker Compose configuration uses NOVA_DEV_SCHEMA_SYNC=true for a **local development-only** Prisma schema sync. Production startup rejects missing versioned migrations.

## Important API endpoints
- /v1/auth/register, /v1/auth/login, /v1/auth/refresh
- /v1/characters, /v1/characters/mine, /v1/characters/catalog
- /v1/characters/:id, /v1/characters/:id/clone, /v1/characters/:id/publish, /v1/characters/:id/hide, /v1/characters/:id/favorite
- /v1/wallet, /v1/wallet/transactions
- /v1/billing/verify, /v1/ads/status, /v1/ads/ssv

## Remaining release blockers
- True AI gateway, streaming chat, memories, image generation and content safety system.
- Play Billing and AdMob Android UI, true end-to-end purchase/ad verification, refund reconciliation and abuse review.
- Production Prisma migrations, hosted TLS backend, reliable backups/recovery, monitoring.
- Admin panel, moderation workflows, terms/privacy, legal and store policy review.
- Secure session persistence, signed release keystore, Android UI and security tests, load tests.
- Production APK/AAB/ZIP are not yet verified; release signing requires owner-provided secrets.

## Starter character gallery (new)

The server seeds 12 original adults (six heroines and six male heroes) with character biography, personality, greetings and moderated public catalog entries. The seed is safe to run repeatedly and does not overwrite subsequent moderation decisions.

The Android app bundles a compressed, original photorealistic contact sheet and shows all 12 portraits on the welcome screen without login or purchases. When the backend is running, character cards also show the portraits. They are launch-preview thumbnails, not final high-resolution marketing artwork. See docs/CHARACTER_VISUAL_STYLE.md.

## AI provider foundation (not yet connected)

Backend code supports configurable, server-only OpenAI-compatible adapters for OpenRouter, Groq and Gemini. This is **not** evidence of live chat: API keys, backend chat routes, UI conversation flow and provider integration tests are still missing. The app must not show canned AI responses when no provider is configured.

## Free and Premium AI character chat (developer implementation)

NOVA AI now implements two **different chat tiers** through the same authenticated, server-owned provider gateway:

- **Free**: no NOVA Token charges and no fixed daily message cap in NOVA AI. Subject to per-minute abuse protection, service uptime, free upstream provider usage quotas, capacity and fair-use protections. Never advertise an upstream AI provider as unlimited.
- **Premium**: uses a separately configured, stronger model and longer output/context. The configured price (default `5 NOVA Tokens`) is displayed before sending. On success, spending and saving the reply commit together in PostgreSQL; failed upstream responses do not debit NOVA Tokens.
- **Safety**: no API keys in Android, per-user chat ownership checks, attempts reserved by UUID to avoid repeat requests, server-side wallet and model selection. Neither tier offers explicit/adult chat.

Configure in backend environment (never commit real keys): `AI_PROVIDER`, `AI_MODEL`, provider key, `NOVA_PREMIUM_PROVIDER`, `NOVA_PREMIUM_MODEL`, `NOVA_PREMIUM_TOKENS_PER_MESSAGE`. Premium remains disabled until a **different** model/provider is configured. Upstream costs and actual production provider limits are not represented by the token price and require a budget strategy before public launch. Real provider streaming, Android billing, hosted HTTPS backend and production security/E2E testing remain release blockers.
