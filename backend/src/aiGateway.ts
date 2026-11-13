import { setTimeout as delay } from 'node:timers/promises';
import type { PrismaClient } from '@prisma/client';
import { z } from 'zod';

export type AiProviderName = 'openrouter' | 'groq' | 'gemini' | 'cerebras' | 'cloudflare';
export type AiTier = 'free' | 'premium';
export type AiMessage = { role: 'system' | 'user' | 'assistant'; content: string };
export class AiProviderError extends Error {
  constructor(readonly code: string, readonly httpStatus: number, readonly retryAfter = 30) { super(code); }
}
const providers = {
  groq: { base: 'https://api.groq.com/openai/v1', keyEnv: 'GROQ_API_KEY', model: 'openai/gpt-oss-20b' },
  openrouter: { base: 'https://openrouter.ai/api/v1', keyEnv: 'OPENROUTER_API_KEY', model: 'openrouter/free' },
  // Native Gemini API: AI Studio now issues 'AQ.' auth keys, which the OpenAI-compatible endpoint rejects.
  gemini: { base: 'https://generativelanguage.googleapis.com/v1beta', keyEnv: 'GEMINI_API_KEY', model: 'gemini-flash-latest' },
  cerebras: { base: 'https://api.cerebras.ai/v1', keyEnv: 'CEREBRAS_API_KEY', model: 'gpt-oss-120b' },
  // Workers AI, OpenAI-compatible; the account id is part of the URL. Free plan: 10,000 Neurons/day, then errors (no charge).
  cloudflare: { base: 'https://api.cloudflare.com/client/v4/accounts', keyEnv: 'CLOUDFLARE_API_TOKEN', model: '@cf/openai/gpt-oss-20b' },
} as const;
const modelSchema = z.object({
  provider: z.enum(['groq','openrouter','gemini','cerebras','cloudflare']), model: z.string().min(1).max(150),
  tier: z.enum(['free','premium']), approved: z.literal(true),
  rpm: z.number().int().positive(), rpd: z.number().int().positive(),
  tpm: z.number().int().positive(), tpd: z.number().int().positive(),
  inputUsdPerMillion: z.number().nonnegative(), outputUsdPerMillion: z.number().nonnegative(),
  dailyBudgetMicros: z.number().int().nonnegative(), concurrency: z.number().int().min(1).max(20).default(2),
});
export type ModelConfig = z.infer<typeof modelSchema>;
const positive = (value: string | undefined, fallback: number) => Math.max(1, Math.floor(Number(value) || fallback));
export function modelPool(tier: AiTier): ModelConfig[] {
  let models: ModelConfig[];
  if (process.env.NOVA_AI_MODELS_JSON) {
    try { models = z.array(modelSchema).max(20).parse(JSON.parse(process.env.NOVA_AI_MODELS_JSON)); }
    catch { return []; } // Invalid deployment configuration fails closed.
  } else {
    // 120b first: on live Russian checks (2026-11-12) 20b made spelling and agreement errors that 120b did not.
    // Official free-plan limits (console.groq.com/docs/rate-limits, checked 2026-11-11): Groq counts quotas
    // per model and organization, so each listed Groq model is an independent free bucket. OpenRouter's
    // openrouter/free router is the only OpenRouter free entry by default.
    const preferred = (process.env.AI_PROVIDER || 'groq') as AiProviderName;
    const groqModels = Array.from(new Set([
      ...(preferred === 'groq' && process.env.AI_MODEL?.trim() ? [process.env.AI_MODEL.trim()] : []),
      ...(process.env.NOVA_GROQ_FREE_MODELS?.trim() || 'openai/gpt-oss-120b,openai/gpt-oss-20b').split(',').map(x => x.trim()).filter(Boolean),
    ])).filter(model => !((process.env.NOVA_PREMIUM_PROVIDER || 'groq') === 'groq' && model === process.env.NOVA_PREMIUM_MODEL?.trim()))
      .slice(0, 6); // A model sold as Premium is never also handed out as Free.
    const free = (provider: AiProviderName, model: string): ModelConfig => ({
      provider, model, tier: 'free', approved: true,
      rpm: provider === 'groq' ? 30 : 20, rpd: provider === 'groq' ? 1000 : 50,
      tpm: provider === 'groq' ? 8000 : 20000, tpd: provider === 'groq' ? 200000 : 100000,
      inputUsdPerMillion: 0, outputUsdPerMillion: 0, dailyBudgetMicros: 0, concurrency: 2,
    });
    const groq = groqModels.map(model => free('groq', model));
    // OpenRouter free models share one per-account quota; the set changes often, so it is configurable.
    // A removed model answers 404 and the gateway moves on to the next entry.
    const openrouter = Array.from(new Set([
      ...(preferred === 'openrouter' && process.env.AI_MODEL?.trim() ? [process.env.AI_MODEL.trim()] : []),
      // deepseek-v4-flash:free was withdrawn (404 'unavailable for free', 2026-11-12).
      ...(process.env.NOVA_OPENROUTER_FREE_MODELS?.trim() || 'openrouter/free').split(',').map(x => x.trim()).filter(Boolean),
    ])).slice(0, 4).map(model => free('openrouter', model));
    // Gemini (free tier) is a last resort and only with the owner's documented consent (NOVA_GEMINI_TERMS_REVIEWED):
    // Google may use free-tier prompts to improve its products. Conservative limits; the provider's 429 still rules.
    const gemini = [{...free('gemini', process.env.NOVA_GEMINI_MODEL?.trim() || providers.gemini.model), rpm: 8, rpd: 200, tpm: 200000, tpd: 2000000}];
    // Neuron-based quota (~10k/day) mapped to conservative token/request budgets; the provider's errors still rule.
    const cloudflare = [{...free('cloudflare', process.env.NOVA_CLOUDFLARE_MODEL?.trim() || providers.cloudflare.model), rpm: 60, rpd: 300, tpm: 50000, tpd: 300000}];
    const first = preferred === 'openrouter' ? openrouter : preferred === 'gemini' ? gemini : preferred === 'cloudflare' ? cloudflare : groq;
    models = Array.from(new Set([first, groq, openrouter, cloudflare, gemini])).flat();
    if (process.env.NOVA_PREMIUM_MODEL?.trim()) {
      const provider = (process.env.NOVA_PREMIUM_PROVIDER || 'groq') as AiProviderName;
      if (provider in providers) models.push({
        provider, model: process.env.NOVA_PREMIUM_MODEL.trim(), tier:'premium', approved:true,
        rpm:30, rpd:1000, tpm:8000, tpd:200000, concurrency:2,
        inputUsdPerMillion: Number(process.env.NOVA_PREMIUM_INPUT_USD_PER_MILLION || '0.15'),
        outputUsdPerMillion: Number(process.env.NOVA_PREMIUM_OUTPUT_USD_PER_MILLION || '0.60'),
        dailyBudgetMicros: Math.max(0, Number(process.env.NOVA_AI_DAILY_BUDGET_MICROS || 0)),
      });
    }
  }
  return models.filter(m => m.tier === tier && !!process.env[providers[m.provider].keyEnv]?.trim())
    .filter(m => tier !== 'free' || (m.inputUsdPerMillion === 0 && m.outputUsdPerMillion === 0))
    .filter(m => m.provider !== 'openrouter' || tier !== 'free' || m.model === 'openrouter/free' || m.model.endsWith(':free'))
    .filter(m => m.provider !== 'gemini' || process.env.NOVA_GEMINI_TERMS_REVIEWED === 'true')
    .filter(m => m.provider !== 'cerebras' || tier !== 'free')
    .filter(m => m.provider !== 'cloudflare' || /^[0-9a-f]{32}$/.test(process.env.CLOUDFLARE_ACCOUNT_ID || ''))
    .filter(m => m.inputUsdPerMillion + m.outputUsdPerMillion === 0 || m.dailyBudgetMicros > 0);
}
export function quotaKey(m: Pick<ModelConfig,'provider'|'model'>): string {
  return m.provider === 'groq' ? 'groq:' + m.model : m.provider;
}
/** Circuit breaker per model: one broken OpenRouter model must not block the others behind the shared quota. */
export function breakerKey(m: Pick<ModelConfig,'provider'|'model'>): string {
  return m.provider === 'groq' ? quotaKey(m) : 'breaker:' + m.provider + ':' + m.model;
}
/**
 * Conservative token estimate. Measured on live traffic (2026-11-12): Russian prompts average ~2.7
 * characters per token on gpt-oss, while the old UTF-8 byte count reserved ~5x the real usage and
 * made Groq's 8k tokens/minute look full after one request.
 */
