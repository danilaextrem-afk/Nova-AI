import {beforeAll,afterAll,beforeEach,afterEach,describe,it,expect,vi} from 'vitest';
import {PrismaClient} from '@prisma/client';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import {randomUUID} from 'node:crypto';
import {registerChatRoutes} from '../src/chatRoutes.js';
import {registerStoryRoutes} from '../src/storyRoutes.js';
import {extractMemories,pickSpeakers,stripSpeakerPrefix,keepOwnVoice,parseExtraction,EXTRACT_EVERY_MESSAGES} from '../src/memoryEngine.js';
const db=new PrismaClient();const app=Fastify();const users:string[]=[];
const user=async()=>{const u=await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test'}});users.push(u.id);return u.id;};
const auth=(uid:string)=>({authorization:'Bearer '+app.jwt.sign({sub:uid})});
const character=(ownerId:string,name:string,extra:any={})=>db.character.create({data:{ownerId,name,greeting:'Привет',category:'x',tags:[],personality:name+' personality',...extra}});
/** Fake provider: answers with the name of the character the system prompt says it must play. */
const provider=(reply:(system:string,body:any)=>string)=>{const calls:any[]=[];
 vi.stubGlobal('fetch',vi.fn(async(_u:string,init:any)=>{const body=JSON.parse(init.body);calls.push(body);
  return {ok:true,status:200,json:async()=>({choices:[{message:{content:reply(body.messages[0].content,body)}}],usage:{prompt_tokens:10,completion_tokens:5}})};}));return calls;};
const env={...process.env};
beforeAll(async()=>{await app.register(jwt,{secret:'integration-tests-only-not-for-production'});
 app.decorate('auth',async(req:any,reply:any)=>{try{await req.jwtVerify()}catch{return reply.code(401).send({error:'UNAUTHORIZED'})}});
 await registerChatRoutes(app,db);await registerStoryRoutes(app,db);await app.ready();});
beforeEach(async()=>{await db.aiProviderState.deleteMany();process.env.AI_PROVIDER='groq';process.env.GROQ_API_KEY='test-key';process.env.AI_MODEL='openai/gpt-oss-20b';
 process.env.NOVA_AI_DAILY_BUDGET_MICROS='100000';});
afterEach(()=>{vi.unstubAllGlobals();});
afterAll(async()=>{for(const k of ['AI_PROVIDER','GROQ_API_KEY','AI_MODEL','NOVA_AI_DAILY_BUDGET_MICROS'])if(env[k]===undefined)delete process.env[k];else process.env[k]=env[k];
 await db.user.deleteMany({where:{id:{in:users}}});await app.close();await db.$disconnect();});

describe('Group chat orchestration',()=>{
 it('picks addressed characters first, then whoever was silent longest',()=>{
  const cast=['Аврора Вейл','Кай Номад','Лея Винтер'].map((name,i)=>({id:'c'+i,name}));
  expect(pickSpeakers(cast,'Привет всем',[],2).map(c=>c.id)).toEqual(['c0','c1']);
  expect(pickSpeakers(cast,'Привет всем',['c1','c0'],2).map(c=>c.id)).toEqual(['c2','c0']);
  expect(pickSpeakers(cast,'лея, что думаешь?',['c2','c1','c0'],2).map(c=>c.id)).toEqual(['c2','c0']);
  expect(pickSpeakers(cast,'x',[],5).length).toBe(3);
  expect(stripSpeakerPrefix('**Кай Номад:** Пойдём.','Кай Номад')).toBe('Пойдём.');
  expect(stripSpeakerPrefix('Кай улыбнулся.','Кай Номад')).toBe('Кай улыбнулся.');
 });
 // Regression: live run on 886ad2c — the first speaker also introduced the second one with a made-up job.
 it('cuts a reply where the model starts speaking for another character, but keeps narration and address',()=>{
  const others=['Илья Невский'];
  expect(keepOwnVoice('Меня зовут Вера Снегирёва, я реставратор карт. Илья Невский, я – старший архивист.',others)).toBe('Меня зовут Вера Снегирёва, я реставратор карт.');
  expect(keepOwnVoice('Я Вера. Илья: Привет всем!',others)).toBe('Я Вера.');
  expect(keepOwnVoice('Я Вера.\nМеня зовут Илья Невский, смотрю за маяком.',others)).toBe('Я Вера.');
  expect(keepOwnVoice('Я Вера. Я — Илья, смотритель.',others)).toBe('Я Вера.');
  for(const keep of ['Илья Невский — смотритель маяка, он мой друг.','Я реставратор. Илья, я думаю, нам пора.','Вера смотрит на Илью. Илья молчит.'])
   expect(keepOwnVoice(keep,others)).toBe(keep);
 });
 it('gives every speaker its own model call, sheet and saved reply, and rotates the turn order',async()=>{
  const u=await user();const [a,b,c]=await Promise.all(['Аврора','Борис','Вера'].map(n=>character(u,n)));
  const created=await app.inject({method:'POST',url:'/v1/chats',headers:auth(u),payload:{characterId:a.id,participantIds:[b.id,c.id]}});
  const chatId=created.json().chat.id;
  // The first speaker also tries to talk for the second one; only its own line may be saved.
  const calls=provider((_system, body) => { const name = JSON.parse(body.messages[1].content).activeCharacter.name; return name + ': реплика от ' + name + (name === 'Аврора' ? '. Борис: а я вообще пилот.' : ''); });
  const send=(content:string,requestId=randomUUID())=>app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(u),payload:{content,requestId}});
  const rid=randomUUID();const first=await send('Привет всем',rid);expect(first.statusCode,first.body).toBe(200);
  expect(calls.length).toBe(2); // Free tier: two speakers per turn, not the whole cast.
  expect(first.json().assistantMessage).toMatchObject({speakerName:'Аврора',content:'реплика от Аврора.'});
  expect(first.json().extraMessages.map((m:any)=>m.speakerName)).toEqual(['Борис']);
  // The second speaker saw the first one's line and only its own character sheet.
  const context = JSON.parse(calls[1].messages[1].content);
  expect(context.earlierReplies).toEqual([{ name: 'Аврора', text: 'реплика от Аврора.' }]); // The impersonated 'Борис: …' line is cut before Борис sees the turn.
  expect(context.activeCharacter.name).toBe('Борис');
  expect(calls[1].messages[0].content).not.toContain('Аврора');
  // Replay of the same request returns the same turn and calls nothing.
  const replay=await send('Привет всем',rid);expect(replay.json().duplicate).toBe(true);expect(replay.json().extraMessages.length).toBe(1);expect(calls.length).toBe(2);
  const second=await send('И что дальше?');
  expect([second.json().assistantMessage.speakerName,...second.json().extraMessages.map((m:any)=>m.speakerName)]).toEqual(['Вера','Аврора']);
  const list=(await app.inject({url:`/v1/chats/${chatId}/messages`,headers:auth(u)})).json().items;
  expect(list.map((m:any)=>m.speakerName??'player')).toEqual(['player','Аврора','Борис','player','Вера','Аврора']);
  // Regenerating the last reply keeps its speaker.
  const regen=await app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(u),payload:{requestId:randomUUID(),regenerateMessageId:list.at(-1).id}});
  expect(regen.statusCode,regen.body).toBe(200);expect(regen.json().assistantMessage.speakerName).toBe('Аврора');
  // Deleting the player's message removes the whole turn it produced.
  const del=await app.inject({method:'DELETE',url:`/v1/chats/${chatId}/messages/${list[0].id}`,headers:auth(u)});expect(del.json().deleted).toBe(3);
 });
 it('keeps the first reply when a later speaker fails, and changes the cast only with visible characters',async()=>{
  const u=await user();const other=await user();const [a,b]=await Promise.all([character(u,'Аврора'),character(u,'Борис')]);
  const secret=await character(other,'Чужой');
  const chatId=(await app.inject({method:'POST',url:'/v1/chats',headers:auth(u),payload:{characterId:a.id,participantIds:[b.id]}})).json().chat.id;
  let n=0;vi.stubGlobal('fetch',vi.fn(async()=>(++n===1?{ok:true,status:200,json:async()=>({choices:[{message:{content:'Первая реплика'}}],usage:{}})}:{ok:false,status:500})));
  const r=await app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(u),payload:{content:'Привет',requestId:randomUUID()}});
  expect(r.statusCode,r.body).toBe(200);expect(r.json().extraMessages).toEqual([]);
  const patch=(participantIds:string[],who=u)=>app.inject({method:'PATCH',url:`/v1/chats/${chatId}`,headers:auth(who),payload:{participantIds}});
  expect((await patch([secret.id])).statusCode).toBe(404);
  expect((await patch([],other)).statusCode).toBe(404);
  expect((await patch([])).statusCode).toBe(200);
  expect((await db.chat.findUnique({where:{id:chatId}}))!.participantIds).toEqual([]);
 });
});

