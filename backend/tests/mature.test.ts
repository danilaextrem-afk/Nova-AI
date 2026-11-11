import {beforeAll,afterAll,afterEach,describe,it,expect,vi} from 'vitest';
import {PrismaClient} from '@prisma/client';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import {randomUUID} from 'node:crypto';
import {registerCharacterRoutes} from '../src/characters.js';
import {registerChatRoutes} from '../src/chatRoutes.js';
import {registerProductRoutes} from '../src/productRoutes.js';
const db=new PrismaClient();const app=Fastify();const created:string[]=[];
let author:string,viewer:string,matureId:string,generalId:string;
const user=async()=>{const u=await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'t',profile:{create:{displayName:'Тест'}}}});created.push(u.id);return u.id;};
const auth=(uid:string)=>({authorization:'Bearer '+app.jwt.sign({sub:uid})});
const published=async(name:string,mature:boolean)=>(await db.character.create({data:{ownerId:author,name,greeting:'Привет',category:'Романтика',tags:[],mature,
 publication:{create:{status:'PUBLISHED',publishedAt:new Date()}}}})).id;
const catalog=(uid:string,section?:string)=>app.inject({url:'/v1/characters/catalog'+(section?'?section='+section:''),headers:auth(uid)});
beforeAll(async()=>{await app.register(jwt,{secret:'integration-tests-only-not-for-production'});
 app.decorate('auth',async(req:any,reply:any)=>{try{await req.jwtVerify()}catch{return reply.code(401).send({error:'UNAUTHORIZED'})}});
 await registerCharacterRoutes(app,db);await registerChatRoutes(app,db);await registerProductRoutes(app,db);await app.ready();
 author=await user();viewer=await user();matureId=await published('Взрослая драма '+randomUUID(),true);generalId=await published('Обычный герой '+randomUUID(),false);});
afterEach(()=>{vi.unstubAllGlobals();vi.unstubAllEnvs()});
afterAll(async()=>{await db.user.deleteMany({where:{id:{in:created}}});await app.close();await db.$disconnect();});

describe('Mature (18+) section',()=>{
 it('is hidden by default everywhere: catalog, detail, new chats',async()=>{
  const ids=(await catalog(viewer)).json().items.map((c:any)=>c.id);
  expect(ids).toContain(generalId);expect(ids).not.toContain(matureId);
  expect((await catalog(viewer,'mature')).statusCode).toBe(403);
  expect((await app.inject({url:'/v1/characters/'+matureId,headers:auth(viewer)})).statusCode).toBe(404);
  expect((await app.inject({method:'POST',url:'/v1/chats',headers:auth(viewer),payload:{characterId:matureId}})).statusCode).toBe(404);
 });
 it('needs an explicit 18+ confirmation to opt in, then lists 18+ separately with a label',async()=>{
  expect((await app.inject({method:'PUT',url:'/v1/me/content-settings',headers:auth(viewer),payload:{showMature:true}})).statusCode).toBe(400);
  const on=await app.inject({method:'PUT',url:'/v1/me/content-settings',headers:auth(viewer),payload:{showMature:true,confirmAdult:true}});
  expect(on.json().showMature).toBe(true);
  expect((await db.profile.findUnique({where:{userId:viewer}}))!.matureConfirmedAt).not.toBeNull();
  const general=(await catalog(viewer)).json().items;expect(general.some((c:any)=>c.mature)).toBe(false);
  const mature=(await catalog(viewer,'mature')).json().items;
  expect(mature.map((c:any)=>c.id)).toContain(matureId);expect(mature.every((c:any)=>c.mature===true)).toBe(true);
  expect((await app.inject({url:'/v1/characters/'+matureId,headers:auth(viewer)})).json().character.mature).toBe(true);
 });
 it('keeps explicit content out of the model instructions and stops chats when the user opts out',async()=>{
  vi.stubEnv('GROQ_API_KEY','fixture-not-a-key');vi.stubEnv('OPENROUTER_API_KEY','');
  vi.stubEnv('NOVA_AI_MODELS_JSON',JSON.stringify([{provider:'groq',tier:'free',model:'test-free',approved:true,rpm:1000,rpd:1000,tpm:10000000,tpd:100000000,inputUsdPerMillion:0,outputUsdPerMillion:0,dailyBudgetMicros:0}]));
  await db.aiProviderState.deleteMany();
  const chat=(await app.inject({method:'POST',url:'/v1/chats',headers:auth(viewer),payload:{characterId:matureId}})).json().chat;
  const mock=vi.fn(async(_u:any,_i:any)=>new Response(JSON.stringify({choices:[{message:{content:'Ответ'}}],usage:{prompt_tokens:1,completion_tokens:1}}),{status:200,headers:{'content-type':'application/json'}}));
  vi.stubGlobal('fetch',mock);
  const r=await app.inject({method:'POST',url:`/v1/chats/${chat.id}/messages`,headers:auth(viewer),payload:{content:'Привет',requestId:randomUUID()}});
  expect(r.statusCode,r.body).toBe(200);
  const system=JSON.parse(mock.mock.calls[0][1].body).messages[0].content as string;
  expect(system).toContain('never sexually explicit');expect(system).toContain('Never involve minors');
  await app.inject({method:'PUT',url:'/v1/me/content-settings',headers:auth(viewer),payload:{showMature:false}});
  const blocked=await app.inject({method:'POST',url:`/v1/chats/${chat.id}/messages`,headers:auth(viewer),payload:{content:'Ещё',requestId:randomUUID()}});
  expect(blocked.statusCode).toBe(403);expect(mock).toHaveBeenCalledTimes(1);
  expect((await catalog(viewer,'mature')).statusCode).toBe(403);
 });
});
