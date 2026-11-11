import {beforeAll,beforeEach,afterAll,afterEach,describe,it,expect,vi} from 'vitest';
import {PrismaClient} from '@prisma/client';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import {randomUUID} from 'node:crypto';
import {registerChatRoutes} from '../src/chatRoutes.js';
import {registerStoryRoutes} from '../src/storyRoutes.js';
import {completeAiChat,retryAfterMs,modelPool,quotaKey} from '../src/aiGateway.js';
import {credit,balance,debit} from '../src/ledger.js';
const db=new PrismaClient();const app=Fastify();let userId:string;let other:string;let characterId:string;let chatId:string;
const model=(provider:string,tier='free',extras:any={})=>({provider,tier,model:provider==='openrouter'?'openrouter/free':'test-'+tier,approved:true,rpm:10000,rpd:10000,tpm:10000000,tpd:100000000,inputUsdPerMillion:0,outputUsdPerMillion:0,dailyBudgetMicros:0,...extras});
const ok=(text='Проверенный ответ тестового провайдера')=>new Response(JSON.stringify({choices:[{message:{content:text}}],usage:{prompt_tokens:30,completion_tokens:15}}),{status:200,headers:{'Content-Type':'application/json'}});
const auth=(uid=userId)=>({authorization:'Bearer '+app.jwt.sign({sub:uid})});
const send=(content:string,requestId=randomUUID(),tier='free',expectedPriceTokens=5)=>app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(),payload:{content,requestId,tier,expectedPriceTokens}});
beforeAll(async()=>{
 await app.register(jwt,{secret:'integration-tests-only-not-for-production'});app.decorate('auth',async(req:any,reply:any)=>{try{await req.jwtVerify()}catch{return reply.code(401).send({error:'UNAUTHORIZED'})}});
 await registerChatRoutes(app,db);await registerStoryRoutes(app,db);await app.ready();
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
afterEach(()=>{vi.unstubAllGlobals();vi.unstubAllEnvs()});
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
  let first:any;const mock=vi.fn(async(url:string,init:any)=>{const body=JSON.parse(init.body);if(url.includes('groq')){first=body.messages;if(failure==='timeout')throw new DOMException('timed out','TimeoutError');return new Response('',{status:failure as number,headers:{'Retry-After':'60'}})}expect(body.messages).toEqual(first);return ok('Резервный ответ')});
  vi.stubGlobal('fetch',mock);const result=await send('Продолжи');expect(result.statusCode,result.body).toBe(200);
  expect(mock).toHaveBeenCalledTimes(2);expect((await db.aiProviderState.findUnique({where:{key:quotaKey({provider:'groq',model:'test-free'})}}))!.retryAt!.getTime()).toBeGreaterThan(Date.now());
  const attempts=await db.aiAttempt.findMany({orderBy:{createdAt:'asc'}});expect(attempts.map(x=>x.status)).toEqual(['FAILED','SUCCEEDED']);
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
  vi.stubGlobal('fetch',vi.fn(async(_url:string,init:any)=>{expect(JSON.parse(init.body).messages[0].content).toContain('Маяк на острове');return ok()}));
  const answer=await send('Где маяк?');const branch=await app.inject({method:'POST',url:`/v1/chats/${chatId}/branch`,headers:auth(),payload:{messageId:answer.json().assistantMessage.id}});
  expect(branch.statusCode,branch.body).toBe(201);expect(branch.json().chat.id).not.toBe(chatId);
  const copied=await db.memory.findMany({where:{chatId:branch.json().chat.id}});expect(copied.map(x=>x.content)).toContain('Маяк на острове');
 });
 it('blocks provider quota oversubscription under concurrency',async()=>{
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([model('groq','free',{rpm:2,concurrency:2})]));vi.stubGlobal('fetch',vi.fn(async()=>{await new Promise(r=>setTimeout(r,25));return ok()}));
  const results=await Promise.allSettled(Array.from({length:12},()=>completeAiChat([{role:'system',content:'Fiction'},{role:'user',content:'Hello'}],'free',db)));
  expect(results.filter(x=>x.status==='fulfilled')).toHaveLength(2);expect(await db.aiAttempt.count()).toBe(2);
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
 beforeEach(()=>{vi.stubEnv('NOVA_AI_MODELS_JSON','');vi.stubEnv('AI_PROVIDER','groq');vi.stubEnv('AI_MODEL','');vi.stubEnv('NOVA_GROQ_FREE_MODELS','');vi.stubEnv('NOVA_PREMIUM_MODEL','')});
 it('uses separate per-model Groq quota buckets and falls through to the next Groq model',async()=>{
  expect(modelPool('free').map(m=>m.provider+':'+m.model)).toEqual(['groq:openai/gpt-oss-20b','groq:openai/gpt-oss-120b','openrouter:openrouter/free']);
  const day=new Date(new Date().toISOString().slice(0,10));
  await db.aiProviderState.create({data:{key:'groq:openai/gpt-oss-20b',minuteAt:new Date(Math.floor(Date.now()/60000)*60000),dayAt:day,dayRequests:1000}});
  const mock=vi.fn(async(_u:any,init:any)=>ok());vi.stubGlobal('fetch',mock);
  const r=await send('Привет');expect(r.statusCode,r.body).toBe(200);
  expect(mock).toHaveBeenCalledTimes(1);expect(JSON.parse(mock.mock.calls[0][1].body).model).toBe('openai/gpt-oss-120b');
  expect((await db.aiProviderState.findUnique({where:{key:'groq:openai/gpt-oss-120b'}}))!.dayRequests).toBe(1);
  expect((await db.aiProviderState.findUnique({where:{key:'groq:openai/gpt-oss-20b'}}))!.dayRequests).toBe(1000);
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
