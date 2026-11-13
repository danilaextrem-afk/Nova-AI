import {beforeAll,beforeEach,afterAll,afterEach,describe,it,expect,vi} from 'vitest';
import {PrismaClient} from '@prisma/client';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import {randomUUID} from 'node:crypto';
import {registerChatRoutes} from '../src/chatRoutes.js';
import {registerStoryRoutes} from '../src/storyRoutes.js';
import {completeAiChat,retryAfterMs,modelPool,quotaKey,breakerKey,estimateTokens,providerRequest} from '../src/aiGateway.js';
import {credit,balance,debit} from '../src/ledger.js';
const db=new PrismaClient();const app=Fastify();let drainChatJobs: () => Promise<void>;let userId:string;let other:string;let characterId:string;let chatId:string;
const model=(provider:string,tier='free',extras:any={})=>({provider,tier,model:provider==='openrouter'?'openrouter/free':'test-'+tier,approved:true,rpm:10000,rpd:10000,tpm:10000000,tpd:100000000,inputUsdPerMillion:0,outputUsdPerMillion:0,dailyBudgetMicros:0,...extras});
const ok=(text='Проверенный ответ тестового провайдера')=>new Response(JSON.stringify({choices:[{message:{content:text}}],usage:{prompt_tokens:30,completion_tokens:15}}),{status:200,headers:{'Content-Type':'application/json'}});
const auth=(uid=userId)=>({authorization:'Bearer '+app.jwt.sign({sub:uid})});
const send=(content:string,requestId=randomUUID(),tier='free',expectedPriceTokens=5)=>app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(),payload:{content,requestId,tier,expectedPriceTokens}});
beforeAll(async()=>{
 await app.register(jwt,{secret:'integration-tests-only-not-for-production'});app.decorate('auth',async(req:any,reply:any)=>{try{await req.jwtVerify()}catch{return reply.code(401).send({error:'UNAUTHORIZED'})}});
 drainChatJobs = await registerChatRoutes(app,db);await registerStoryRoutes(app,db);await app.ready();
 userId=(await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test'}})).id;
 other=(await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test'}})).id;
 characterId=(await db.character.create({data:{ownerId:userId,name:'Взрослый тестовый герой',greeting:'Привет',category:'Приключения',tags:[]}})).id;
});
beforeEach(async()=>{
 vi.stubEnv('GROQ_API_KEY','fixture-not-a-key');vi.stubEnv('OPENROUTER_API_KEY','fixture-not-a-key');
 vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq'),model('openrouter'),model('groq','premium')]));
 await db.aiAttempt.deleteMany();await db.aiProviderState.deleteMany();
 chatId=(await db.chat.create({data:{userId,characterId}})).id;
});
afterEach(async () => { await drainChatJobs(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
afterAll(async()=>{await app.close();await db.user.deleteMany({where:{id:{in:[userId,other]}}});await db.$disconnect()});
describe('PostgreSQL router and persisted chat invariants (HTTP fixtures, not live AI)',()=>{
 it('100 consecutive Free turns have no token debit and history pages include the latest answer',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok()));const start=await balance(db,userId);
  for(let i=0;i<100;i++){const r=await send('Message '+i);expect(r.statusCode,r.body).toBe(200);expect(r.json().tokensCharged).toBe(0)}
  expect(await balance(db,userId)).toEqual(start);
  expect(await db.message.count({where:{chatId}})).toBe(200);
  const first=await app.inject({url:`/v1/chats/${chatId}/messages`,headers:auth()});expect(first.json().items.at(-2).content).toBe('Message 99');
  const second=await app.inject({url:`/v1/chats/${chatId}/messages?before=${first.json().nextBefore}`,headers:auth()});
  expect(second.json().items).toHaveLength(50);expect(second.json().items.at(-1).sequence).toBeLessThan(first.json().items[0].sequence);
 });
 it.each([429,503,'timeout'])('fails over to an independent provider after %s with unchanged messages',async(failure)=>{
  const requestId = randomUUID();
  let first:any;const mock=vi.fn(async(url:string,init:any)=>{const body=JSON.parse(init.body);if(url.includes('groq')){first=body.messages;if(failure==='timeout')throw new DOMException('timed out','TimeoutError');return new Response('',{status:failure as number,headers:{'Retry-After':'60'}})}expect(body.messages).toEqual(first);return ok('Резервный ответ')});
  vi.stubGlobal('fetch',mock);const result=await send('Продолжи', requestId);expect(result.statusCode,result.body).toBe(200);
  expect(mock).toHaveBeenCalledTimes(2);expect((await db.aiProviderState.findUnique({where:{key:quotaKey({provider:'groq',model:'test-free'})}}))!.retryAt!.getTime()).toBeGreaterThan(Date.now());
  const attempts=await db.aiAttempt.findMany({where:{requestId},orderBy:{createdAt:'asc'}});expect(attempts.map(x=>x.status)).toEqual(['FAILED','SUCCEEDED']);
 });
 it('preserves failed user text, charges nothing, and retries the same request ID',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('',{status:429})));const id=randomUUID();const before=await balance(db,userId);
  expect((await send('Сохрани мой черновик',id)).statusCode).toBe(429);
  expect((await db.chatRequest.findUnique({where:{id}}))?.content).toBe('Сохрани мой черновик');expect(await balance(db,userId)).toEqual(before);
  await db.aiProviderState.updateMany({data:{retryAt:null}});vi.stubGlobal('fetch',vi.fn(async()=>ok()));expect((await send('Сохрани мой черновик',id)).statusCode).toBe(200);
 });
 it('returns the exact old answer on replay after later turns and rejects payload reuse',async()=>{
  let n=0;vi.stubGlobal('fetch',vi.fn(async()=>ok('Ответ '+(++n))));const id=randomUUID();const a=await send('Первый',id);await send('Второй');
  const replay=await send('Первый',id);expect(replay.json().assistantMessage.id).toBe(a.json().assistantMessage.id);expect(n).toBe(2);
  expect((await send('Другой текст',id)).statusCode).toBe(409);
 });
 it('serializes simultaneous duplicate Premium requests and performs one debit',async()=>{
  await credit(db,{userId,bucket:'BONUS',amount:50,source:'test',idempotencyKey:randomUUID()});const before=await balance(db,userId);
  vi.stubGlobal('fetch',vi.fn(async()=>{await new Promise(r=>setTimeout(r,40));return ok()}));const id=randomUUID();
  const all=await Promise.all(Array.from({length:10},()=>send('Premium',id,'premium')));
  expect(all.filter(x=>x.statusCode===200).length).toBeGreaterThan(0);expect(all.every(x=>[200,409].includes(x.statusCode))).toBe(true);
  expect((await balance(db,userId)).total).toBe(before.total-5);expect(await db.aiUsage.count({where:{id}})).toBe(1);
 });
 it('requires exact accepted price before any paid provider call',async()=>{
  const mock=vi.fn();vi.stubGlobal('fetch',mock);const r=await send('Paid',randomUUID(),'premium',1);expect(r.statusCode).toBe(409);expect(mock).not.toHaveBeenCalled();
 });
 it('scopes editable pinned memories to the caller and branch timeline',async()=>{
  const r=await app.inject({method:'POST',url:`/v1/chats/${chatId}/memories`,headers:auth(),payload:{content:'Маяк на острове',pinned:true}});expect(r.statusCode).toBe(200);
  const id=r.json().memory.id;const denied=await app.inject({method:'DELETE',url:`/v1/chats/${chatId}/memories/${id}`,headers:auth(other)});expect(denied.statusCode).toBe(404);
  vi.stubGlobal('fetch',vi.fn(async(_url:string,init:any)=>{expect(JSON.parse(init.body).messages[1].content).toContain('Маяк на острове');return ok()}));
  const answer=await send('Где маяк?');const branch=await app.inject({method:'POST',url:`/v1/chats/${chatId}/branch`,headers:auth(),payload:{messageId:answer.json().assistantMessage.id}});
  expect(branch.statusCode,branch.body).toBe(201);expect(branch.json().chat.id).not.toBe(chatId);
  const copied=await db.memory.findMany({where:{chatId:branch.json().chat.id}});expect(copied.map(x=>x.content)).toContain('Маяк на острове');
 });
 it('blocks provider quota oversubscription under concurrency',async()=>{
  // Pin the clock to the start of a minute: crossing a minute boundary mid-test legitimately resets the rpm window.
  vi.useFakeTimers({toFake:['Date'],now:Math.floor(Date.now()/60000)*60000+5000});
  try{
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq','free',{rpm:2,concurrency:2})]));vi.stubGlobal('fetch',vi.fn(async()=>{await new Promise(r=>setTimeout(r,25));return ok()}));
  const results=await Promise.allSettled(Array.from({length:12},()=>completeAiChat([{role:'system',content:'Fiction'},{role:'user',content:'Hello'}],'free',db)));
  expect(results.filter(x=>x.status==='fulfilled')).toHaveLength(2);expect(await db.aiAttempt.count()).toBe(2);
  }finally{vi.useRealTimers();}
 });
 it('blocks paid calls above daily provider budget',async()=>{
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq','premium',{inputUsdPerMillion:20,outputUsdPerMillion:80,dailyBudgetMicros:1})]));
  const mock=vi.fn();vi.stubGlobal('fetch',mock);await expect(completeAiChat([{role:'system',content:'Fiction'},{role:'user',content:'Hello'}],'premium',db)).rejects.toThrow('AI_RATE_LIMITED');expect(mock).not.toHaveBeenCalled();
 });
 it('does not silently include paid models in Free or Cerebras trial as permanently free',()=>{
  vi.stubEnv('CEREBRAS_API_KEY','fixture');vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq','free',{inputUsdPerMillion:1}),model('cerebras')]));expect(modelPool('free')).toEqual([]);
 });
 it('parses numeric and HTTP-date Retry-After',()=>{expect(retryAfterMs('60')).toBe(60000);expect(retryAfterMs('Wed, 11 Nov 2026 12:01:00 GMT',Date.parse('2026-11-11T12:00:00Z'))).toBe(60000)});
 it('concurrent wallet debits with the same key debit once',async()=>{
  await credit(db,{userId,bucket:'BONUS',amount:20,source:'test',idempotencyKey:randomUUID()});const before=await balance(db,userId);const key=randomUUID();
  await Promise.all(Array.from({length:10},()=>debit(db,{userId,amount:5,source:'test',idempotencyKey:key})));
  expect((await balance(db,userId)).total).toBe(before.total-5);
 });
});