export function estimateTokens(messages: AiMessage[]): number {
  return messages.reduce((n, m) => n + Math.ceil(m.content.length / 2) + 8, 0);
}
/** gpt-oss spends part of max_tokens on hidden reasoning; Cloudflare has no switch to shorten it. */
export function outputAllowance(m: Pick<ModelConfig,'provider'|'model'>, maxOutput: number): number {
  return m.provider === 'cloudflare' && m.model.includes('gpt-oss') ? Math.max(maxOutput, 1024) : maxOutput;
}
/** Failures worth one more try within the same request: the provider may answer next time. */
const TRANSIENT = new Set(['AI_UPSTREAM_ERROR', 'AI_EMPTY_RESPONSE', 'AI_TIMEOUT']);
export function aiSettings(tier: AiTier = 'free') {
  const candidate = modelPool(tier)[0];
  const raw = (tier === 'premium' ? process.env.NOVA_PREMIUM_PROVIDER : process.env.AI_PROVIDER) || 'groq';
  const provider = (raw in providers ? raw : 'groq') as AiProviderName;
  return {provider: candidate?.provider || provider, model: candidate?.model || providers[provider].model, configured: !!candidate};
}
export const premiumReady = () => modelPool('premium').some(p =>
  !modelPool('free').some(f => p.model === f.model && p.provider === f.provider));
