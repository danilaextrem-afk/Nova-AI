# NOVA visual system — Android

Graphite `#0C0B14`, surface `#191623`, lavender `#C3A6FF`, rose `#F3B3CF`. Editorial serif headlines contrast with readable sans-serif interface text. Rounded 20–28 dp surfaces, restrained borders and gradients, 48 dp Material touch targets. Existing screens, requests and server prices are retained.

`NovaVisualSystem.kt` owns theme, portraits, wordmark, editorial covers and typing animation. `NovaConversation.kt` keeps the composer separate from the lazy message list; keyboard insets and a chat-specific header leave room on narrow screens. Streaming, retry identifiers, charging and encrypted drafts stay in the existing ViewModel. Message edit, regeneration, branching, deletion, speech, reporting and memory remain accessible.

## Original portrait collection

Twelve original fictional adult women, ages 25–36, were generated individually for NOVA on 2026-11-12. No reference photographs or celebrity likenesses were supplied. Prompts requested realistic skin and hair, natural portrait light, contemporary fully clothed outfits, no nudity, no explicit content, no logos or text. The complete collection was visually reviewed before inclusion. Names, biographies, personalities, greetings and adult ages are declared in `StudioCast.kt`.

Assets `201.webp`–`212.webp` are 1024×1536 WebP at quality 87, 1.83 MiB combined. Coil decodes and caches them off the main thread; portrait scaling is Crop with top-centre alignment, never stretched. Existing portraits `101.webp`–`112.webp` are unchanged. The app recognizes its own curated asset URLs locally; immutable commit URLs also let the existing server retain the portrait reference.

The collection appears only after the existing account adult confirmation and Mature section selection. Opening a profile does not publish it. Starting a story creates a personal character through the existing authenticated endpoint, with `mature=true`; public publication remains subject to the existing moderation flow. This is an Android curated collection, not a new globally published server seed catalogue.

## Acceptance and evidence

- Existing login, streaming, regeneration, message editing, encrypted draft restoration, network retry, pending moderation and daily reward UI regressions must pass.
- All 24 bundled portraits must decode; all 12 new profiles must have unique names and adult ages.
- Adult confirmation precedes collection access. Starting a Studio story must send the existing character/chat payloads and restore the session after recreation.
- Navigation and screenshots cover welcome/login, home, search/catalogue, character profiles, chat, creation, wallet, rewards, referrals, account/support, Mature collection, favourites and Premium availability.
- Run on API 35 at standard Pixel 2 dimensions and 480×800/240 dpi. HTTP fixtures are explicitly test data, not live AI/payment verification.

No backend files, production secrets, payment flags or deployment settings are modified by this redesign.
