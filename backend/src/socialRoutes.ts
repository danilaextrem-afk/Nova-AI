import type {FastifyInstance} from 'fastify';
import type {PrismaClient} from '@prisma/client';
import {z} from 'zod';
import {showsMature} from './characters.js';
export async function registerSocialRoutes(app:FastifyInstance,db:PrismaClient){
 const auth={preHandler:app.auth};
 const unblocked = (userId: string) => ({
  blocksGot: {none: {blockerId: userId}},
  blocksMade: {none: {blockedId: userId}},
 });
 const visible = (userId: string, mature: boolean) => ({
  status: 'PUBLISHED' as const,
  character: {isHidden: false, owner: unblocked(userId), ...(mature ? {} : {mature: false})},
 });
 const commentVisibility = (userId: string) => ({
  OR: [{status: 'PUBLISHED'}, {userId}],
  user: unblocked(userId),
 });
 app.get('/v1/publications/:id/comments', auth, async (req: any, reply) => {
  const userId: string = req.user.sub;
  const publicationId: string = req.params.id;
  if (!await db.publication.findFirst({where: {id: publicationId, ...visible(userId, await showsMature(db, userId))}})) {
   return reply.code(404).send({error: 'NOT_FOUND'});
  }
  const items = await db.socialComment.findMany({
   where: {publicationId, ...commentVisibility(userId), AND: [
    {OR: [{parentId: null}, {parent: commentVisibility(userId)}]},
   ]},
   select: {id: true, userId: true, parentId: true, content: true, status: true, createdAt: true,
    user: {select: {profile: {select: {displayName: true}}}},
    parent: {select: {user: {select: {profile: {select: {displayName: true}}}}}},
   },
   orderBy: [{createdAt: 'desc'}, {id: 'desc'}],
   take: 50,
  });
  return {items, repliesSupported: true};
 });
 app.post('/v1/publications/:id/comments', auth, async (req: any, reply) => {
  const parsed = z.object({content: z.string().trim().min(1).max(1000), parentId: z.string().uuid().optional()})
   .strict().safeParse(req.body);
  if (!parsed.success) return reply.code(400).send({error: 'INVALID_COMMENT'});
  const userId: string = req.user.sub;
  const publicationId: string = req.params.id;
  if (!await db.publication.findFirst({where: {id: publicationId, ...visible(userId, await showsMature(db, userId))}})) {
   return reply.code(404).send({error: 'NOT_FOUND'});
  }
  const {content, parentId} = parsed.data;
  if (parentId && !await db.socialComment.findFirst({where: {
   id: parentId, publicationId, parentId: null, status: 'PUBLISHED', user: unblocked(userId),
  }, select: {id: true}})) {
   return reply.code(404).send({error: 'COMMENT_NOT_FOUND'});
  }
  return {comment: await db.socialComment.create({data: {userId, publicationId, content, parentId}})};
 });
 app.delete('/v1/comments/:id',auth,async(req:any)=>{await db.socialComment.deleteMany({where:{id:req.params.id,userId:req.user.sub}});return {deleted:true}});
 app.put('/v1/creators/:id/follow',auth,async(req:any,reply)=>{
  if(req.params.id===req.user.sub||!await db.user.findFirst({where:{id:req.params.id,status:'ACTIVE',blocksGot:{none:{blockerId:req.user.sub}},blocksMade:{none:{blockedId:req.user.sub}}}}))return reply.code(400).send({error:'INVALID_CREATOR'});
  await db.creatorFollow.upsert({where:{followerId_creatorId:{followerId:req.user.sub,creatorId:req.params.id}},create:{followerId:req.user.sub,creatorId:req.params.id},update:{}});return {following:true};
 });
 app.delete('/v1/creators/:id/follow',auth,async(req:any)=>{await db.creatorFollow.deleteMany({where:{followerId:req.user.sub,creatorId:req.params.id}});return {following:false}});
 app.get('/v1/community/following', auth, async (req: any) => {
  const userId: string = req.user.sub;
  const mature = await showsMature(db, userId);
  return {items: await db.character.findMany({
   where: {isHidden: false, publication: {is: {status: 'PUBLISHED'}},
    owner: {followers: {some: {followerId: userId}}, ...unblocked(userId)},
    ...(mature ? {} : {mature: false})},
   select: {id: true, name: true, greeting: true, category: true, tags: true, biography: true,
    avatarUrl: true, mature: true, ownerId: true, publication: {select: {id: true, likesCount: true}}},
   orderBy: {createdAt: 'desc'},
   take: 50,
  })};
 });
 app.post('/v1/chats/:id/diary',auth,async(req:any,reply)=>{
  const p=z.object({requestId:z.string().uuid(),kind:z.enum(['diary','event','inner-monologue','fictional-phone']).default('diary')}).strict().safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:'INVALID_DIARY'});
  const title={diary:'Дневник',event:'Повседневное событие','inner-monologue':'Художественный внутренний монолог','fictional-phone':'Вымышленная заметка в телефоне персонажа'}[p.data.kind];
  // Uses the same persisted request/charging/ownership path. The result is part of this story's history.
  const result=await app.inject({method:'POST',url:'/v1/chats/'+encodeURIComponent(req.params.id)+'/messages',headers:{authorization:req.headers.authorization},
   payload:{requestId:p.data.requestId,tier:'free',content:`Напиши: ${title}. Начни с пометки «Вымышленная сцена». Это художественная история, не реальные мысли, личная переписка или данные человека. Сохрани события нашей истории.`}});
  return reply.code(result.statusCode).send(result.json());
 });
}