describe('Default free pool (no NOVA_AI_MODELS_JSON; HTTP fixtures, not live AI)',()=>{
 beforeEach(()=>{vi.stubEnv('NOVA_AI_MODELS_JSON','');vi.stubEnv('AI_PROVIDER','groq');vi.stubEnv('AI_MODEL','');vi.stubEnv('NOVA_GROQ_FREE_MODELS','');vi.stubEnv('NOVA_OPENROUTER_FREE_MODELS','');vi.stubEnv('GEMINI_API_KEY','');vi.stubEnv('CLOUDFLARE_API_TOKEN','');vi.stubEnv('NOVA_PREMIUM_MODEL','')});
 it('uses separate per-model Groq quota buckets and falls through to the next Groq model',async()=>{
  expect(modelPool('free').map(m=>m.provider+':'+m.model)).toEqual(['groq:openai/gpt-oss-120b','groq:openai/gpt-oss-20b','openrouter:openrouter/free']);
  const day=new Date(new Date().toISOString().slice(0,10));
  await db.aiProviderState.create({data:{key:'groq:openai/gpt-oss-120b',minuteAt:new Date(Math.floor(Date.now()/60000)*60000),dayAt:day,dayRequests:1000}});
  const mock=vi.fn(async(_u:any,init:any)=>ok());vi.stubGlobal('fetch',mock);
  const r=await send('Привет');expect(r.statusCode,r.body).toBe(200);
  expect(mock).toHaveBeenCalledTimes(1);expect(JSON.parse(mock.mock.calls[0][1].body).model).toBe('openai/gpt-oss-20b');
  expect((await db.aiProviderState.findUnique({where:{key:'groq:openai/gpt-oss-20b'}}))!.dayRequests).toBe(1);
  expect((await db.aiProviderState.findUnique({where:{key:'groq:openai/gpt-oss-120b'}}))!.dayRequests).toBe(1000);
 });
 it('never hands out the configured Premium model as Free',async()=>{
  vi.stubEnv('NOVA_PREMIUM_MODEL','openai/gpt-oss-120b');vi.stubEnv('NOVA_AI_DAILY_BUDGET_MICROS','100000');
  expect(modelPool('free').map(m=>m.model)).toEqual(['openai/gpt-oss-20b','openrouter/free']);
  expect(modelPool('premium').map(m=>m.model)).toEqual(['openai/gpt-oss-120b']);
 });
});

