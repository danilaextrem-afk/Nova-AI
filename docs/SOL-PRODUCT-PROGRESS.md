# NOVA AI Android product experience

Branch: `sol/nova-product-experience`. Started from `main` at `de6181b`.

- Discovery uses the API for the featured portrait, recommendations, curated collections, and recent conversations. A favorite swipe keeps the card on screen until the server confirms the write.
- Catalog has separate public, favorite, and following views, search, sorting, filters, 18+ controls, and loading, empty, and retry states.
- Character profiles have full-screen portraits, stories, sharing, and discussion. Author pages show server-confirmed following state and published characters. Reports and blocks use existing server endpoints.
- Account and wallet display notifications, content preferences, support, privacy controls, balances, server-provided Premium pricing, rewards, referrals, and transactions. Existing payment rules and feature flags remain in effect.
- AI gateway, memory, voice, editor, portrait upload, and deployment remain under the parallel workstream.

Validation is tracked in [PR #45](https://github.com/danilaextrem-afk/Nova-AI/pull/45) and GitHub Actions. The Android job runs UI and E2E checks on two emulator sizes and collects screenshots in its evidence artifact.
