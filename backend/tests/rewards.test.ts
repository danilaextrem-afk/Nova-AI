import {beforeAll,afterAll,afterEach,describe,it,expect,vi} from 'vitest';
import {PrismaClient} from '@prisma/client';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import {randomUUID} from 'node:crypto';
import {registerRewardRoutes,settleReferral,rewardDay,REFERRAL_REQUIRED_TURNS} from '../src/rewardRoutes.js';
import {balance} from '../src/ledger.js';
const db=new PrismaClient();const app=Fastify();const created:string[]=[];
const user=async(data:any={})=>{const u=await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test',...data}});created.push(u.id);return u.id;};
const auth=(uid:string)=>({authorization:'Bearer '+app.jwt.sign({sub:uid})});
const succeed=async(uid:string,n:number)=>{const c=await db.character.create({data:{ownerId:uid,name:'Тест',greeting:'Привет',category:'x',tags:[]}});
 const chat=await db.chat.create({data:{userId:uid,characterId:c.id}});
 for(let i=0;i<n;i++)await db.chatRequest.create({data:{id:randomUUID(),userId:uid,chatId:chat.id,content:'m',fingerprint:randomUUID(),tier:'free',status:'SUCCEEDED'}});};
beforeAll(async()=>{await app.register(jwt,{secret:'integration-tests-only-not-for-production'});
 app.decorate('auth',async(req:any,reply:any)=>{try{await req.jwtVerify()}catch{return reply.code(401).send({error:'UNAUTHORIZED'})}});
 await registerRewardRoutes(app,db);await app.ready();});
afterEach(()=>vi.unstubAllEnvs());
afterAll(async()=>{await db.setting.deleteMany({where:{key:{in:['dailyBonusTokens','referralBonusTokens']}}});
 await db.user.deleteMany({where:{id:{in:created}}});await app.close();await db.$disconnect();});

describe('Daily bonus',()=>{
 it('pays once per Moscow calendar day even under concurrent taps',async()=>{
  const u=await user();
  const all=await Promise.all(Array.from({length:8},()=>app.inject({method:'POST',url:'/v1/rewards/daily',headers:auth(u)})));
  expect(all.every(r=>r.statusCode===200)).toBe(true);
  expect(all.filter(r=>r.json().granted).length).toBe(1);
  expect((await balance(db,u)).total).toBe(2);
  const status=await app.inject({url:'/v1/rewards/daily',headers:auth(u)});expect(status.json().claimedToday).toBe(true);
 });
 it('uses the configured time zone for the day boundary and can be disabled by the owner',async()=>{
  const at=Date.UTC(2026,10,11,21,30);// 00:30 on Nov 12 in Moscow
  expect(rewardDay(at).day).toBe('2026-11-12');expect(rewardDay(at).nextAt.toISOString()).toBe('2026-11-12T21:00:00.000Z');
  await db.setting.upsert({where:{key:'dailyBonusTokens'},create:{key:'dailyBonusTokens',value:0},update:{value:0}});
  const u=await user();const r=await app.inject({method:'POST',url:'/v1/rewards/daily',headers:auth(u)});
  expect(r.statusCode).toBe(403);expect((await balance(db,u)).total).toBe(0);
  await db.setting.delete({where:{key:'dailyBonusTokens'}});
 });
 it('refuses blocked accounts and anonymous calls',async()=>{
  const u=await user({status:'BLOCKED'});
  expect((await app.inject({method:'POST',url:'/v1/rewards/daily',headers:auth(u)})).statusCode).toBe(403);
  expect((await app.inject({method:'POST',url:'/v1/rewards/daily'})).statusCode).toBe(401);
 });
});

describe('Referrals',()=>{
 const codeOf=async(uid:string)=>(await app.inject({url:'/v1/referrals',headers:auth(uid)})).json().code as string;
 const apply=(uid:string,code:string)=>app.inject({method:'POST',url:'/v1/referrals/apply',headers:auth(uid),payload:{code}});
 it('pays both sides once, only after the invitee really chatted',async()=>{
  const inviter=await user();const invitee=await user();const code=await codeOf(inviter);
  expect(code).toMatch(/^[A-HJ-NP-Z2-9]{8}$/);expect(await codeOf(inviter)).toBe(code);
  const r=await apply(invitee,code.toLowerCase());expect(r.statusCode,r.body).toBe(200);expect(r.json().rewarded).toBe(false);
  expect((await balance(db,inviter)).total).toBe(0);
  await succeed(invitee,REFERRAL_REQUIRED_TURNS-1);expect(await settleReferral(db,invitee)).toBe(false);
  await succeed(invitee,1);
  const results=await Promise.all(Array.from({length:6},()=>settleReferral(db,invitee)));
  expect(results.filter(Boolean).length).toBe(1);
  expect((await balance(db,inviter)).total).toBe(20);expect((await balance(db,invitee)).total).toBe(20);
  expect(await settleReferral(db,invitee)).toBe(false);
  const stats=(await app.inject({url:'/v1/referrals',headers:auth(inviter)})).json();expect(stats.invited).toBe(1);expect(stats.rewarded).toBe(1);
  expect((await apply(invitee,code)).json().error).toBe('ALREADY_APPLIED');
 });
 it('rejects own code, unknown code, circular invites and old accounts',async()=>{
  const a=await user();const b=await user();
  expect((await apply(a,await codeOf(a))).json().error).toBe('OWN_CODE');
  expect((await apply(a,'ZZZZZZZZ')).statusCode).toBe(404);
  expect((await apply(a,await codeOf(b))).statusCode).toBe(200);
  expect((await apply(b,await codeOf(a))).json().error).toBe('CIRCULAR_REFERRAL');
  const old=await user({createdAt:new Date(Date.now()-8*86400000)});
  expect((await apply(old,await codeOf(a))).json().error).toBe('APPLY_WINDOW_CLOSED');
  expect((await app.inject({method:'POST',url:'/v1/referrals/apply',headers:auth(a),payload:{code:'bad!'}})).statusCode).toBe(400);
 });
});