describe('Story memory',()=>{
 it('parses only well-formed extraction output',()=>{
  expect(parseExtraction('ok {"add":[{"kind":"EVENT","content":"Нашли карту"}]} done')!.add.length).toBe(1);
  expect(parseExtraction('{"add":[{"kind":"SECRET","content":"x"}]}')).toBeNull();expect(parseExtraction('no json')).toBeNull();
 });
 it('extracts events once per range, corrects its own contradicted notes and never touches the user notes',async()=>{
  const u=await user();const c=await character(u,'Аврора');
  const chat=await db.chat.create({data:{userId:u,characterId:c.id}});
  const mine=await db.memory.create({data:{chatId:chat.id,kind:'FACT',content:'Игрок — капитан корабля'}});
  const auto=await db.memory.create({data:{chatId:chat.id,kind:'FACT',content:'Корабль стоит в порту',source:'auto'}});
  for(let i=0;i<EXTRACT_EVERY_MESSAGES-1;i++)await db.message.create({data:{chatId:chat.id,role:i%2?'assistant':'user',content:'Реплика '+i}});
  const calls=provider(()=>JSON.stringify({add:[{kind:'EVENT',content:'Корабль вышел в море'},{kind:'RELATION',content:'Аврора доверяет капитану'},{kind:'FACT',content:'корабль вышел в море'}],
   replace:[{n:1,content:'Игрок — пират'},{n:2,content:'Корабль в открытом море'}],character:['Игрок любит шторма']}));
  expect(await extractMemories(db,chat.id,u)).toBe(0);expect(calls.length).toBe(0); // Too few new messages: no AI call.
  await db.message.create({data:{chatId:chat.id,role:'assistant',content:'Мы отплываем!'}});
  const both=await Promise.all([extractMemories(db,chat.id,u),extractMemories(db,chat.id,u)]);
  expect(calls.length).toBe(1);expect(both.filter(Boolean).length).toBe(1);
  expect(calls[0].messages[1].content).toContain('Мы отплываем!');
  const notes=await db.memory.findMany({where:{chatId:chat.id},orderBy:{createdAt:'asc'}});
  expect(notes.find(m=>m.id===mine.id)!.content).toBe('Игрок — капитан корабля'); // User note is never rewritten.
  expect(notes.find(m=>m.id===auto.id)!.content).toBe('Корабль в открытом море'); // Contradicted auto note corrected.
  expect(notes.filter(m=>m.source==='auto').map(m=>m.kind).sort()).toEqual(['EVENT','FACT','RELATION']);
  expect((await db.characterMemory.findMany({where:{userId:u,characterId:c.id}})).map(m=>m.content)).toEqual(['Игрок любит шторма']);
  expect(await extractMemories(db,chat.id,u)).toBe(0);expect(calls.length).toBe(1);
 });
 it('persists the rolling summary and supplies it in a subsequent request', async () => {
  const uid = await user();
  const lead = await character(uid, 'Аврора');
  const thread = await db.chat.create({ data: { userId: uid, characterId: lead.id } });
  await db.message.createMany({ data: Array.from({ length: EXTRACT_EVERY_MESSAGES }, (_, index) => ({
   chatId: thread.id, role: index % 2 ? 'assistant' : 'user', content: 'Улика ' + index,
  })) });
  provider(() => JSON.stringify({ summary: 'Аврора нашла медный ключ в вазе.' }));
  await extractMemories(db, thread.id, uid);
  const stored = await db.chat.findUniqueOrThrow({ where: { id: thread.id } });
  expect(stored.summaryThrough).toBeGreaterThan(0);
  expect(stored.summaryThrough).toBe(stored.memoryThrough);
  expect(await db.memory.findFirst({ where: { chatId: thread.id, kind: 'SUMMARY' } }))
   .toMatchObject({ source: 'auto', content: 'Аврора нашла медный ключ в вазе.' });

  const calls = provider(() => 'Ключ был в вазе.');
  const response = await app.inject({
   method: 'POST', url: `/v1/chats/${thread.id}/messages`, headers: auth(uid),
   payload: { requestId: randomUUID(), content: 'Где был ключ?' },
  });
  expect(response.statusCode, response.body).toBe(200);
  expect(JSON.parse(calls[0].messages[1].content).storySoFar).toBe('Аврора нашла медный ключ в вазе.');
 });
 it('uses character memory in later chats of the same user only, and lets the user edit and delete it',async()=>{
  const u=await user();const stranger=await user();const c=await character(u,'Аврора');
  await db.character.update({where:{id:c.id},data:{publication:{create:{status:'PUBLISHED'}}}}).catch(()=>{});
  const mem=(who:string,method='GET',path='',payload?:any)=>app.inject({method:method as any,url:`/v1/characters/${c.id}/memories${path}`,headers:auth(who),payload});
  const made=await mem(u,'POST','',{content:'Игрок боится высоты'});expect(made.statusCode,made.body).toBe(201);
  const id=made.json().memory.id;
  expect((await mem(stranger)).json().items??[]).toEqual([]);
  expect((await mem(stranger,'PATCH','/'+id,{content:'взлом'})).statusCode).toBe(404);
  expect((await mem(stranger,'DELETE','/'+id)).statusCode).toBe(404);
  const calls=provider(()=>'Ответ');
  const chatId=(await app.inject({method:'POST',url:'/v1/chats',headers:auth(u),payload:{characterId:c.id}})).json().chat.id;
  await app.inject({method:'POST',url:`/v1/chats/${chatId}/messages`,headers:auth(u),payload:{content:'Пойдём на крышу',requestId:randomUUID()}});
  expect(calls[0].messages[1].content).toContain('Игрок боится высоты');
  expect((await mem(u,'PATCH','/'+id,{content:'Игрок больше не боится высоты',pinned:true})).statusCode).toBe(200);
  expect((await mem(u)).json().items[0]).toMatchObject({content:'Игрок больше не боится высоты',pinned:true});
  expect((await mem(u,'DELETE','/'+id)).statusCode).toBe(204);expect((await mem(u)).json().items).toEqual([]);
 });
 it('lets only the author define scenarios and starts a chat inside the chosen one',async()=>{
  const u=await user();const other=await user();const c=await character(u,'Аврора');
  const url=`/v1/characters/${c.id}/scenarios`;
  expect((await app.inject({method:'POST',url,headers:auth(other),payload:{title:'x',scenario:'y'}})).statusCode).toBe(404); // Private character: invisible.
  const made=await app.inject({method:'POST',url,headers:auth(u),payload:{title:'Космос',scenario:'Орбитальная станция, 2300 год.',greeting:'Шлюз открыт.'}});
  expect(made.statusCode,made.body).toBe(201);const sid=made.json().scenario.id;
  const chat=await app.inject({method:'POST',url:'/v1/chats',headers:auth(u),payload:{characterId:c.id,scenarioId:sid}});
  expect(chat.json().chat).toMatchObject({scenario:'Орбитальная станция, 2300 год.',title:'Аврора · Космос'});expect(chat.json().greeting).toBe('Шлюз открыт.');
  expect((await app.inject({method:'POST',url:'/v1/chats',headers:auth(u),payload:{characterId:c.id,scenarioId:randomUUID()}})).statusCode).toBe(404);
  expect((await app.inject({method:'DELETE',url:url+'/'+sid,headers:auth(other)})).statusCode).toBe(404);
  expect((await app.inject({method:'DELETE',url:url+'/'+sid,headers:auth(u)})).statusCode).toBe(204);
 });
});