/** Request for one provider. Gemini uses its native generateContent API; the rest are OpenAI-compatible. */
export function providerRequest(m:Pick<ModelConfig,'provider'|'model'>,messages:AiMessage[],streaming:boolean,maxOutput:number,temperature=0.8):
  {url:string;headers:Record<string,string>;body:Record<string,unknown>}{
  const p=providers[m.provider];const key=process.env[p.keyEnv]||'';
  if(m.provider==='gemini'){
    const system=messages.filter(x=>x.role==='system').map(x=>x.content).join('\n\n');
    const contents:{role:'user'|'model';parts:{text:string}[]}[]=[];
    for(const x of messages.filter(x=>x.role!=='system')){
      const role=x.role==='assistant'?'model':'user';const last=contents.at(-1);
      if(last?.role===role)last.parts.push({text:x.content});else contents.push({role,parts:[{text:x.content}]});
    }
    return {url:`${p.base}/models/${encodeURIComponent(m.model)}:${streaming?'streamGenerateContent?alt=sse':'generateContent'}`,
      headers:{'x-goog-api-key':key,'Content-Type':'application/json'},
      // Extra output room: thinking models spend part of the budget before the visible answer.
      body:{...(system?{systemInstruction:{parts:[{text:system}]}}:{}),contents,generationConfig:{temperature,maxOutputTokens:maxOutput+1024}}};
  }
  const groqGptOss = m.provider === 'groq' && ['openai/gpt-oss-20b', 'openai/gpt-oss-120b'].includes(m.model);
  const base=m.provider==='cloudflare'?`${p.base}/${process.env.CLOUDFLARE_ACCOUNT_ID}/ai/v1`:p.base;
  return {url:base+'/chat/completions',
    headers:{Authorization:'Bearer '+key,'Content-Type':'application/json',...(m.provider==='openrouter'?{'X-Title':'NOVA AI'}:{} as Record<string,string>)},
    body:{model:m.model,messages,stream:streaming,temperature,max_tokens:outputAllowance(m,maxOutput),
      ...(groqGptOss ? { reasoning_effort: 'low', include_reasoning: false } : {}),
      ...(streaming?{stream_options:{include_usage:true}}:{}),...(m.provider==='openrouter'?{provider:{data_collection:'deny'}}:{})}};
}
/** Maps a Gemini response or stream chunk onto the OpenAI-compatible shape the gateway already parses. */
export function geminiToOpenAI(chunk:any){
  if(chunk?.error)return {error:chunk.error};
  const c=chunk?.candidates?.[0];
  const text=(c?.content?.parts??[]).filter((x:any)=>!x?.thought&&typeof x?.text==='string').map((x:any)=>x.text).join('');
  const r=c?.finishReason;
  const finish=r==='STOP'?'stop':r==='MAX_TOKENS'?'length':r||chunk?.promptFeedback?.blockReason?'content_filter':null;
  const u=chunk?.usageMetadata;
  return {choices:[{delta:{content:text},message:{content:text},finish_reason:finish}],
    ...(u?{usage:{prompt_tokens:u.promptTokenCount,completion_tokens:u.candidatesTokenCount}}:{})};
}
export type AiCompletion = { text:string; provider:AiProviderName; model:string; inputTokens:number; outputTokens:number; costMicros:number };
export function retryAfterMs(value: string | null | undefined, now = Date.now()): number {
  if (!value) return 0;
  const seconds = Number(value);
  const ms = Number.isFinite(seconds) ? seconds * 1000 : Date.parse(value) - now;
  return Number.isFinite(ms) ? Math.max(0, Math.min(86400000, ms)) : 0;
}