describe('Regenerate latest reply (HTTP fixtures, not live AI)',()=>{
 const regen=(messageId:string,requestId=randomUUID(),tier='free',expectedPriceTokens=5)=>app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(),payload:{regenerateMessageId:messageId,requestId,tier,expectedPriceTokens}});
 it('replaces only the latest reply, keeps the old version, reuses the same user turn and charges nothing on Free',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok('Первый ответ')));const first=(await send('Расскажи историю')).json();
  const start=await balance(db,userId);
  const mock=vi.fn(async(_u:any,_i:any)=>ok('Другой ответ'));vi.stubGlobal('fetch',mock);
  const id=randomUUID();const r=await regen(first.assistantMessage.id,id);
  expect(r.statusCode,r.body).toBe(200);expect(r.json().tokensCharged).toBe(0);
  const sent=JSON.parse(mock.mock.calls[0][1].body).messages;
  expect(sent.at(-1)).toEqual({role:'user',content:'Расскажи историю'});
  expect(sent.some((m:any)=>m.content==='Первый ответ')).toBe(false);
  const msg=await db.message.findUnique({where:{id:first.assistantMessage.id}});
  expect(msg!.content).toBe('Другой ответ');expect(msg!.alternatives).toEqual(['Первый ответ']);
  expect(await db.message.count({where:{chatId}})).toBe(2);expect(await balance(db,userId)).toEqual(start);
  const replay=await regen(first.assistantMessage.id,id);expect(replay.json().duplicate).toBe(true);
  expect(replay.json().assistantMessage.content).toBe('Другой ответ');expect(mock).toHaveBeenCalledTimes(1);
  const tierSwap=await regen(first.assistantMessage.id,id,'premium');expect(tierSwap.statusCode).toBe(409);
 });
 it('refuses to regenerate an older reply and never touches user text',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok()));const a=(await send('Один')).json();await send('Два');
  const r=await regen(a.assistantMessage.id);expect(r.statusCode).toBe(409);expect(r.json().error).toBe('REGENERATE_ONLY_LATEST_REPLY');
  const u=await regen(a.userMessage.id);expect(u.statusCode).toBe(409);
  const both=await app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(),payload:{content:'x',regenerateMessageId:a.assistantMessage.id,requestId:randomUUID()}});
  expect(both.statusCode).toBe(400);
 });
 it('Premium regeneration debits once on success and nothing when the provider fails',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok()));const a=(await send('Сцена')).json();
  await credit(db,{userId,bucket:'BONUS',amount:20,source:'test',idempotencyKey:randomUUID()});const before=await balance(db,userId);
  vi.stubGlobal('fetch',vi.fn(async()=>new Response('{}',{status:500})));
  const failed=await regen(a.assistantMessage.id,randomUUID(),'premium');expect(failed.statusCode).toBeGreaterThanOrEqual(500);
  expect(await balance(db,userId)).toEqual(before);
  await db.aiProviderState.updateMany({data:{retryAt:null}});
  vi.stubGlobal('fetch',vi.fn(async()=>ok('Премиум-версия')));const id=randomUUID();
  const done=await regen(a.assistantMessage.id,id,'premium');expect(done.statusCode,done.body).toBe(200);
  expect(done.json().tokensCharged).toBe(5);await regen(a.assistantMessage.id,id,'premium');
  expect((await balance(db,userId)).total).toBe(before.total-5);
 });
});

