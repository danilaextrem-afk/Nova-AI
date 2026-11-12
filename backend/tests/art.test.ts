import {beforeAll,afterAll,afterEach,beforeEach,describe,it,expect,vi} from 'vitest';
import {PrismaClient} from '@prisma/client';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import {randomUUID} from 'node:crypto';
import {registerArtRoutes,type ArtDeps} from '../src/artRoutes.js';
import {balance,credit} from '../src/ledger.js';
const db=new PrismaClient();const app=Fastify();const created:string[]=[];
const IMG='A'.repeat(2000);
const deps:ArtDeps={prepare:vi.fn(async(t:string)=>({allowed:!/dragon-refuse/.test(t),prompt:'a calm lake at dawn'})),generate:vi.fn(async()=>IMG)};
const user=async(data:any={})=>{const u=await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test',...data}});created.push(u.id);return u.id;};
const auth=(uid:string)=>({authorization:'Bearer '+app.jwt.sign({sub:uid})});
const art=(uid:string,prompt='Озеро на рассвете')=>app.inject({method:'POST',url:'/v1/art',headers:auth(uid),payload:{prompt,style:'fantasy',requestId:randomUUID()}});
beforeAll(async()=>{await app.register(jwt,{secret:'integration-tests-only-not-for-production'});
 app.decorate('auth',async(req:any,reply:any)=>{try{await req.jwtVerify()}catch{return reply.code(401).send({error:'UNAUTHORIZED'})}});
 await registerArtRoutes(app,db,deps);await app.ready();});
beforeEach(()=>{vi.stubEnv('CLOUDFLARE_ACCOUNT_ID','0'.repeat(32));vi.stubEnv('CLOUDFLARE_API_TOKEN','test');
 vi.stubEnv('NOVA_ART_FREE_PER_DAY','2');vi.stubEnv('NOVA_ART_PRICE_TOKENS','3');});
afterEach(()=>{vi.unstubAllEnvs();(deps.generate as any).mockImplementation(async()=>IMG);});
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
 it('refuses unsafe prompts before spending anything',async()=>{
  const u=await user();
  for(const p of ['голая девушка','sexy schoolgirl','A naked man'])expect((await art(u,p)).statusCode).toBe(422);
  expect((await art(u,'dragon-refuse over the city')).json().error).toBe('ART_PROMPT_REJECTED');
  expect((await app.inject({url:'/v1/art/status',headers:auth(u)})).json().freeLeft).toBe(2);
 });
 it('refunds paid images when generation fails and does not use up the free allowance',async()=>{
  const u=await user();vi.stubEnv('NOVA_ART_FREE_PER_DAY','0');
  await credit(db,{userId:u,bucket:'BONUS',amount:3,source:'test',idempotencyKey:'test:'+u});
  (deps.generate as any).mockImplementation(async()=>{throw new Error('ART_PROVIDER_500')});
  const r=await art(u);expect(r.statusCode).toBe(503);expect((await balance(db,u)).total).toBe(3);
 });
 it('is off without Cloudflare keys and closed to anonymous or blocked users',async()=>{
  const blocked=await user({status:'BLOCKED'});
  expect((await art(blocked)).statusCode).toBe(403);
  expect((await app.inject({method:'POST',url:'/v1/art',payload:{prompt:'lake',requestId:randomUUID()}})).statusCode).toBe(401);
  vi.stubEnv('CLOUDFLARE_ACCOUNT_ID','');const u=await user();
  expect((await art(u)).json().error).toBe('ART_NOT_CONFIGURED');
 });
});
