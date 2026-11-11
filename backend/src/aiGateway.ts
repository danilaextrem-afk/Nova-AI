/**
 * Server-side OpenAI-compatible provider adapter.
 * No API secrets ever enter Android code or the API response.
 * Provider/model selection is deployment-controlled, not user-controlled.
 */
export type AiProviderName = 'openrouter' | 'groq' | 'gemini';
export type AiMessage = { role: 'system' | 'user' | 'assistant'; content: string };

export class AiProviderError extends Error {
  constructor(
    readonly code: 'AI_NOT_CONFIGURED' | 'AI_RATE_LIMITED' | 'AI_UPSTREAM_ERROR' | 'AI_EMPTY_RESPONSE',
    readonly httpStatus: number,
  ) { super(code); }
}

const providers: Record<AiProviderName, { base: string; keyEnv: string; defaultModel: string }> = {
  openrouter: {
    base: 'https://openrouter.ai/api/v1',
    keyEnv: 'OPENROUTER_API_KEY',
    defaultModel: 'openrouter/free',
  },
  groq: {
    base: 'https://api.groq.com/openai/v1',
    keyEnv: 'GROQ_API_KEY',
    defaultModel: 'openai/gpt-oss-20b',
  },
  gemini: {
    base: 'https://generativelanguage.googleapis.com/v1beta/openai',
    keyEnv: 'GEMINI_API_KEY',
    defaultModel: 'gemini-2.5-flash',
  },
};

export type AiTier = 'free' | 'premium';

export function aiSettings(tier: AiTier = 'free') {
  const raw = (tier === 'premium' ? process.env.NOVA_PREMIUM_PROVIDER : process.env.AI_PROVIDER) || process.env.AI_PROVIDER || 'openrouter';
  const provider = (raw in providers ? raw : 'openrouter') as AiProviderName;
  const p = providers[provider];
  const key = process.env[p.keyEnv] || '';
  const model = ((tier === 'premium' ? process.env.NOVA_PREMIUM_MODEL : process.env.AI_MODEL) || process.env.AI_MODEL || p.defaultModel).trim();
  return {
    provider, model, configured: Boolean(key),
    base: p.base, key,
  };
}

export type AiCompletion = {
  text: string; provider: AiProviderName; model: string;
  inputTokens: number; outputTokens: number;
};

export async function completeAiChat(messages: AiMessage[], tier: AiTier = 'free'): Promise<AiCompletion> {
  const s = aiSettings(tier);
  if (!s.configured) throw new AiProviderError('AI_NOT_CONFIGURED', 503);
  if (messages.length < 2 || messages.length > 25 ||
    messages.some(m => m.content.length > 2500)) {
    throw new AiProviderError('AI_UPSTREAM_ERROR', 400);
  }
  let response: Response;
  try {
    response = await fetch(s.base + '/chat/completions', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + s.key,
        'Content-Type': 'application/json',
        ...(s.provider === 'gemini' ? { 'x-goog-api-client': 'nova-ai/0.2' } : {}),
        ...(s.provider === 'openrouter' ? { 'X-Title': 'NOVA AI' } : {}),
      },
      body: JSON.stringify({
        model: s.model,
        messages,
        stream: false,
        temperature: 0.8,
        max_tokens: tier === 'premium' ? 1600 : 360,
      }),
      signal: AbortSignal.timeout(35000),
    });
  } catch {
    throw new AiProviderError('AI_UPSTREAM_ERROR', 502);
  }
  if (response.status === 429) throw new AiProviderError('AI_RATE_LIMITED', 429);
  if (!response.ok) throw new AiProviderError('AI_UPSTREAM_ERROR', 502);
  let data: unknown;
  try { data = await response.json(); }
  catch { throw new AiProviderError('AI_UPSTREAM_ERROR', 502); }
  const obj = data as {
    choices?: { message?: { content?: unknown } }[];
    usage?: { prompt_tokens?: number; completion_tokens?: number };
  };
  const content = obj?.choices?.[0]?.message?.content;
  if (typeof content !== 'string' || content.trim().length === 0) {
    throw new AiProviderError('AI_EMPTY_RESPONSE', 502);
  }
  return {
    text: content.trim().slice(0, tier === 'premium' ? 8500 : 2600),
    provider: s.provider,
    model: s.model,
    inputTokens: Math.max(0, Math.trunc(obj.usage?.prompt_tokens || 0)),
    outputTokens: Math.max(0, Math.trunc(obj.usage?.completion_tokens || 0)),
  };
}