describe('Streaming replies over SSE (HTTP fixtures, not live AI)',()=>{
 const enc=new TextEncoder();
 const chunk=(content:string)=>`data: ${JSON.stringify({choices:[{delta:{content}}]})}\n\n`;
 const usage=`data: ${JSON.stringify({choices:[],usage:{prompt_tokens:40,completion_tokens:6}})}\n\ndata: [DONE]\n\n`;
 const streamResponse=(parts:string[],mode:{fail?:boolean,signal?:AbortSignal}={})=>{let i=0;return new Response(new ReadableStream({async pull(c){
  if(i<parts.length){c.enqueue(enc.encode(parts[i++]));return;}
  if(mode.fail)throw new Error('socket hang up');
  if(mode.signal)await new Promise((_,reject)=>{if(mode.signal!.aborted)return reject(new Error('aborted'));mode.signal!.addEventListener('abort',()=>reject(new Error('aborted')));});
  c.close();}}),{status:200,headers:{'content-type':'text/event-stream'}});};
 const stream=(payload:any)=>app.inject({method:'POST',url:`/v1/chats/${chatId}/messages/stream`,headers:auth(),payload});
 const events=(body:string)=>body.split('\n\n').filter(b=>b.startsWith('event:')).map(b=>({name:b.split('\n')[0].slice(7),data:JSON.parse(b.split('\n')[1].slice(6))}));
 it('forwards deltas in order and saves exactly the streamed text',async()=>{
  const mock=vi.fn(async(_u:any,_i:any)=>streamResponse([chunk('Привет, '),chunk('путник!'),usage]));vi.stubGlobal('fetch',mock);
  const r=await stream({content:'Здравствуй',requestId:randomUUID()});
  expect(r.headers['content-type']).toContain('text/event-stream');
  const ev=events(r.body);
  expect(ev.filter(e=>e.name==='delta').map(e=>e.data.text)).toEqual(['Привет, ','путник!']);
  const done=ev.at(-1)!;expect(done.name).toBe('done');expect(done.data.assistantMessage.content).toBe('Привет, путник!');expect(done.data.tokensCharged).toBe(0);
  expect(JSON.parse(mock.mock.calls[0][1].body).stream).toBe(true);
  expect((await db.message.findMany({where:{chatId},orderBy:{sequence:'asc'}})).map(m=>m.content)).toEqual(['Здравствуй','Привет, путник!']);
 });
 it('an interrupted Premium stream is not failed over, not saved and not charged',async()=>{
  await credit(db,{userId,bucket:'BONUS',amount:20,source:'test',idempotencyKey:randomUUID()});const before=await balance(db,userId);
  const mock=vi.fn(async()=>streamResponse([chunk('Начало')],{fail:true}));vi.stubGlobal('fetch',mock);
  const free=await stream({content:'Сцена',requestId:randomUUID()});
  expect(events(free.body).at(-1)!.data.error).toBe('AI_STREAM_INTERRUPTED');
  expect(mock).toHaveBeenCalledTimes(1); // OpenRouter is configured too, but shown text is never continued by another model.
  await db.aiProviderState.updateMany({data:{retryAt:null}});
  const r=await stream({content:'Сцена',requestId:randomUUID(),tier:'premium',expectedPriceTokens:5});
  const last=events(r.body).at(-1)!;expect(last.name).toBe('error');expect(last.data.error).toBe('AI_STREAM_INTERRUPTED');
  expect(mock).toHaveBeenCalledTimes(2);expect(await db.message.count({where:{chatId}})).toBe(0);
  expect(await balance(db,userId)).toEqual(before);
 });
 it('user cancel stops the upstream call without tripping the provider circuit breaker',async()=>{
  const ac=new AbortController();
  vi.stubGlobal('fetch',vi.fn(async(_u:any,init:any)=>streamResponse([chunk('Пишу...')],{signal:init.signal})));
  const seen:string[]=[];
  await expect(completeAiChat([{role:'system',content:'s'},{role:'user',content:'u'}],'free',db,randomUUID(),
   {onDelta:t=>{seen.push(t);ac.abort();},signal:ac.signal})).rejects.toMatchObject({code:'AI_CANCELLED'});
  expect(seen).toEqual(['Пишу...']);
  const state=await db.aiProviderState.findUnique({where:{key:quotaKey({provider:'groq',model:'test-free'})}});
  expect(state!.failures).toBe(0);expect(state!.retryAt).toBeNull();
  expect((await db.aiAttempt.findFirst({orderBy:{createdAt:'desc'}}))!.errorCode).toBe('AI_CANCELLED');
 });
});

