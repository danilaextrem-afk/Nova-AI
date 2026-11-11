# NOVA AI — MiraiMind Feature Parity Audit
**Research date:** 2026-11-11  |  **Goal:** Original, age-appropriate feature-complete AI character platform, not a source/branding clone.

> Статусы перепроверены; доказательства и ограничения каждого пункта: [`NOVA-AI-MIRAIMIND-COMPARISON.md`](NOVA-AI-MIRAIMIND-COMPARISON.md) (2026-11-11).

## Research method and caveats
Compared public MiraiMind first-party guide pages (January 15, 2026), developer's feature announcements (2024–2026), Google Play listing (updated August 5, 2026), and developer membership release (November 4, 2025). Some capabilities may vary by country, subscription and product version. App internals and real provider costs are not observable from public pages. All parity claims must be functional E2E tests, not visual checkboxes.

### Status legend
- **FOUNDATION** = existing NOVA AI code, still needing deployed-backend/device verification.
- **PARTIAL** = some backend/UI, not a complete end-to-end user flow.
- **MISSING** = not implemented. Features here are **requirements**, not completed work.

## Feature inventory: target original NOVA AI implementation

| ID | Module | Functional feature required | NOVA status | Acceptance criteria |
|---|---|---|---|---|
| D01 | Discover | Character card catalog with cover art and short intro | Проверено на стенде | Backend returns published moderated characters; Android displays image, intro and metadata |
| D02 | Discover | Swipe left/right discovery interface | Отсутствует | Gestures, accessibility alternative, no duplicates until page exhausted |
| D03 | Discover | Search by character name and description | В коде; устройство не проверено | Server-side search, no private character data returned |
| D04 | Discover | Tag and category filters | В коде; устройство не проверено | Server pagination, category and tags filters combine correctly |
| D05 | Discover | Sort by recent, rating, trending, featured | Частично | Server metrics with spam-resistant ranking and clear sorts |
| D06 | Discover | Character details (bio, tags, author, scenes) | Частично | Dedicated rich profile screen and shareable deep link |
| D07 | Discover | Favorite/collection | В коде; устройство не проверено | Add/remove/list persisted, sync across devices |
| D08 | Discover | Share links and comments | Частично | Share a public character; moderated comments only |
| C01 | Character | Create character with name, traits, history, language | В коде; устройство не проверено | All schema fields editable in Android; API validation |
| C02 | Character | Appearance, avatar upload, generation, crop | Отсутствует | Moderated asset pipeline, S3-style store, signed URLs |
| C03 | Character | Custom voice and speech style | Внешний доступ / частично | Voice presets, preview, no cloning real persons without authorization |
| C04 | Character | Introduction, prologue, first message | В коде; устройство не проверено | Both supported and shown in chat and card |
| C05 | Character | Multiple story scenarios and alternate universes | Частично | Per-character variants with independent state |
| C06 | Character | Player role/persona card | В коде; устройство не проверено | Name, identity, role and scene supplied on user consent |
| C07 | Character | Save drafts, edit, clone, hide, publish | Частично | Full Android controls; moderation before public listing |
| C08 | Character | Paid creator characters / unlock entitlement | Отсутствует | Play-compliant entitlement, moderation, transparent terms |
| C09 | Character | Starter library of original safe characters | Проверено на стенде | Curated original art/licensed images; no third-party character cloning |
| H01 | Chat | Text conversations with true model gateway | Внешний доступ / частично | AI output from configured provider, not canned responses |
| H02 | Chat | Streaming with cancel/retry/failure states | Проверено на стенде | SSE/WS+abort, retries without duplicate charging |
| H03 | Chat | Chat history and search | Частично | Per-user IDOR protection and infinite paging |
| H04 | Chat | Edit, regenerate, pin, branch/replay | Частично | Deterministic states; audit of revisions |
| H05 | Chat | Multiple model-quality modes | Проверено на стенде | Free/basic and premium model tiers, actual provider routing |
| H06 | Chat | Reply style: concise/balanced/story | В коде; устройство не проверено | Token and length bounds, scene/narration ratio |
| H07 | Chat | Character initiative and tone controls | Частично | Controls survive restart and are reflected in prompts |
| H08 | Chat | Worldbuilding & interactive branching plots | Частично | Scene state, branching, checkpoints, replay |
| H09 | Chat | Group chat (up to 5 AI characters target) | Частично | Multi-agent orchestration and coherent turn order |
| H10 | Chat | Group chat member selection and theme | Частично | Member roles, private/public controls, room management |
| M01 | Memory | Context summary & long-term memory | В коде; устройство не проверено | Regular summarization and relevance retrieval |
| M02 | Memory | Manual add/edit/delete/pin memory | Проверено на стенде | User-owned and deletable memory records |
| M03 | Memory | Memory scopes: character, chat, story | Частично | Explicit scope and isolation between stories |
| M04 | Memory | Forgetting policy, memory transparency | В коде; устройство не проверено | View/export/delete memories, no hidden sensitive profiling |
| V01 | Voice | TTS play button on messages | В коде; устройство не проверено | Audio from server provider, pause/replay, accessible captions |
| V02 | Voice | Voice presets, pronunciation, revoice | Внешний доступ / частично | Voice licensing and safety validation |
| V03 | Voice | Live AI voice conversation | Внешний доступ / частично | STT/streaming TTS, mute, hang up, latency/errors |
| V04 | Voice | Optional voice notes/transcription | В коде; устройство не проверено | Consent and user-controlled audio retention |
| A01 | Art | Text to image with real provider | Отсутствует | Actual output, no pretend generated image |
| A02 | Art | Templates/AI prompt optimization | Отсутствует | Predefined style and shot templates |
| A03 | Art | Style presets, negative prompts | Отсутствует | Input filters, preview of price |
| A04 | Art | Img2Img reference guidance | Отсутствует | License/consent checks, upload limits, safety filters |
| A05 | Art | Image history, gallery, share, regenerate | Отсутствует | Persistent media records, deletion and moderation |
| A06 | Art | Optional face repair and own style adapters | Отсутствует | Consent, filters, avoid misleading impersonation |
| L01 | Fictional life | Character fictional timeline/feed and diary | Частично | Clearly labeled generated fiction with timestamps |
| L02 | Fictional life | Simulated 'phone' search/chat/shopping pages | Частично | In-world fictional data, not actual private data from people |
| L03 | Fictional life | Inner monologue / thoughts | Частично | Labeled creative narration, no claim to reveal actual model thoughts |
| L04 | Fictional life | Daily events & hobby posts | Частично | Consistent with character canon, rate limited |
| S01 | Social | User profiles & creator follows | Частично | Privacy controls, account settings, blocking |
| S02 | Social | Public publication feed and comment replies | Частично | Abuse reporting, human/admin review and queues |
| S03 | Social | Likes, rankings, discovery topics | Частично | Favorite backend exists; likes/rank UI and anti-spam missing |
| S04 | Social | Image gallery and creator showcase | Отсутствует | Moderated images and attribution |
| S05 | Social | Challenges/events and community notifications | Отсутствует | Admin-run safe themed events with controls |
| S06 | Social | Creator dashboard, analytics, earnings | Отсутствует | Only verified net income, transparent payout policy |
| E01 | Economy | Three token balances & ledger | Проверено на стенде | Ledger tests, reconciliation, audit logs |
| E02 | Economy | Free basic dialogue | Проверено на стенде | Sustainable quota, transparent costs without coercive limits |
| E03 | Economy | Premium paid chat and imagery | Частично | Cost displayed before requests, rollback on failed jobs |
| E04 | Economy | Five Play Billing token packages | Внешний доступ / частично | Android Billing UI + Google server verification + refunds |
| E05 | Economy | AdMob rewarded views for tokens | Внешний доступ / частично | Android opt-in + signed server validation + idempotency |
| E06 | Economy | Optional memberships/subscriptions | Отсутствует | Store entitlements, grace period, cancel and restore |
| E07 | Economy | Daily check-in bonus and promotions | Отсутствует | Anti-fraud, rate-limits, eligibility |
| E08 | Economy | Gifts and story unlock collections | Отсутствует | Clear fictional rewards and no gambling/randomized paid winnings |
| E09 | Economy | Transaction history and balance breakdown | В коде; устройство не проверено | Android shows wallet, complete detailed history |
| U01 | UX | Premium dark theme & original branding | Частично | Consistent components, responsive typography, low-end profiling |
| U02 | UX | Home, Discover, Search, Create, Chats, Profile | В коде; устройство не проверено | Real destination for each navigation item |
| U03 | UX | Stories, Collections, Notifications, Creator hub | Частично | Working workflows with empty/loading/error states |
| U04 | UX | Localizations (RU/EN first) | Отсутствует | Android resources and translated server content |
| U05 | UX | Accessibility/contrast/screen readers | Частично | TalkBack checks, no inaccessible gestures-only actions |
| U06 | UX | Web/browser experience | Отсутствует | Responsive web app with same backend and permissions |
| T01 | Trust | Moderation before public UGC | Частично | Report/review queues and human appeals, not just status enum |
| T02 | Trust | Age-appropriate content policies | Частично | Nonsexual fictional content, safety checks for text/art/voice |
| T03 | Trust | Privacy, account deletion, data export | Проверено на стенде | Verified deletion and GDPR/local compliance as applicable |
| T04 | Trust | Subscription/Play/AdMob compliance | Внешний доступ / частично | Current policy review + sandbox purchases + owner accounts |
| T05 | Trust | Deployment, backups, monitoring, incident readiness | Внешний доступ / частично | Hosted HTTPS server, migrations, audit and recovery exercises |
| T06 | Trust | Anti-spam, protections, billing fraud tests | Проверено на стенде | Rate limiting, idempotency, negative-path security integration |
| T07 | Trust | End-to-end device tests & signed releases | Внешний доступ / частично | Debug APK built; release AAB with owner signing keys not ready |

