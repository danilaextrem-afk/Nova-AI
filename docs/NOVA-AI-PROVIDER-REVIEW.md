# Provider review — 2026-11-11

No provider credentials were available for live checks. Russian-language quality, roleplay suitability and latency have not been benchmarked; a model name does not prove Premium quality.

| Provider | Official evidence | NOVA policy |
|---|---|---|
| Groq | [Rate limits](https://console.groq.com/docs/rate-limits), [models](https://console.groq.com/docs/models). Current documented free quotas for gpt-oss-20b/120b: 30 RPM, 1000 RPD, 8000 TPM, 200000 TPD per organization/model. | Defaults are configurable; per-model shared PostgreSQL buckets. Paid deployment must supply actual prices/budget; app quotas are not extra provider entitlement. |
| OpenRouter | [Free models limits](https://openrouter.ai/docs/api-reference/limits): free model limits depend on account credits, commonly 20 RPM / 50 RPD. | Only openrouter/free or :free suffix is allowed in Free; default conservative 50 RPD, data_collection deny. Router is one fallback provider, not independent capacity for each routed model. |
| Gemini | [Terms](https://ai.google.dev/gemini-api/terms), [pricing](https://ai.google.dev/gemini-api/docs/pricing). Availability and free/paid processing rules vary; EEA/UK/CH clients and personal data need particular care under terms. | Disabled unless NOVA_GEMINI_TERMS_REVIEWED=true and explicit model configuration. Never treat its unpaid service as a universally suitable private-chat backend. |
| Cerebras | [Limits](https://inference-docs.cerebras.ai/support/rate-limits): documented trial credit requires eligibility and expires; not perpetual free capacity. | Excluded from Free. Explicit paid model, pricing and budget required. |

NOVA_AI_MODELS_JSON requires approved=true, RPM/RPD/TPM/TPD, prices, budget and concurrency. Rate-limit responses and Retry-After trigger cooldown; bounded queues, circuit state, attempt latency and budget accounting are persisted. These are conservative admission limits, not automatic discovery of every account-specific quota or live health checks. Ambiguous upstream failures retain cost reservation. No fake replies or account rotation are used.

Premium uses a distinct configured model and server-set token price. Its quality advantage remains unverified until a real comparative benchmark is run. Quota/price documentation must be rechecked when deploying; this review is dated, not a promise of free inference indefinitely.