// Two bounded queues; Premium workers cannot consume the Free queue's capacity.
const queues: Record<AiTier, { active: number; waiting: (() => void)[] }> = {
  free: {active:0, waiting:[]}, premium: {active:0, waiting:[]},
};
const cancelledRequest = () => new AiProviderError('AI_CANCELLED', 499, 1);

function checkCancellation(signal?: AbortSignal) {
  if (signal?.aborted) throw cancelledRequest();
}

async function enter(tier: AiTier, signal?: AbortSignal) {
  checkCancellation(signal);
  const queue = queues[tier];
  if (queue.active >= 4) {
    if (queue.waiting.length >= 24) throw new AiProviderError('AI_QUEUE_FULL', 503);
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => {
        clearTimeout(timer);
        signal?.removeEventListener('abort', abort);
      };
      const start = () => { cleanup(); resolve(); };
      const remove = (error: AiProviderError) => {
        cleanup();
        queue.waiting = queue.waiting.filter(waiter => waiter !== start);
        reject(error);
      };
      const abort = () => remove(cancelledRequest());
      const timer = setTimeout(() => remove(new AiProviderError('AI_QUEUE_FULL', 503)), 15000);
      queue.waiting.push(start);
      signal?.addEventListener('abort', abort, { once: true });
    });
  } else {
    queue.active++;
  }
  return () => {
    const next = queue.waiting.shift();
    if (next) next();
    else queue.active--;
  };
}
const micros = (m:ModelConfig, input:number, output:number) => Math.ceil(input*m.inputUsdPerMillion + output*m.outputUsdPerMillion);

