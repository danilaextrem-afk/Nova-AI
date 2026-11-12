import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';
import { z } from 'zod';
export async function registerProductRoutes(app:FastifyInstance,db:PrismaClient){
 const auth={preHandler:app.auth};
 app.get('/v1/me',auth,async(req:any)=>{
  const u=await db.user.findUnique({where:{id:req.user.sub},select:{id:true,email:true,profile:true,createdAt:true}});return u;
 });
 app.patch('/v1/me',auth,async(req:any,reply)=>{
  const p=z.object({displayName:z.string().trim().min(1).max(80),bio:z.string().max(500).default('')}).strict().safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:'INVALID_PROFILE'});
  return db.profile.upsert({where:{userId:req.user.sub},create:{userId:req.user.sub,...p.data},update:p.data});
 });
 // 18+ section is opt-in: enabling requires an explicit confirmation in the same request; disabling never does.
 app.put('/v1/me/content-settings',auth,async(req:any,reply)=>{
  const p=z.object({showMature:z.boolean(),confirmAdult:z.literal(true).optional()}).strict().safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:'INVALID_SETTINGS'});
  if(p.data.showMature&&!p.data.confirmAdult)return reply.code(400).send({error:'ADULT_CONFIRMATION_REQUIRED'});
  const user=await db.user.findUnique({where:{id:req.user.sub},select:{email:true}});
  const data={showMature:p.data.showMature,...(p.data.showMature?{matureConfirmedAt:new Date()}:{})};
  const profile=await db.profile.upsert({where:{userId:req.user.sub},create:{userId:req.user.sub,displayName:user!.email.split('@')[0],...data},update:data});
  return {showMature:profile.showMature};
 });
 app.get('/v1/me/export',auth,async(req:any,reply)=>{
  const u=await db.user.findUnique({where:{id:req.user.sub},select:{email:true,createdAt:true,profile:true,
    characters:true,chats:{include:{messages:{orderBy:{sequence:'asc'}},memories:true}},favorites:true,notifications:true,comments:true,following:true,likes:true,characterMemories:true}});
  reply.header('Content-Disposition','attachment; filename="NOVA-data.json"');return u;
 });
 app.delete('/v1/me',auth,async(req:any,reply)=>{
  if(req.body?.confirmation!=='DELETE')return reply.code(400).send({error:'CONFIRMATION_REQUIRED'});
  await db.$transaction(async tx=>{
   await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${req.user.sub} FOR UPDATE`;
   await tx.chat.deleteMany({where:{userId:req.user.sub}});await tx.characterMemory.deleteMany({where:{userId:req.user.sub}});await tx.character.deleteMany({where:{ownerId:req.user.sub}});
   await tx.socialComment.deleteMany({where:{userId:req.user.sub}});await tx.creatorFollow.deleteMany({where:{OR:[{followerId:req.user.sub},{creatorId:req.user.sub}]}});
   await tx.report.deleteMany({where:{reporterId:req.user.sub}});
   await tx.userBlock.deleteMany({where:{OR:[{blockerId:req.user.sub},{blockedId:req.user.sub}]}});
   const likes=await tx.like.findMany({where:{userId:req.user.sub},select:{publicationId:true}});
   for(const like of likes){
    await tx.$queryRaw`SELECT id FROM "Publication" WHERE id = ${like.publicationId} FOR UPDATE`;
    await tx.like.deleteMany({where:{userId:req.user.sub,publicationId:like.publicationId}});
    await tx.publication.update({where:{id:like.publicationId},data:{likesCount:await tx.like.count({where:{publicationId:like.publicationId}})}});
   }
   await tx.profile.deleteMany({where:{userId:req.user.sub}});await tx.session.deleteMany({where:{userId:req.user.sub}});
   await tx.favorite.deleteMany({where:{userId:req.user.sub}});await tx.notification.deleteMany({where:{userId:req.user.sub}});
   await tx.user.update({where:{id:req.user.sub},data:{status:'DELETED',deletedAt:new Date(),email:req.user.sub+'@deleted.invalid',passwordHash:'DELETED'}});
  });return reply.code(204).send();
 });
 app.get('/v1/billing/products',auth,async(req:any)=>({enabled:process.env.NOVA_MONETIZATION_ENABLED==='true',
  accountId:req.user.sub,items:await db.paymentProduct.findMany({where:{active:true},orderBy:{tokens:'asc'}})}));
 app.get('/v1/notifications',auth,async(req:any)=>({items:await db.notification.findMany({where:{userId:req.user.sub},orderBy:{createdAt:'desc'},take:100})}));
 app.put('/v1/notifications/:id/read',auth,async(req:any)=>{await db.notification.updateMany({where:{id:req.params.id,userId:req.user.sub},data:{readAt:new Date()}});return {ok:true};});
 app.post('/v1/reports',auth,async(req:any,reply)=>{
  const p=z.object({targetType:z.enum(['PUBLICATION','USER','MESSAGE','SUPPORT']),targetId:z.string().min(1).max(100),reason:z.string().trim().min(5).max(500)}).strict().safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:'INVALID_REPORT'});
  return {report:await db.report.create({data:{reporterId:req.user.sub,...p.data}})};
 });
 app.put('/v1/blocks/:id',auth,async(req:any,reply)=>{
  if(req.params.id===req.user.sub)return reply.code(400).send({error:'CANNOT_BLOCK_SELF'});
  if(!await db.user.findUnique({where:{id:req.params.id}}))return reply.code(404).send({error:'NOT_FOUND'});
  await db.userBlock.upsert({where:{blockerId_blockedId:{blockerId:req.user.sub,blockedId:req.params.id}},create:{blockerId:req.user.sub,blockedId:req.params.id},update:{}});return {blocked:true};
 });
 app.put('/v1/publications/:id/like',auth,async(req:any,reply)=>{
  const p=await db.publication.findFirst({where:{id:req.params.id,status:'PUBLISHED',character:{isHidden:false,owner:{blocksGot:{none:{blockerId:req.user.sub}},blocksMade:{none:{blockedId:req.user.sub}}}}}});if(!p)return reply.code(404).send({error:'NOT_FOUND'});
  await db.$transaction(async tx=>{
   await tx.$queryRaw`SELECT id FROM "Publication" WHERE id = ${p.id} FOR UPDATE`;
   await tx.like.upsert({where:{userId_publicationId:{userId:req.user.sub,publicationId:p.id}},create:{userId:req.user.sub,publicationId:p.id},update:{}});
   await tx.publication.update({where:{id:p.id},data:{likesCount:await tx.like.count({where:{publicationId:p.id}})}});});return {liked:true};
 });
}