## Competitor findings (confirmed publicly)
- Official 'Start Here' guide dated 2026-01-15: discovery swipes, searches/tags, favorites, community, chats/notifications, creation, rankings, creator revenue.
- 'Advanced Chat' guide 2026-01-15: model quality tiers, writing styles, customization.
- 'Memory' guide 2026-01-15: automatic summarization about every 40 conversation turns plus manual pin/edit; memory is scoped to chat/scenario.
- 'Voice' guide 2026-01-15: audio playback and voice customization; official main site also advertises live voice.
- 'Create Character' guide 2026-01-15: image, intro, roleplay persona, scenario variants and paid unlocks.
- 'Image Generation' guide 2026-01-15: prompt optimization, LoRA, restoration, img2img and negative prompts.
- 'Gameplay' release 2025-09-20: fictional in-world search history, social posting, character communications and shopping lists.
- Developer membership announcement 2025-11-04: premium chat/voice/character slots/group chat and creative limits.
- Google Play listing updated 2026-08-05: inner thoughts, daily life, gifts, leveling, gallery and community.

## Execution milestones (each milestone requires code + tests + usable UI)
1. **Discover + original starter library + profiles:** search, filters, featured carousel, thumbnails, category/tag pages.
2. **Real AI:** provider gateway, model router, safe prompt construction, per-character text streaming, context & token charging, error refunds.
3. **Memory + story engine:** memory review/edit/delete; story branches, role cards, character/world canon, multi-character chats.
4. **Voice + Art:** STT/TTS calls and voice messages, template art/img2img/gallery, filters/consent.
5. **Social + fictional life:** fictional feed/phone, public profiles, follows, rankings, reports, review dashboard.
6. **Monetization and creator tools:** Play Billing tokens, rewarded video, SSV, paid tiers, gifts as fixed-price entitlements, earnings/analytics.
7. **Hardening and launch:** policies, platform account configuration, server infrastructure, signed release AAB, load/E2E/security testing.

## Non-negotiable design principles
- Never copy competitor code, artwork, brand names, characters or UI pixel-for-pixel.
- No nudity/sexual content, sexualized minors or adult-only material. No gambling-style rewards.
- All fictional character calls, thoughts, timelines and simulated devices must be clearly labeled as AI-generated fiction.
- No vulnerable pseudo-intimacy, misleading promises, coercive paywalls or fake 'AI is a real person' messaging.
- All real payment and advertisement credits must originate server-side with verified receipts/SSV; transparent price before charge.
- Never mark a milestone 'done' without repeatable tests and an actual user-visible workflow.

## Evidence and limitations
Based on MiraiMind's public 2026 guide, Google Play listing and developer news/press releases. This is a **functional parity target**, not evidence that NOVA currently has these features. Detailed UI flows may differ across app builds and locales. Re-verify competitor claims periodically, and prefer building improved original UX over mirroring incidental details.
