import {beforeAll,afterAll,afterEach,beforeEach,describe,it,expect,vi} from 'vitest';
import {PrismaClient} from '@prisma/client';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import {randomUUID} from 'node:crypto';
import {registerArtRoutes,type ArtDeps} from '../src/artRoutes.js';
import {balance,credit,debit} from '../src/ledger.js';
import {rewardDay} from '../src/rewardRoutes.js';
const db=new PrismaClient();const app=Fastify();const created:string[]=[];
const IMG='A'.repeat(2000);
const deps:ArtDeps={prepare:vi.fn(async(t:string)=>({allowed:!/dragon-refuse/.test(t),prompt:'a calm lake at dawn'})),generate:vi.fn(async()=>IMG)};
const user=async(data:any={})=>{const u=await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test',...data}});created.push(u.id);return u.id;};
const auth=(uid:string)=>({authorization:'Bearer '+app.jwt.sign({sub:uid})});
const art=(uid:string,prompt='Озеро на рассвете',requestId=randomUUID())=>app.inject({method:'POST',url:'/v1/art',headers:auth(uid),payload:{prompt,style:'fantasy',requestId}});
beforeAll(async()=>{await app.register(jwt,{secret:'integration-tests-only-not-for-production'});
 app.decorate('auth',async(req:any,reply:any)=>{try{await req.jwtVerify()}catch{return reply.code(401).send({error:'UNAUTHORIZED'})}});
 await registerArtRoutes(app,db,deps);await app.ready();});
beforeEach(()=>{vi.stubEnv('CLOUDFLARE_ACCOUNT_ID','0'.repeat(32));vi.stubEnv('CLOUDFLARE_API_TOKEN','test');
 vi.clearAllMocks();
 vi.stubEnv('NOVA_ART_FREE_PER_DAY','2');vi.stubEnv('NOVA_ART_PRICE_TOKENS','3');});
afterEach(()=>{vi.unstubAllEnvs();vi.mocked(deps.generate).mockImplementation(async()=>IMG);});
afterAll(async()=>{await db.user.deleteMany({where:{id:{in:created}}});await app.close();await db.$disconnect();});

describe('AI Art',()=>{
 it('gives the free allowance, then charges tokens, and reports what is left',async()=>{
  const u=await user();
  const s0=(await app.inject({url:'/v1/art/status',headers:auth(u)})).json();
  expect(s0).toMatchObject({configured:true,freePerDay:2,freeLeft:2,priceTokens:3});
  const first=await art(u);expect(first.statusCode,first.body).toBe(200);
  expect(first.json()).toMatchObject({image:IMG,mime:'image/jpeg',chargedTokens:0,freeLeft:1});
  expect((await art(u)).json().freeLeft).toBe(0);
  expect((await art(u)).json().error).toBe('INSUFFICIENT_TOKENS');
  await credit(db,{userId:u,bucket:'BONUS',amount:5,source:'test',idempotencyKey:'test:'+u});
  const paid=await art(u);expect(paid.json().chargedTokens).toBe(3);expect((await balance(db,u)).total).toBe(2);
 });
 it('lets parallel taps take only the free images',async()=>{
  const u=await user();
  const all=await Promise.all(Array.from({length:6},()=>art(u)));
  expect(all.filter(r=>r.statusCode===200).length).toBe(2);
  expect(all.filter(r=>r.json().error==='INSUFFICIENT_TOKENS').length).toBe(4);
 });
 it('deduplicates a free request before a replay can consume another image', async () => {
  const u = await user();
  const id = randomUUID();
  const results = await Promise.all([art(u, undefined, id), art(u, undefined, id)]);
  expect(results.map(r => r.statusCode).sort()).toEqual([200, 409]);
  const prepareCalls = vi.mocked(deps.prepare).mock.calls.length;
  const replay = await art(u, undefined, id);
  expect(replay.statusCode).toBe(409);
  expect(replay.json()).toEqual({error:'DUPLICATE_REQUEST'});
  expect(vi.mocked(deps.prepare).mock.calls).toHaveLength(prepareCalls);
  expect(deps.generate).toHaveBeenCalledTimes(1);
  expect(await db.aiUsage.count({where:{userId:u,feature:'image'}})).toBe(1);
 });
 it('reserves the final global image slot only once across different accounts', async () => {
  const today = new Date(rewardDay().nextAt.getTime() - 86400000);
  const prior = await db.aiUsage.count({where:{feature:'image',createdAt:{gte:today},
   OR:[{success:true},{errorCode:'PENDING'}]}});
  vi.stubEnv('NOVA_ART_DAILY_CAP', String(prior + 1));
  const users = await Promise.all(Array.from({length:8}, () => user()));
  const results = await Promise.all(users.map(u => art(u)));
  expect(results.filter(r => r.statusCode === 200)).toHaveLength(1);
  expect(results.filter(r => r.statusCode === 429 && r.json().error === 'ART_DAILY_LIMIT')).toHaveLength(7);
  expect(deps.generate).toHaveBeenCalledTimes(1);
 });
 it('scopes paid request IDs to one account and recognizes its legacy debit', async () => {
  vi.stubEnv('NOVA_ART_FREE_PER_DAY','0');
  const id = randomUUID();
  const [first, second] = await Promise.all([user(), user()]);
  for (const userId of [first, second]) {
   await credit(db,{userId,bucket:'BONUS',amount:6,source:'test',idempotencyKey:'test:'+userId});
  }
  await debit(db,{userId:first,amount:3,source:'ai_art',idempotencyKey:'art:'+id});
  expect((await art(first, undefined, id)).statusCode).toBe(409);
  expect((await art(second, undefined, id)).statusCode).toBe(200);
  expect((await art(second, undefined, id)).statusCode).toBe(409);
  expect(deps.generate).toHaveBeenCalledTimes(1);
  expect((await balance(db, first)).total).toBe(3);
  expect((await balance(db, second)).total).toBe(3);
 });
 it('refuses unsafe prompts before spending anything',async()=>{
  const u=await user();
  for(const p of ['голая девушка','sexy schoolgirl','A naked man'])expect((await art(u,p)).statusCode).toBe(422);
  expect((await art(u,'dragon-refuse over the city')).json().error).toBe('ART_PROMPT_REJECTED');
  expect((await app.inject({url:'/v1/art/status',headers:auth(u)})).json().freeLeft).toBe(2);
 });
 it('refuses to reserve an image if the account is blocked during prompt preparation', async () => {
  const u = await user();
  vi.mocked(deps.prepare).mockImplementationOnce(async () => {
   await db.user.update({where:{id:u},data:{status:'BLOCKED'}});
   return {allowed:true,prompt:'a calm lake at dawn'};
  });
  const result = await art(u);
  expect(result.statusCode).toBe(403);
  expect(result.json()).toEqual({error:'ACCOUNT_NOT_ACTIVE'});
  expect(deps.generate).not.toHaveBeenCalled();
  expect(await db.aiUsage.count({where:{userId:u}})).toBe(0);
 });
 it('refunds paid images when generation fails and does not use up the free allowance',async()=>{
  const u=await user();vi.stubEnv('NOVA_ART_FREE_PER_DAY','0');
  await credit(db,{userId:u,bucket:'BONUS',amount:3,source:'test',idempotencyKey:'test:'+u});
  (deps.generate as any).mockImplementation(async()=>{throw new Error('ART_PROVIDER_500')});
  const id = randomUUID();
  const r=await art(u,undefined,id);expect(r.statusCode).toBe(503);expect((await balance(db,u)).total).toBe(3);
  expect((await art(u,undefined,id)).statusCode).toBe(409);
  expect(deps.generate).toHaveBeenCalledTimes(1);
  expect(await db.walletTransaction.count({where:{userId:u,type:'CREDIT_REFUND'}})).toBe(1);
 });
 it('is off without Cloudflare keys and closed to anonymous or blocked users',async()=>{
  const blocked=await user({status:'BLOCKED'});
  expect((await art(blocked)).statusCode).toBe(403);
  expect((await app.inject({method:'POST',url:'/v1/art',payload:{prompt:'lake',requestId:randomUUID()}})).statusCode).toBe(401);
  vi.stubEnv('CLOUDFLARE_ACCOUNT_ID','');const u=await user();
  expect((await art(u)).json().error).toBe('ART_NOT_CONFIGURED');
 });
});
