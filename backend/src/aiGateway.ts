import type { PrismaClient } from '@prisma/client';
import { z } from 'zod';

export type AiProviderName = 'openrouter' | 'groq' | 'gemini' | 'cerebras';
export type AiTier = 'free' | 'premium';
export type AiMessage = { role: 'system' | 'user' | 'assistant'; content: string };
export class AiProviderError extends Error {
  constructor(readonly code: string, readonly httpStatus: number, readonly retryAfter = 30) { super(code); }
}
const providers = {
  groq: { base: 'https://api.groq.com/openai/v1', keyEnv: 'GROQ_API_KEY', model: 'openai/gpt-oss-20b' },
  openrouter: { base: 'https://openrouter.ai/api/v1', keyEnv: 'OPENROUTER_API_KEY', model: 'openrouter/free' },
  gemini: { base: 'https://generativelanguage.googleapis.com/v1beta/openai', keyEnv: 'GEMINI_API_KEY', model: 'gemini-2.5-flash' },
  cerebras: { base: 'https://api.cerebras.ai/v1', keyEnv: 'CEREBRAS_API_KEY', model: 'gpt-oss-120b' },
} as const;
const modelSchema = z.object({
  provider: z.enum(['groq','openrouter','gemini','cerebras']), model: z.string().min(1).max(150),
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
    // Official free-plan limits (console.groq.com/docs/rate-limits, checked 2026-11-11): Groq counts quotas
    // per model and organization, so each listed Groq model is an independent free bucket. OpenRouter's
    // openrouter/free router is the only OpenRouter free entry by default.
    const preferred = (process.env.AI_PROVIDER || 'groq') as AiProviderName;
    const groqModels = Array.from(new Set([
      ...(preferred === 'groq' && process.env.AI_MODEL?.trim() ? [process.env.AI_MODEL.trim()] : []),
      ...(process.env.NOVA_GROQ_FREE_MODELS?.trim() || 'openai/gpt-oss-20b,openai/gpt-oss-120b').split(',').map(x => x.trim()).filter(Boolean),
    ])).filter(model => !((process.env.NOVA_PREMIUM_PROVIDER || 'groq') === 'groq' && model === process.env.NOVA_PREMIUM_MODEL?.trim()))
      .slice(0, 6); // A model sold as Premium is never also handed out as Free.
    const free = (provider: AiProviderName, model: string): ModelConfig => ({
      provider, model, tier: 'free', approved: true,
      rpm: provider === 'groq' ? 30 : 20, rpd: provider === 'groq' ? 1000 : 50,
      tpm: provider === 'groq' ? 8000 : 20000, tpd: provider === 'groq' ? 200000 : 100000,
      inputUsdPerMillion: 0, outputUsdPerMillion: 0, dailyBudgetMicros: 0, concurrency: 2,
    });
    const groq = groqModels.map(model => free('groq', model));
    const openrouter = [free('openrouter', preferred === 'openrouter' && process.env.AI_MODEL?.trim() ? process.env.AI_MODEL.trim() : providers.openrouter.model)];
    models = preferred === 'openrouter' ? [...openrouter, ...groq] : [...groq, ...openrouter];
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
    .filter(m => m.inputUsdPerMillion + m.outputUsdPerMillion === 0 || m.dailyBudgetMicros > 0);
}
export function quotaKey(m: Pick<ModelConfig,'provider'|'model'>): string {
  return m.provider === 'groq' ? 'groq:' + m.model : m.provider;
}
export function aiSettings(tier: AiTier = 'free') {
  const candidate = modelPool(tier)[0];
  const raw = (tier === 'premium' ? process.env.NOVA_PREMIUM_PROVIDER : process.env.AI_PROVIDER) || 'groq';
  const provider = (raw in providers ? raw : 'groq') as AiProviderName;
  return {provider: candidate?.provider || provider, model: candidate?.model || providers[provider].model, configured: !!candidate};
}
export const premiumReady = () => modelPool('premium').some(p =>
  !modelPool('free').some(f => p.model === f.model && p.provider === f.provider));
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
async function enter(tier:AiTier) {
  const queue = queues[tier];
  if (queue.active >= 4) {
    if (queue.waiting.length >= 24) throw new AiProviderError('AI_QUEUE_FULL',503);
    await new Promise<void>((resolve,reject) => {
      const start = () => { clearTimeout(timer); resolve(); };
      const timer = setTimeout(() => {queue.waiting = queue.waiting.filter(x=>x!==start);reject(new AiProviderError('AI_QUEUE_FULL',503));},15000);
      queue.waiting.push(start);
    });
  } else queue.active++;
  return () => { const next=queue.waiting.shift(); if(next) next(); else queue.active--; };
}
const micros = (m:ModelConfig, input:number, output:number) => Math.ceil(input*m.inputUsdPerMillion + output*m.outputUsdPerMillion);

// Shared database reservations enforce provider quotas/budgets across server replicas.
// Token estimation uses UTF-8 bytes as a conservative upper bound; includes output reservation.
export type AiStreamOptions = { onDelta?: (text:string)=>void; signal?: AbortSignal };
export async function completeAiChat(messages:AiMessage[], tier:AiTier='free', db?:PrismaClient, requestId?:string, opts:AiStreamOptions={}):Promise<AiCompletion> {
  const models = modelPool(tier).filter(m => tier !== 'premium' || !modelPool('free').some(f=>m.model===f.model&&m.provider===f.provider));
  if (!models.length) throw new AiProviderError('AI_NOT_CONFIGURED',503);
  if (!db) throw new AiProviderError('AI_COORDINATOR_REQUIRED',503);
  if (messages.length < 2 || messages.length > 80 || messages.some(m=>m.content.length>20000)) throw new AiProviderError('INVALID_AI_CONTEXT',400);
  const leave = await enter(tier);
  try {
    const maxOutput = tier === 'premium' ? 1600 : 360;
    const inputEstimate = messages.reduce((n,m)=>n+Buffer.byteLength(m.content,'utf8')+8,0);
    let lastError = new AiProviderError('AI_RATE_LIMITED',429);
    const deadline = Date.now()+70000;
    for (const m of models) {
      if (Date.now()>deadline) break;
      const now = new Date();
      const minute = new Date(Math.floor(now.getTime()/60000)*60000);
      const day = new Date(now.toISOString().slice(0,10));
      const estimate = inputEstimate + maxOutput;
      const maxCost = micros(m,inputEstimate,maxOutput);
      const key = quotaKey(m); // Groq limits are per model; other providers share one conservative bucket.
      const attempt = await db.$transaction(async tx => {
        await tx.aiProviderState.upsert({where:{key},create:{key,minuteAt:minute,dayAt:day},update:{}});
        await tx.$queryRaw`SELECT key FROM "AiProviderState" WHERE key = ${key} FOR UPDATE`;
        let state = (await tx.aiProviderState.findUnique({where:{key}}))!;
        const reset = {...(state.minuteAt<minute ? {minuteAt:minute,minuteRequests:0,minuteTokens:0}:{}),
          ...(state.dayAt<day ? {dayAt:day,dayRequests:0,dayTokens:0,costMicros:0}:{})};
        if(Object.keys(reset).length) state = await tx.aiProviderState.update({where:{key},data:reset});
        if(state.retryAt && state.retryAt>now) return null;
        const active = await tx.aiAttempt.count({where:{provider:m.provider,status:'PENDING',expiresAt:{gt:now}}});
        if (active >= m.concurrency || state.minuteRequests>=m.rpm || state.dayRequests>=m.rpd ||
          state.minuteTokens+estimate>m.tpm || state.dayTokens+estimate>m.tpd ||
          (maxCost>0 && state.costMicros+maxCost>m.dailyBudgetMicros)) return null;
        await tx.aiProviderState.update({where:{key},data:{minuteRequests:{increment:1},dayRequests:{increment:1},
          minuteTokens:{increment:estimate},dayTokens:{increment:estimate},costMicros:{increment:maxCost}}});
        return tx.aiAttempt.create({data:{requestId,provider:m.provider,model:m.model,tier,status:'PENDING',
          estimatedTokens:estimate,costMicros:maxCost,expiresAt:new Date(Date.now()+90000)}});
      });
      if (!attempt) continue;
      const started=Date.now();
      let retry=0; let code='AI_UPSTREAM_ERROR'; let status=502; let certainNoCharge=false; let emitted=false;
      const streaming=!!opts.onDelta;
      try {
        const p=providers[m.provider];
        const response=await fetch(p.base+'/chat/completions',{
          method:'POST',headers:{Authorization:'Bearer '+process.env[p.keyEnv],'Content-Type':'application/json',
            ...(m.provider==='openrouter'?{'X-Title':'NOVA AI'}:{})},
          body:JSON.stringify({model:m.model,messages,stream:streaming,temperature:0.8,max_tokens:maxOutput,
            ...(streaming?{stream_options:{include_usage:true}}:{}),
            ...(m.provider==='openrouter'?{provider:{data_collection:'deny'}}:{})}),
          signal:AbortSignal.any([AbortSignal.timeout(Math.min(streaming?60000:30000,Math.max(1000,deadline-Date.now()))),
            ...(opts.signal?[opts.signal]:[])]),
        });
        if(!response.ok){
          retry=retryAfterMs(response.headers?.get('retry-after'));
          status=response.status===429?429:502; code=response.status===429?'AI_RATE_LIMITED':'AI_UPSTREAM_ERROR';
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
          const obj=await response.json() as {choices?:{message?:{content?:unknown}}[];usage?:Usage};
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
        });
        return {text:text.trim().slice(0,tier==='premium'?12000:4000),provider:m.provider,model:m.model,inputTokens,outputTokens,costMicros:cost};
      }catch(error){
        const cancelled=!!opts.signal?.aborted;if(cancelled)code='AI_CANCELLED';
        await db.$transaction(async tx=>{
          await tx.$queryRaw`SELECT key FROM "AiProviderState" WHERE key = ${key} FOR UPDATE`;
          const state=(await tx.aiProviderState.findUnique({where:{key}}))!;
          if(cancelled){ // A user pressing Stop is not a provider fault: no breaker trip, reservation kept.
            await tx.aiAttempt.update({where:{id:attempt.id},data:{status:'FAILED',errorCode:code,latencyMs:Date.now()-started}});
            return;
          }
          const failures=state.failures+1;
          const backoff=Math.min(60000,1000*2**Math.min(failures,6))+Math.floor(Math.random()*500);
          const until=new Date(Date.now()+Math.max(retry,backoff,failures>=3?30000:0));
          await tx.aiProviderState.update({where:{key},data:{failures,retryAt:until,
            ...(certainNoCharge&&state.dayAt.getTime()===day.getTime()?{costMicros:{decrement:maxCost}}:{})}});
          await tx.aiAttempt.update({where:{id:attempt.id},data:{status:'FAILED',errorCode:code,latencyMs:Date.now()-started}});
        });
        lastError=new AiProviderError(code,status,Math.max(1,Math.ceil(retry/1000)||30));
        // A cancelled request, or a stream that already showed text, is never silently continued elsewhere.
        if(opts.signal?.aborted)throw new AiProviderError('AI_CANCELLED',499,1);
        if(emitted)throw new AiProviderError('AI_STREAM_INTERRUPTED',502,5);
      }
    }
    throw lastError;
  }finally{leave();}
}
