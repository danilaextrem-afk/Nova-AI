import {beforeAll,afterAll,it,expect} from 'vitest';
import {PrismaClient} from '@prisma/client';
import {randomUUID} from 'node:crypto';
import {buildApp} from '../src/server.js';
const db=new PrismaClient();let app:Awaited<ReturnType<typeof buildApp>>;
let owner:string,reader:string,other:string,character:string,publication:string,comment:string;
const users:string[]=[];
const headers=(id:string)=>({authorization:'Bearer '+app.jwt.sign({sub:id})});
beforeAll(async()=>{
 app=await buildApp(db);await app.ready();
 for(let i=0;i<3;i++)users.push((await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test',profile:{create:{displayName:'Fiction author'}}}})).id);
 [owner,reader,other]=users;
 const c=await db.character.create({data:{ownerId:owner,name:'Community fixture',greeting:'Hello',category:'fiction',tags:[],publication:{create:{status:'PUBLISHED'}}},include:{publication:true}});
 character=c.id;publication=c.publication!.id;
});
afterAll(async()=>{await db.user.deleteMany({where:{id:{in:users}}});await app.close()});
it('keeps pending comments private, and prevents deletion by another user',async()=>{
 const r=await app.inject({method:'POST',url:`/v1/publications/${publication}/comments`,headers:headers(reader),payload:{content:'A comment awaiting moderation'}});
 expect(r.statusCode,r.body).toBe(200);comment=r.json().comment.id;expect(r.json().comment.status).toBe('PENDING_REVIEW');
 const read=async(id:string)=>(await app.inject({url:`/v1/publications/${publication}/comments`,headers:headers(id)})).json().items;
 expect(await read(owner)).toHaveLength(0);expect(await read(reader)).toHaveLength(1);
 await app.inject({method:'DELETE',url:`/v1/comments/${comment}`,headers:headers(other)});
 expect(await db.socialComment.findUnique({where:{id:comment}})).not.toBeNull();
 await db.socialComment.update({where:{id:comment},data:{status:'PUBLISHED'}});expect(await read(owner)).toHaveLength(1);
});
it('does not let ordinary users access administrator comment moderation or pricing',async()=>{
 for(const url of [`/v1/admin/comments/${comment}`,'/v1/admin/premium-price']){
  const r=await app.inject({method:'PUT',url,headers:headers(reader),payload:{status:'PUBLISHED',tokens:1}});expect(r.statusCode).toBe(401);
 }
});
it('follows once, shows only approved publications, and removes the subscription',async()=>{
 for(let i=0;i<2;i++)expect((await app.inject({method:'PUT',url:`/v1/creators/${owner}/follow`,headers:headers(reader)})).statusCode).toBe(200);
 expect(await db.creatorFollow.count({where:{followerId:reader,creatorId:owner}})).toBe(1);
 const feed=async()=>(await app.inject({url:'/v1/community/following',headers:headers(reader)})).json().items;
 expect((await feed()).map((x:any)=>x.id)).toContain(character);
 await db.publication.update({where:{id:publication},data:{status:'PENDING_REVIEW'}});expect(await feed()).toHaveLength(0);
 await db.publication.update({where:{id:publication},data:{status:'PUBLISHED'}});
 await app.inject({method:'DELETE',url:`/v1/creators/${owner}/follow`,headers:headers(reader)});expect(await feed()).toHaveLength(0);
});
it('blocks social and new-chat access in both directions',async()=>{
 await app.inject({method:'PUT',url:`/v1/blocks/${owner}`,headers:headers(reader)});
 expect((await app.inject({url:`/v1/publications/${publication}/comments`,headers:headers(reader)})).statusCode).toBe(404);
 expect((await app.inject({method:'PUT',url:`/v1/publications/${publication}/like`,headers:headers(reader)})).statusCode).toBe(404);
 for(const [a,b] of [[reader,owner],[owner,reader]])expect((await app.inject({method:'PUT',url:`/v1/creators/${b}/follow`,headers:headers(a)})).statusCode).toBe(400);
 expect((await app.inject({method:'POST',url:'/v1/chats',headers:headers(reader),payload:{characterId:character}})).statusCode).toBe(404);
 const catalog=await app.inject({url:'/v1/characters/catalog',headers:headers(reader)});expect(catalog.json().items.some((x:any)=>x.id===character)).toBe(false);
 await db.userBlock.deleteMany({where:{blockerId:reader}});
});
it('counts concurrent duplicate likes once and updates popular discovery',async()=>{
 const results=await Promise.all(Array.from({length:8},()=>app.inject({method:'PUT',url:`/v1/publications/${publication}/like`,headers:headers(reader)})));
 expect(results.every(x=>x.statusCode===200)).toBe(true);expect((await db.publication.findUnique({where:{id:publication}}))!.likesCount).toBe(1);
 const r=await app.inject({url:'/v1/characters/catalog?sort=popular',headers:headers(reader)});expect(r.statusCode,r.body).toBe(200);
 expect(r.json().items.find((x:any)=>x.id===character).publication.likesCount).toBe(1);
});
it('exports and deletes social personal data, leaving no usable session',async()=>{
 await app.inject({method:'PUT',url:`/v1/creators/${owner}/follow`,headers:headers(reader)});
 const r=await app.inject({url:'/v1/me/export',headers:headers(reader)});expect(r.json().comments.map((x:any)=>x.id)).toContain(comment);
 expect((await app.inject({method:'DELETE',url:'/v1/me',headers:headers(reader),payload:{confirmation:'DELETE'}})).statusCode).toBe(204);
 expect(await db.socialComment.count({where:{userId:reader}})).toBe(0);expect(await db.creatorFollow.count({where:{followerId:reader}})).toBe(0);
 expect((await db.publication.findUnique({where:{id:publication}}))!.likesCount).toBe(0);
 expect((await app.inject({url:'/v1/me',headers:headers(reader)})).statusCode).toBe(401);
});