describe('Truncated SSE protection (HTTP fixture)',()=>{
 it('does not save or debit Premium on a clean EOF without a completion marker',async()=>{
  await credit(db,{userId,bucket:'BONUS',amount:20,source:'test',idempotencyKey:randomUUID()});const before=await balance(db,userId);
  const mock=vi.fn(async()=>new Response('data: '+JSON.stringify({choices:[{delta:{content:'Незаконченный ответ'}}]})+'\n\n',{headers:{'content-type':'text/event-stream'}}));vi.stubGlobal('fetch',mock);
  const r=await app.inject({method:'POST',url:`/v1/chats/${chatId}/messages/stream`,headers:auth(),payload:{content:'Сцена',requestId:randomUUID(),tier:'premium',expectedPriceTokens:5}});
  expect(r.body).toContain('AI_STREAM_INTERRUPTED');expect(r.body).not.toContain('event: done');
  expect(await balance(db,userId)).toEqual(before);expect(await db.message.count({where:{chatId}})).toBe(0);expect(mock).toHaveBeenCalledTimes(1);
 });
});

describe('Edit, delete and search messages',()=>{
 it('edits only own text, deletes a turn with its reply, searches inside one chat and isolates other users',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>ok('Ответ про драконов')));
  const a=(await send('Расскажи о драконах')).json();await send('А теперь о море');
  const url=(m:string)=>`/v1/chats/${chatId}/messages/${m}`;
  const e=await app.inject({method:'PATCH',url:url(a.userMessage.id),headers:auth(),payload:{content:'Расскажи о северных драконах'}});
  expect(e.statusCode,e.body).toBe(200);expect(e.json().message.editedAt).not.toBeNull();
  expect((await app.inject({method:'PATCH',url:url(a.assistantMessage.id),headers:auth(),payload:{content:'подмена'}})).statusCode).toBe(409);
  expect((await app.inject({method:'PATCH',url:url(a.userMessage.id),headers:auth(other),payload:{content:'чужое'}})).statusCode).toBe(404);
  const found=await app.inject({url:`/v1/chats/${chatId}/messages?q=СЕВЕРН`,headers:auth()});
  expect(found.json().items.map((m:any)=>m.id)).toEqual([a.userMessage.id]);
  expect((await app.inject({method:'DELETE',url:url(a.userMessage.id),headers:auth(other)})).statusCode).toBe(404);
  const d=await app.inject({method:'DELETE',url:url(a.userMessage.id),headers:auth()});
  expect(d.json().deleted).toBe(2);
  expect((await db.message.findMany({where:{chatId},orderBy:{sequence:'asc'}})).map(m=>m.content)).toEqual(['А теперь о море','Ответ про драконов']);
 });
});