// Shared database reservations enforce provider quotas/budgets across server replicas.
// Token estimation uses UTF-8 bytes as a conservative upper bound; includes output reservation.
export type AiStreamOptions = { onDelta?: (text:string)=>void; signal?: AbortSignal; maxOutput?: number; temperature?: number };
export async function completeAiChat(messages:AiMessage[], tier:AiTier='free', db?:PrismaClient, requestId?:string, opts:AiStreamOptions={}):Promise<AiCompletion> {
  const models = modelPool(tier).filter(m => tier !== 'premium' || !modelPool('free').some(f=>m.model===f.model&&m.provider===f.provider));
  if (!models.length) throw new AiProviderError('AI_NOT_CONFIGURED',503);
  if (!db) throw new AiProviderError('AI_COORDINATOR_REQUIRED',503);
  if (messages.length < 2 || messages.length > 80 || messages.some(m=>m.content.length>20000)) throw new AiProviderError('INVALID_AI_CONTEXT',400);
  const leave = await enter(tier, opts.signal);
  try {
    const maxOutput = Math.min(2400, opts.maxOutput ?? (tier === 'premium' ? 1600 : 360));
    const inputEstimate = estimateTokens(messages);
    let lastError = new AiProviderError('AI_RATE_LIMITED',429);
    const deadline = Date.now()+70000;
    // Pass 1 tries every model once. Pass 2 retries, once, only the models that just failed transiently
    // (5xx, timeout, empty answer) — bounded by the same deadline, never after text was shown.
    const retryable = new Set<ModelConfig>();
    // Models skipped only because this minute's request/token window is full. If the next minute starts soon,
    // waiting for it (within the quota) beats a slow last-resort provider or an error.
    const waitable = new Set<ModelConfig>();
    const queue: { m: ModelConfig; second: boolean; wait?: boolean }[] = models.map(m => ({ m, second: false }));
    for (let qi = 0; ; qi++) {
      checkCancellation(opts.signal);
      if (qi === models.length) {
        for (const r of models.filter(x => retryable.has(x))) queue.push({ m: r, second: true });
        for (const w of models.filter(x => waitable.has(x))) queue.push({ m: w, second: false, wait: true });
      }
      if (qi >= queue.length || Date.now() > deadline) break;
      const { m, second, wait } = queue[qi];
      if (wait) {
        const untilNextMinute = 60000 - (Date.now() % 60000) + 300;
        if (untilNextMinute > 25000 || Date.now() + untilNextMinute > deadline - 15000) continue;
        if (!queue.slice(0, qi).some(x => x.wait)) {
          try {
            await delay(untilNextMinute, undefined, { signal: opts.signal });
          } catch (error) {
            checkCancellation(opts.signal);
            throw error;
          }
        }
      }
      checkCancellation(opts.signal);
      const now = new Date();
      const minute = new Date(Math.floor(now.getTime()/60000)*60000);
      const day = new Date(now.toISOString().slice(0,10));
      const estimate = inputEstimate + maxOutput;
      const maxCost = micros(m,inputEstimate,maxOutput);
      const key = quotaKey(m); // Groq limits are per model; other providers share one conservative bucket.
      const bkey = breakerKey(m);
      const attempt = await db.$transaction(async tx => {
        // Prisma can emulate an upsert with an empty update as a read and insert.
        // Insert idempotently before locking so simultaneous first requests cannot collide.
        await tx.aiProviderState.createMany({data:{key,minuteAt:minute,dayAt:day},skipDuplicates:true});
        await tx.$queryRaw`SELECT key FROM "AiProviderState" WHERE key = ${key} FOR UPDATE`;
        if (bkey !== key) {
          await tx.aiProviderState.createMany({data:{key:bkey,minuteAt:minute,dayAt:day},skipDuplicates:true});
          const breaker = await tx.aiProviderState.findUniqueOrThrow({where:{key:bkey}});
          if (!second && breaker.retryAt && breaker.retryAt > now) return null;
        }
        let state = (await tx.aiProviderState.findUnique({where:{key}}))!;
        const reset = {...(state.minuteAt<minute ? {minuteAt:minute,minuteRequests:0,minuteTokens:0}:{}),
          ...(state.dayAt<day ? {dayAt:day,dayRequests:0,dayTokens:0,costMicros:0}:{})};
        if(Object.keys(reset).length) state = await tx.aiProviderState.update({where:{key},data:reset});
        // A breaker on the shared row (rate limit, or Groq's per-model row) is skipped only for the one bounded retry.
        if(state.retryAt && state.retryAt>now && !(second && bkey===key)) return null;
        const active = await tx.aiAttempt.count({where:{provider:m.provider,status:'PENDING',expiresAt:{gt:now}}});
        if (active >= m.concurrency || state.dayRequests>=m.rpd || state.dayTokens+estimate>m.tpd ||
          (maxCost>0 && state.costMicros+maxCost>m.dailyBudgetMicros)) return null;
        if (state.minuteRequests>=m.rpm || state.minuteTokens+estimate>m.tpm) return 'minute' as const;
        checkCancellation(opts.signal);
        await tx.aiProviderState.update({where:{key},data:{minuteRequests:{increment:1},dayRequests:{increment:1},
          minuteTokens:{increment:estimate},dayTokens:{increment:estimate},costMicros:{increment:maxCost}}});
        const reserved = await tx.aiAttempt.create({data:{requestId,provider:m.provider,model:m.model,tier,status:'PENDING',
          estimatedTokens:estimate,costMicros:maxCost,expiresAt:new Date(Date.now()+90000)}});
        checkCancellation(opts.signal);
        return reserved;
      });
      if (attempt === 'minute') { if (!wait && estimate <= m.tpm) waitable.add(m); continue; }
      if (!attempt) continue;
      const started=Date.now();
      let retry=0; let code='AI_UPSTREAM_ERROR'; let status=502; let certainNoCharge=false; let emitted=false;
      let httpStatus=0; let detail='';
      const streaming=!!opts.onDelta;
      try {
        checkCancellation(opts.signal);
        const req=providerRequest(m,messages,streaming,maxOutput,opts.temperature);
        const response=await fetch(req.url,{
          method:'POST',headers:req.headers,body:JSON.stringify(req.body),
          signal:AbortSignal.any([AbortSignal.timeout(Math.min(streaming?60000:m.provider==='groq'?30000:20000,Math.max(1000,deadline-Date.now()))),
            ...(opts.signal?[opts.signal]:[])]),
        });
        if(!response.ok){
          httpStatus=response.status;
          retry=retryAfterMs(response.headers?.get('retry-after'));
          status=response.status===429?429:502; code=response.status===429?'AI_RATE_LIMITED':'AI_UPSTREAM_ERROR';
          // Keep the provider's own error message (never our prompt) for diagnosis; reading it must never fail the request.
          detail=await Promise.resolve().then(()=>response.text()).then(t=>{try{const j=JSON.parse(t);return String(j?.error?.message??j?.error??j?.errors?.[0]?.message??t);}catch{return String(t);}}).catch(()=>'');
          certainNoCharge=[400,401,403,404,429].includes(response.status);
          if([401,403,404].includes(response.status)) retry=Math.max(retry,300000);
          throw new Error(code);
        }
        type Usage={prompt_tokens?:number;completion_tokens?:number};
        let text:unknown; let usage:Usage|undefined;
        if(streaming){
          // OpenAI-compatible SSE: "data: {choices:[{delta:{content}}]}" lines, optional usage chunk, "data: [DONE]".
          if(!response.body){code='AI_EMPTY_RESPONSE';throw new Error(code);}
          const reader=response.body.getReader();const decoder=new TextDecoder();let buffer='';let out='';let completed=false;
          const limit=tier==='premium'?12000:4000;
          for(;;){
            // Do not rely on the fetch implementation to abort the body: stop reading as soon as the user cancels.
            if(opts.signal?.aborted){reader.cancel().catch(()=>{});throw new Error('AI_CANCELLED');}
            const {done,value}=await reader.read();
            buffer+=decoder.decode(value,{stream:!done});
            if(buffer.length>1048576)throw new Error('AI_INVALID_STREAM');
            if(done&&buffer&&!buffer.endsWith('\n'))buffer+='\n';
            let nl:number;
            while((nl=buffer.indexOf('\n'))>=0){
              const line=buffer.slice(0,nl).trim();buffer=buffer.slice(nl+1);
              if(!line.startsWith('data:'))continue;
              const data=line.slice(5).trim();if(data==='[DONE]'){completed=true;continue;}if(!data)continue;
              let chunk:any;try{chunk=JSON.parse(data);}catch{continue;}
              if(m.provider==='gemini')chunk=geminiToOpenAI(chunk);
              if(chunk?.error){code='AI_UPSTREAM_ERROR';throw new Error(code);}
              if(chunk?.usage)usage=chunk.usage;
              const finish=chunk?.choices?.[0]?.finish_reason;
              if(finish==='stop'||finish==='length')completed=true;
              if(finish==='content_filter'){code='AI_CONTENT_FILTERED';throw new Error(code);}
              const delta=chunk?.choices?.[0]?.delta?.content;
              if(typeof delta==='string'&&delta&&out.length<limit){
                const piece=delta.slice(0,limit-out.length);out+=piece;emitted=true;opts.onDelta!(piece);
              }
            }
            if(done)break;
          }
          if(!completed){code='AI_STREAM_INTERRUPTED';throw new Error(code);}
          text=out;
        }else{
          let obj=await response.json() as any;
          if(m.provider==='gemini'){obj=geminiToOpenAI(obj);if(obj.choices[0].finish_reason==='content_filter'){code='AI_CONTENT_FILTERED';throw new Error(code);}}
          text=obj?.choices?.[0]?.message?.content;usage=obj?.usage;
        }
        if(opts.signal?.aborted)throw new Error('AI_CANCELLED');
        if(typeof text!=='string'||!text.trim()){code='AI_EMPTY_RESPONSE';throw new Error(code);}
        const num=(x:unknown,fallback:number)=>typeof x==='number'&&Number.isSafeInteger(x)&&x>=0?x:fallback;
        const inputTokens=num(usage?.prompt_tokens,inputEstimate), outputTokens=num(usage?.completion_tokens,maxOutput);
        const cost=micros(m,inputTokens,outputTokens);
        // Reservations are kept on ambiguous failures/timeouts. Successful usage can release overestimates.
        await db.$transaction(async tx=>{
          await tx.$queryRaw`SELECT key FROM "AiProviderState" WHERE key = ${key} FOR UPDATE`;
          const state=(await tx.aiProviderState.findUnique({where:{key}}))!;
          await tx.aiProviderState.update({where:{key},data:{failures:0,
            ...(state.dayAt.getTime()===day.getTime()?{costMicros:{increment:cost-maxCost},dayTokens:{increment:inputTokens+outputTokens-estimate}}:{}),
            ...(state.minuteAt.getTime()===minute.getTime()?{minuteTokens:{increment:inputTokens+outputTokens-estimate}}:{})}});
          await tx.aiAttempt.update({where:{id:attempt.id},data:{status:'SUCCEEDED',costMicros:cost,latencyMs:Date.now()-started}});
          if(bkey!==key)await tx.aiProviderState.update({where:{key:bkey},data:{failures:0,retryAt:null}});
        });
        return {text:text.trim().slice(0,tier==='premium'?12000:4000),provider:m.provider,model:m.model,inputTokens,outputTokens,costMicros:cost};
      }catch(error){
        const cancelled=!!opts.signal?.aborted;if(cancelled)code='AI_CANCELLED';
        if(!cancelled&&!httpStatus&&(error as Error)?.name==='TimeoutError'){code='AI_TIMEOUT';status=504;}
        if(!cancelled)console.warn(JSON.stringify({level:40,msg:'AI provider attempt failed',provider:m.provider,model:m.model,code,
          httpStatus:httpStatus||undefined,retry:second||undefined,detail:detail.replace(/\s+/g,' ').replace(/(key|token|bearer)[=: ]+\S+/gi,'$1=…').slice(0,240)||undefined}));
        await db.$transaction(async tx=>{
          await tx.$queryRaw`SELECT key FROM "AiProviderState" WHERE key = ${key} FOR UPDATE`;
          const state=(await tx.aiProviderState.findUnique({where:{key}}))!;
          if(cancelled){ // A user pressing Stop is not a provider fault: no breaker trip, reservation kept.
            await tx.aiAttempt.update({where:{id:attempt.id},data:{status:'FAILED',errorCode:code,latencyMs:Date.now()-started}});
            return;
          }
          // Rate limits are account-wide (shared row); other failures belong to this model only.
          const target=code==='AI_RATE_LIMITED'?key:bkey;
          const b=target===key?state:(await tx.aiProviderState.findUnique({where:{key:target}}))!;
          const failures=b.failures+1;
          const backoff=Math.min(60000,1000*2**Math.min(failures,6))+Math.floor(Math.random()*500);
          const until=new Date(Date.now()+Math.max(retry,backoff,failures>=3?30000:0));
          await tx.aiProviderState.update({where:{key:target},data:{failures,retryAt:until}});
          if(certainNoCharge&&state.dayAt.getTime()===day.getTime())await tx.aiProviderState.update({where:{key},data:{costMicros:{decrement:maxCost}}});
          await tx.aiAttempt.update({where:{id:attempt.id},data:{status:'FAILED',errorCode:httpStatus?code+':'+httpStatus:code,latencyMs:Date.now()-started}});
        });
        lastError=new AiProviderError(code==='AI_TIMEOUT'?'AI_UPSTREAM_ERROR':code,code==='AI_TIMEOUT'?502:status,Math.max(1,Math.ceil(retry/1000)||30));
        if(!second&&TRANSIENT.has(code))retryable.add(m);
        // A cancelled request, or a stream that already showed text, is never silently continued elsewhere.
        checkCancellation(opts.signal);
        if(emitted)throw new AiProviderError('AI_STREAM_INTERRUPTED',502,5);
      }
    }
    throw lastError;
  }finally{leave();}
}
