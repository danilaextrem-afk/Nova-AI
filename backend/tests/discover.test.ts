import {beforeAll,afterAll,it,expect} from 'vitest';
import {PrismaClient} from '@prisma/client';
import {randomUUID} from 'node:crypto';
import {buildApp} from '../src/server.js';
const db=new PrismaClient();let app:Awaited<ReturnType<typeof buildApp>>;
const users:string[]=[];let author:string,reader:string,blockedReader:string;
const tag='t'+randomUUID().slice(0,8);const genre='g'+randomUUID().slice(0,8);
const headers=(id:string)=>({authorization:'Bearer '+app.jwt.sign({sub:id})});
const make=(name:string,data:any={})=>db.character.create({data:{ownerId:author,name,greeting:'Hello',category:genre,tags:[tag],biography:'<b>Bio</b> & "quotes"',
 publication:{create:{status:'PUBLISHED'}},...data},include:{publication:true}});
let pub:any,priv:any,pending:any,mature:any,liked:any;
beforeAll(async()=>{
 app=await buildApp(db);await app.ready();
 for(let i=0;i<3;i++)users.push((await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test',profile:{create:{displayName:'Автор '+i,bio:'О себе'}}}})).id);
 [author,reader,blockedReader]=users;
 pub=await make('Публичный <script>');liked=await make('Уже знакомый');
 priv=await make('Приватный',{publication:undefined});pending=await make('На модерации',{publication:{create:{status:'PENDING_REVIEW'}}});
 mature=await make('Взрослый',{mature:true});
 await db.favorite.create({data:{userId:reader,characterId:liked.id}});
 await db.userBlock.create({data:{blockerId:author,blockedId:blockedReader}});
 await db.chat.create({data:{userId:reader,characterId:pub.id}});await db.chat.create({data:{userId:author,characterId:pub.id}});
 await db.creatorFollow.create({data:{followerId:reader,creatorId:author}});
});
afterAll(async()=>{await db.user.deleteMany({where:{id:{in:users}}});await app.close()});
it('recommends only published characters, puts taste matches first and never repeats known ones',async()=>{
 const r=await app.inject({url:'/v1/discover/recommended?limit=40',headers:headers(reader)});expect(r.statusCode,r.body).toBe(200);
 const mine=r.json().items.filter((c:any)=>c.category===genre);
 expect(mine.map((c:any)=>c.name)).toEqual([]); // 'Публичный' is already in a chat, 'Уже знакомый' is a favourite.
 await db.chat.deleteMany({where:{userId:reader}});
 const again=(await app.inject({url:'/v1/discover/recommended?limit=40',headers:headers(reader)})).json();
 expect(again.personalised).toBe(true);expect(again.items[0]).toMatchObject({name:'Публичный <script>',reason:'TASTE'});
 const names=again.items.map((c:any)=>c.name);
 for(const hidden of ['Приватный','На модерации','Взрослый','Уже знакомый'])expect(names).not.toContain(hidden);
 expect((await app.inject({url:'/v1/discover/recommended',headers:headers(blockedReader)})).json().items.some((c:any)=>c.category===genre)).toBe(false);
 expect((await app.inject({url:'/v1/discover/recommended',headers:headers(author)})).json().items.some((c:any)=>c.category===genre)).toBe(false); // Not your own.
 expect((await app.inject({url:'/v1/discover/recommended'})).statusCode).toBe(401);
});
it('builds genre shelves from public characters only',async()=>{
 const shelves=(await app.inject({url:'/v1/discover/collections',headers:headers(reader)})).json().collections;
 expect(shelves.slice(0,2).map((s:any)=>s.key)).toEqual(['new','popular']);
 const all=shelves.flatMap((s:any)=>s.items.map((c:any)=>c.name));
 expect(all).toContain('Публичный <script>');for(const hidden of ['Приватный','На модерации','Взрослый'])expect(all).not.toContain(hidden);
});
it('shows a public author page without private work, and hides it between blocked users',async()=>{
 const r=await app.inject({url:'/v1/creators/'+author,headers:headers(reader)});expect(r.statusCode,r.body).toBe(200);
 expect(r.json()).toMatchObject({displayName:'Автор 0',followers:1,isFollowing:true});
 expect(r.json().characters.map((c:any)=>c.name).sort()).toEqual(['Публичный <script>','Уже знакомый']);
 expect(r.body).not.toContain('@test.invalid');
 expect((await app.inject({url:'/v1/creators/'+author,headers:headers(blockedReader)})).statusCode).toBe(404);
 expect((await app.inject({url:'/v1/creators/'+randomUUID(),headers:headers(reader)})).statusCode).toBe(404);
});
it('gives authors counts for their own characters and nothing about other authors',async()=>{
 await db.chat.create({data:{userId:reader,characterId:pub.id}});
 const s=(await app.inject({url:'/v1/me/creator-stats',headers:headers(author)})).json();
 expect(s.followers).toBe(1);expect(s.totals.characters).toBe(5);
 expect(s.items.find((i:any)=>i.id===pub.id)).toMatchObject({stories:1,status:'PUBLISHED'}); // The author's own chat is not counted.
 expect(s.items.find((i:any)=>i.id===liked.id).favorites).toBe(1);
 expect(s.items.find((i:any)=>i.id===priv.id).status).toBe('DRAFT');
 expect((await app.inject({url:'/v1/me/creator-stats',headers:headers(reader)})).json().items).toEqual([]);
});
it('serves an escaped public share page only for published all-ages characters',async()=>{
 const page=await app.inject({url:'/c/'+pub.id});expect(page.statusCode).toBe(200);expect(page.headers['content-type']).toContain('text/html');
 expect(page.body).toContain('Публичный &lt;script&gt;');expect(page.body).not.toContain('<script>');expect(page.body).toContain('&lt;b&gt;Bio&lt;/b&gt; &amp; &quot;quotes&quot;');
 for(const id of [priv.id,pending.id,mature.id,randomUUID(),'not-a-uuid'])expect((await app.inject({url:'/c/'+id})).statusCode).toBe(404);
});