describe('Gemini native adapter (HTTP fixtures, not live AI)',()=>{
 const geminiModel=[{provider:'gemini',tier:'free',model:'gemini-flash-latest',approved:true,rpm:1000,rpd:1000,tpm:10000000,tpd:100000000,inputUsdPerMillion:0,outputUsdPerMillion:0,dailyBudgetMicros:0}];
 it('is excluded until the owner has reviewed the free-tier data terms',()=>{
  vi.stubEnv('GEMINI_API_KEY','AQ.fixture');vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify(geminiModel));vi.stubEnv('NOVA_GEMINI_TERMS_REVIEWED','');
  expect(modelPool('free')).toEqual([]);
  vi.stubEnv('NOVA_GEMINI_TERMS_REVIEWED','true');expect(modelPool('free').map(m=>m.provider)).toEqual(['gemini']);
 });
 it('calls the native endpoint with the AQ key header, maps roles and system prompt, and streams text',async()=>{
  vi.stubEnv('GEMINI_API_KEY','AQ.fixture-key');vi.stubEnv('NOVA_GEMINI_TERMS_REVIEWED','true');vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify(geminiModel));
  const sse=['{"candidates":[{"content":{"parts":[{"text":"думаю","thought":true}]}}]}','{"candidates":[{"content":{"parts":[{"text":"Привет, "}]}}]}',
   '{"candidates":[{"content":{"parts":[{"text":"путник!"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":4}}'].map(x=>'data: '+x+'\r\n\r\n').join('');
  const mock=vi.fn(async(_u:any,_i:any)=>new Response(sse,{status:200,headers:{'content-type':'text/event-stream'}}));vi.stubGlobal('fetch',mock);
  const seen:string[]=[];
  const out=await completeAiChat([{role:'system',content:'Ты персонаж.'},{role:'user',content:'А'},{role:'assistant',content:'Б'},{role:'user',content:'В'}],'free',db,randomUUID(),{onDelta:t=>seen.push(t)});
  expect(out.text).toBe('Привет, путник!');expect(seen).toEqual(['Привет, ','путник!']);expect(out.provider).toBe('gemini');
  const [url,init]=mock.mock.calls[0];
  expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:streamGenerateContent?alt=sse');
  expect(init.headers['x-goog-api-key']).toBe('AQ.fixture-key');expect(init.headers.Authorization).toBeUndefined();
  const body=JSON.parse(init.body);
  expect(body.systemInstruction.parts[0].text).toBe('Ты персонаж.');
  expect(body.contents.map((c:any)=>c.role)).toEqual(['user','model','user']);
 });
 it('treats a safety block as a content-filter failure without saving text',async()=>{
  vi.stubEnv('GEMINI_API_KEY','AQ.fixture-key');vi.stubEnv('NOVA_GEMINI_TERMS_REVIEWED','true');vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify(geminiModel));
  vi.stubGlobal('fetch',vi.fn(async()=>new Response(JSON.stringify({promptFeedback:{blockReason:'SAFETY'}}),{status:200,headers:{'content-type':'application/json'}})));
  await expect(completeAiChat([{role:'system',content:'s'},{role:'user',content:'u'}],'free',db,randomUUID())).rejects.toBeTruthy();
 });
});

describe('Cloudflare Workers AI adapter (HTTP fixtures, not live AI)',()=>{
 const cf=[{provider:'cloudflare',tier:'free',model:'@cf/openai/gpt-oss-20b',approved:true,rpm:1000,rpd:1000,tpm:10000000,tpd:100000000,inputUsdPerMillion:0,outputUsdPerMillion:0,dailyBudgetMicros:0}];
 it('needs a valid account id, then calls the account-scoped OpenAI-compatible endpoint with the token',async()=>{
  vi.stubEnv('CLOUDFLARE_API_TOKEN','cfut_fixture');vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify(cf));
  vi.stubEnv('CLOUDFLARE_ACCOUNT_ID','not-an-id');expect(modelPool('free')).toEqual([]);
  vi.stubEnv('CLOUDFLARE_ACCOUNT_ID','e9349634542ae1b6b5730a9ffdad2855');
  const mock=vi.fn(async(_u:any,_i:any)=>ok('Ответ Workers AI'));vi.stubGlobal('fetch',mock);
  const out=await completeAiChat([{role:'system',content:'s'},{role:'user',content:'u'}],'free',db,randomUUID());
  expect(out.text).toBe('Ответ Workers AI');expect(out.provider).toBe('cloudflare');
  expect(mock.mock.calls[0][0]).toBe('https://api.cloudflare.com/client/v4/accounts/e9349634542ae1b6b5730a9ffdad2855/ai/v1/chat/completions');
  expect(mock.mock.calls[0][1].headers.Authorization).toBe('Bearer cfut_fixture');
  expect(JSON.parse(mock.mock.calls[0][1].body).model).toBe('@cf/openai/gpt-oss-20b');
 });
});

