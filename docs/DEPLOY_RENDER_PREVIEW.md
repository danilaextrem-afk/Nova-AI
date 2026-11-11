# NOVA AI — Render preview deployment

**Developer preview only. Do not process real payments on this setup.** Render Free PostgreSQL expires after 30 days and provides no managed backups; free web services may sleep.

## Automated by render.yaml
- Provisions a free HTTPS web API on Render and a free PostgreSQL database for a short-lived preview.
- Links DATABASE_URL to the database and randomly generates JWT_SECRET.
- Builds backend/Dockerfile and applies versioned Prisma migrations before server startup.
- Creates the twelve original starter characters automatically.
- Sets AI_PROVIDER=openrouter and AI_MODEL=openrouter/free; the API key stays server-side only.

## Remaining external authorization
1. Connect Render to your own account and grant it access to the private NOVA AI GitHub repository.
2. Deploy the Blueprint in the repository root (render.yaml).
3. OpenRouter still requires an API key even for free models. Supply OPENROUTER_API_KEY only in Render's secret field; never paste it into chats, APKs, or GitHub.
4. After Render provides a real HTTPS URL, configure the nonsecret GitHub Actions variable NOVA_API_BASE_URL and rebuild the Android APK. The app will embed this endpoint; no manual URL typing for end users.

Once the Render connector is linked, an authorized agent can complete supported cloud operations through that connection. Without cloud and provider authorization, external accounts cannot be provisioned on the user's behalf.

## Verify live behavior
- GET /health returns {ok:true} when the API is up.
- Registration, character catalog and chats require a hosted PostgreSQL backend.
- GET /v1/ai/status must say configured=true after the API key is installed.
- A real POST /v1/chats/{id}/messages must receive a provider response and persist chat messages.
- Error 429 means upstream rate limited. Free NOVA chat is unlimited by message count in-app, **not** unlimited provider compute.

## Production launch blockers
Use a durable PostgreSQL database with backups and monitoring, operational budget, content safety/moderation, subscription/billing verification, safe session persistence, security reviews, and signed store builds. A free Render preview is not production-ready.
