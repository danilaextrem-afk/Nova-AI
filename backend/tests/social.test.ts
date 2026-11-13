import {beforeAll,afterAll,it,expect,vi} from 'vitest';
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
it('links moderated replies to a visible root comment in the same publication', async () => {
 const url = `/v1/publications/${publication}/comments`;
 const send = (parentId: string) => app.inject({method: 'POST', url, headers: headers(other),
  payload: {content: 'Ответ на опубликованный комментарий', parentId}});
 expect((await send(randomUUID())).statusCode).toBe(404);
 const pending = await db.socialComment.create({data: {userId: reader, publicationId: publication, content: 'Private pending'}});
 expect((await send(pending.id)).statusCode).toBe(404);
 const another = await db.character.create({data: {ownerId: owner, name: 'Another discussion', greeting: 'Hello',
  category: 'fiction', tags: [], publication: {create: {status: 'PUBLISHED'}}}, include: {publication: true}});
 try {
  const foreign = await db.socialComment.create({data: {userId: reader, publicationId: another.publication!.id,
   content: 'Different discussion', status: 'PUBLISHED'}});
  expect((await send(foreign.id)).statusCode).toBe(404);
  const response = await send(comment);
  expect(response.statusCode, response.body).toBe(200);
  const replyId = response.json().comment.id;
  expect(response.json().comment).toMatchObject({parentId: comment, status: 'PENDING_REVIEW'});
  const page = async (userId: string) => (await app.inject({url, headers: headers(userId)})).json();
  expect((await page(owner)).items.map((item: {id: string}) => item.id)).not.toContain(replyId);
  expect((await page(other))).toMatchObject({repliesSupported: true, items: expect.arrayContaining([
   expect.objectContaining({id: replyId, parentId: comment, parent: {user: {profile: {displayName: 'Fiction author'}}}}),
  ])});
  await db.socialComment.update({where: {id: replyId}, data: {status: 'PUBLISHED'}});
  expect((await send(replyId)).statusCode).toBe(404);
  await db.userBlock.create({data: {blockerId: owner, blockedId: reader}});
  try {
   expect((await page(owner)).items.map((item: {id: string}) => item.id)).not.toContain(replyId);
  } finally {
   await db.userBlock.deleteMany({where: {blockerId: owner, blockedId: reader}});
  }
  await db.socialComment.delete({where: {id: replyId}});
 } finally {
  await db.socialComment.delete({where: {id: pending.id}});
  await db.character.delete({where: {id: another.id}});
 }
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
it('enforces the 18+ opt-in for the following feed and discussions', async () => {
 const mature = await db.character.create({data: {ownerId: owner, name: 'Mature fixture', greeting: 'Hello',
  category: 'fiction', tags: [], mature: true, publication: {create: {status: 'PUBLISHED'}}}, include: {publication: true}});
 await db.creatorFollow.create({data: {followerId: other, creatorId: owner}});
 const feed = async () => (await app.inject({url: '/v1/community/following', headers: headers(other)})).json().items;
 const discussion = () => app.inject({url: `/v1/publications/${mature.publication!.id}/comments`, headers: headers(other)});
 const like = () => app.inject({method:'PUT',url:`/v1/publications/${mature.publication!.id}/like`,headers:headers(other)});
 try {
  expect(await feed()).not.toEqual(expect.arrayContaining([expect.objectContaining({id: mature.id})]));
  expect((await discussion()).statusCode).toBe(404);
  expect((await like()).statusCode).toBe(404);
  await db.profile.update({where: {userId: other}, data: {showMature: true}});
  expect(await feed()).toEqual(expect.arrayContaining([expect.objectContaining({id: mature.id})]));
  expect((await discussion()).statusCode).toBe(200);
  expect((await like()).statusCode).toBe(200);
  expect(await db.like.count({where:{publicationId:mature.publication!.id}})).toBe(1);
 } finally {
  await db.profile.update({where: {userId: other}, data: {showMature: false}});
  await db.creatorFollow.deleteMany({where: {followerId: other, creatorId: owner}});
  await db.character.delete({where: {id: mature.id}});
 }
});
it('keeps concurrent repeated block and follow actions idempotent',async()=>{
 const many = (url:string) => Promise.all(Array.from({length:8}, () =>
  app.inject({method:'PUT',url,headers:headers(other)})));
 try {
  const follows = await many(`/v1/creators/${owner}/follow`);
  expect(follows.map(r => r.statusCode)).toEqual(Array(8).fill(200));
  expect(await db.creatorFollow.count({where:{followerId:other,creatorId:owner}})).toBe(1);
  const blocks = await many(`/v1/blocks/${owner}`);
  expect(blocks.map(r => r.statusCode)).toEqual(Array(8).fill(200));
  expect(await db.userBlock.count({where:{blockerId:other,blockedId:owner}})).toBe(1);
 } finally {
  await db.userBlock.deleteMany({where:{blockerId:other,blockedId:owner}});
  await db.creatorFollow.deleteMany({where:{followerId:other,creatorId:owner}});
 }
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
it.each(['blocked by the reader', 'blocked by the author', 'hidden', 'sent back to moderation'])(
 'preserves existing chat history but denies new AI generation when the character is %s', async (reason) => {
  const created = await app.inject({method: 'POST', url: '/v1/chats', headers: headers(reader), payload: {characterId: character}});
  expect(created.statusCode, created.body).toBe(201);
  const chatId = created.json().chat.id;
  const url = `/v1/chats/${chatId}/messages`;
  await db.message.create({data: {chatId, role: 'user', content: 'Сохранённый вопрос'}});
  const last = await db.message.create({data: {chatId, role: 'assistant', content: 'Сохранённый ответ'}});
  const blockerId = reason === 'blocked by the reader' ? reader : owner;
  const blockedId = blockerId === reader ? owner : reader;
  const isBlock = reason.startsWith('blocked');
  if (isBlock) await db.userBlock.create({data: {blockerId, blockedId}});
  else if (reason === 'hidden') await db.character.update({where: {id: character}, data: {isHidden: true}});
  else await db.publication.update({where: {id: publication}, data: {status: 'PENDING_REVIEW'}});
  vi.stubEnv('GROQ_API_KEY', 'test-only-key');
  const fetchMock = vi.fn(async () => new Response(JSON.stringify({choices: [{message: {content: 'Новая реплика'}}]})));
  vi.stubGlobal('fetch', fetchMock);
  try {
   const history = await app.inject({url, headers: headers(reader)});
   expect(history.statusCode).toBe(200);
   expect(history.json().items.map((item: {content: string}) => item.content)).toEqual(['Сохранённый вопрос', 'Сохранённый ответ']);
   const send = await app.inject({method: 'POST', url, headers: headers(reader),
    payload: {requestId: randomUUID(), content: 'Продолжи'}});
   expect(send.statusCode, send.body).toBe(404);
   expect(send.json()).toEqual({error: 'CHARACTER_NOT_FOUND'});
   const regenerate = await app.inject({method: 'POST', url: url + '/stream', headers: headers(reader),
    payload: {requestId: randomUUID(), regenerateMessageId: last.id}});
   expect(regenerate.body).toContain('event: error');
   expect(regenerate.body).toContain('"error":"CHARACTER_NOT_FOUND"');
   expect(regenerate.body).not.toContain('event: delta');
   expect(fetchMock).not.toHaveBeenCalled();
   expect(await db.chatRequest.count({where: {chatId}})).toBe(0);
   expect(await db.message.count({where: {chatId}})).toBe(2);
  } finally {
   vi.unstubAllGlobals();
   vi.unstubAllEnvs();
   if (isBlock) await db.userBlock.delete({where: {blockerId_blockedId: {blockerId, blockedId}}});
   else if (reason === 'hidden') await db.character.update({where: {id: character}, data: {isHidden: false}});
   else await db.publication.update({where: {id: publication}, data: {status: 'PUBLISHED'}});
   await db.chat.delete({where: {id: chatId}});
  }
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