// Regression for the 502 AI_UPSTREAM_ERROR seen in live runs on 886ad2c/75ac117 (2026-11-12).
describe('Provider recovery (HTTP fixtures, not live AI)',()=>{
 const msgs=[{role:'system' as const,content:'Сцена'},{role:'user' as const,content:'Привет'}];
 const two=(a:string,b:string)=>[{...model('openrouter'),model:a},{...model('openrouter'),model:b}];
 it('estimates Russian prompts close to real usage instead of reserving ~5x by UTF-8 bytes',()=>{
  const text='Вечер в архиве Луногорска, фонарь мерцает над старыми картами. '.repeat(50); // ~3200 chars
  const est=estimateTokens([{role:'user',content:text}]);
  expect(est).toBeGreaterThan(text.length/3); // Still above the measured ~2.7 chars per token.
  expect(est).toBeLessThan(Buffer.byteLength(text,'utf8')/3);
 });
 it('lets several ordinary turns per minute use one Groq model (8k tokens/minute) without spilling to fallbacks',async()=>{
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq','free',{tpm:8000}),model('openrouter')]));
  const mock=vi.fn(async()=>new Response(JSON.stringify({choices:[{message:{content:'Ответ'}}],usage:{prompt_tokens:1300,completion_tokens:150}}),{status:200,headers:{'Content-Type':'application/json'}}));
  vi.stubGlobal('fetch',mock);
  const prompt=[{role:'system' as const,content:'Лист персонажа и память. '.repeat(120)},{role:'user' as const,content:'Что дальше?'}]; // ~3000 chars
  for(let i=0;i<4;i++)expect((await completeAiChat(prompt,'free',db)).provider).toBe('groq');
 });
 it('a broken OpenRouter model does not block the next one, and records the provider status',async()=>{
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify(two('dead/model:free','openrouter/free')));
  const warn=vi.spyOn(console,'warn').mockImplementation(()=>{});
  vi.stubGlobal('fetch',vi.fn(async(_u:string,init:any)=>JSON.parse(init.body).model==='dead/model:free'
   ?new Response(JSON.stringify({error:{message:'No endpoints found for dead/model:free.'}}),{status:404}):ok('Живая модель')));
  const r=await completeAiChat(msgs,'free',db,randomUUID());expect(r.model).toBe('openrouter/free');
  const failed=await db.aiAttempt.findFirst({where:{status:'FAILED'}});expect(failed!.errorCode).toBe('AI_UPSTREAM_ERROR:404');
  expect(warn.mock.calls.map(c=>String(c[0])).join()).toContain('No endpoints found');
  const dead=await db.aiProviderState.findUnique({where:{key:breakerKey({provider:'openrouter',model:'dead/model:free'})}});
  expect(dead!.retryAt!.getTime()).toBeGreaterThan(Date.now());
  expect((await db.aiProviderState.findUnique({where:{key:'openrouter'}}))!.retryAt).toBeNull();
  // Next request goes straight to the live model while the broken one rests.
  const again=await completeAiChat(msgs,'free',db,randomUUID());expect(again.model).toBe('openrouter/free');
  expect(await db.aiAttempt.count({where:{model:'dead/model:free'}})).toBe(1);
  warn.mockRestore();
 });
 it.each([503,'empty','timeout'])('retries once after a transient %s when every provider failed, then succeeds without double charge',async(kind)=>{
  vi.spyOn(console,'warn').mockImplementation(()=>{});
  await credit(db,{userId,bucket:'BONUS',amount:20,source:'test',idempotencyKey:randomUUID()});const before=await balance(db,userId);
  const calls:string[]=[];let n=0;
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>{calls.push(url.includes('groq')?'groq':'openrouter');n++;
   if(n===1){if(kind==='timeout')throw new DOMException('timed out','TimeoutError');
    return kind==='empty'?ok(' '):new Response('{}',{status:503});}
   return ok('Со второй попытки');}));
  const r=await send('Продолжим',randomUUID(),'premium');expect(r.statusCode,r.body).toBe(200);
  expect(r.json().assistantMessage.content).toBe('Со второй попытки');
  expect((await balance(db,userId)).total).toBe(before.total-5);
  expect(n).toBe(2);
 });
 it('never retries more than once per model and stops on rate limits',async()=>{
  vi.spyOn(console,'warn').mockImplementation(()=>{});
  const fail=vi.fn(async()=>new Response('{}',{status:503}));vi.stubGlobal('fetch',fail);
  await expect(completeAiChat(msgs,'free',db,randomUUID())).rejects.toMatchObject({code:'AI_UPSTREAM_ERROR'});
  expect(fail).toHaveBeenCalledTimes(4); // groq + openrouter, then one retry each.
  await db.aiProviderState.deleteMany();
  const limited=vi.fn(async()=>new Response('{}',{status:429,headers:{'Retry-After':'30'}}));vi.stubGlobal('fetch',limited);
  await expect(completeAiChat(msgs,'free',db,randomUUID())).rejects.toMatchObject({code:'AI_RATE_LIMITED'});
  expect(limited).toHaveBeenCalledTimes(2);
 });
 it.each([[400,null],[401,null],[403,null],[404,null],[503,'60']] as const)(
  'does not immediately repeat provider HTTP %s with Retry-After %s', async (status, retryAfter) => {
   const requests: string[] = [];
   vi.stubGlobal('fetch', vi.fn(async (url: string) => {
    const groq = url.includes('groq');
    requests.push(groq ? 'groq' : 'openrouter');
    const headers = groq && retryAfter ? {'Retry-After': retryAfter} : undefined;
    return new Response('', {status: groq ? status : 429, headers});
   }));
   const requestId = randomUUID();
   await expect(completeAiChat(msgs, 'free', db, requestId)).rejects.toBeTruthy();
   expect(requests).toEqual(['groq', 'openrouter']);
   const attempts = await db.aiAttempt.findMany({where:{requestId,provider:'groq'}});
   expect(attempts.map(x => x.errorCode)).toEqual([`AI_UPSTREAM_ERROR:${status}`]);
  },
 );
 it('gives gpt-oss on Cloudflare room for its hidden reasoning',()=>{
  vi.stubEnv('CLOUDFLARE_ACCOUNT_ID','e9349634542ae1b6b5730a9ffdad2855');
  expect(providerRequest({provider:'cloudflare',model:'@cf/openai/gpt-oss-20b'},msgs,false,360).body.max_tokens).toBe(1024);
  expect(providerRequest({provider:'groq',model:'openai/gpt-oss-20b'},msgs,false,360).body.max_tokens).toBe(360);
 });
});

// Regression: a live request failed after every free model's minute window was full and Gemini timed out.
describe('Waiting for the next quota minute (HTTP fixtures)',()=>{
 const msgs=[{role:'system' as const,content:'Сцена'},{role:'user' as const,content:'Привет'}];
 const fill=async(minuteStart:number)=>db.aiProviderState.create({data:{key:quotaKey({provider:'groq',model:'test-free'}),
  minuteAt:new Date(minuteStart),dayAt:new Date(new Date(minuteStart).toISOString().slice(0,10)),minuteRequests:1}});
 afterEach(()=>{vi.useRealTimers();});
 it('waits a few seconds for the next minute instead of failing when only the minute window is full',async()=>{
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq','free',{rpm:1})]));
  const minute=Math.floor(Date.now()/60000)*60000;
  vi.useFakeTimers({toFake:['Date'],now:minute+57500,shouldAdvanceTime:true});await fill(minute);
  const mock=vi.fn(async()=>ok('После ожидания'));vi.stubGlobal('fetch',mock);
  const started=Date.now();const r=await completeAiChat(msgs,'free',db,randomUUID());
  expect(r.text).toBe('После ожидания');expect(mock).toHaveBeenCalledTimes(1);
  expect(Date.now()-started).toBeGreaterThanOrEqual(2500);expect(Date.now()-started).toBeLessThan(10000);
 },20000);
 it('does not wait long: far from the next minute it reports the rate limit at once',async()=>{
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq','free',{rpm:1})]));
  const minute=Math.floor(Date.now()/60000)*60000;
  vi.useFakeTimers({toFake:['Date'],now:minute+5000,shouldAdvanceTime:true});await fill(minute);
  const mock=vi.fn(async()=>ok());vi.stubGlobal('fetch',mock);
  const started=Date.now();
  await expect(completeAiChat(msgs,'free',db,randomUUID())).rejects.toMatchObject({code:'AI_RATE_LIMITED'});
  expect(mock).not.toHaveBeenCalled();expect(Date.now()-started).toBeLessThan(2000);
 });
});
