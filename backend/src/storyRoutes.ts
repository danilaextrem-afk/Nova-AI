import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
const memoryInput=z.object({content:z.string().trim().min(1).max(2000),kind:z.enum(['FACT','EVENT','RELATION']).default('FACT'),pinned:z.boolean().default(false)}).strict();
export async function registerStoryRoutes(app:FastifyInstance,db:PrismaClient){
  const owned=async(req:any,reply:any)=>{
    await app.auth(req,reply);if(reply.sent)return;
    if(!await db.chat.findFirst({where:{id:req.params.id,userId:req.user.sub}}))return reply.code(404).send({error:'CHAT_NOT_FOUND'});
  };
  app.get('/v1/chats/:id/memories',{preHandler:owned},async(req:any)=>({items:await db.memory.findMany({where:{chatId:req.params.id},orderBy:[{pinned:'desc'},{createdAt:'asc'}]})}));
  app.post('/v1/chats/:id/memories',{preHandler:owned},async(req:any,reply)=>{
    const p=memoryInput.safeParse(req.body);if(!p.success)return reply.code(400).send({error:'INVALID_MEMORY'});
    if(await db.memory.count({where:{chatId:req.params.id}})>=100)return reply.code(409).send({error:'MEMORY_FULL'});
    return {memory:await db.memory.create({data:{...p.data,chatId:req.params.id}})};
  });
  app.patch('/v1/chats/:id/memories/:memoryId',{preHandler:owned},async(req:any,reply)=>{
    const p=memoryInput.partial().safeParse(req.body);if(!p.success)return reply.code(400).send({error:'INVALID_MEMORY'});
    const r=await db.memory.updateMany({where:{id:req.params.memoryId,chatId:req.params.id},data:{...p.data,...(p.data.content!==undefined?{source:'user'}:{})}});
    return r.count?{updated:true}:reply.code(404).send({error:'MEMORY_NOT_FOUND'});
  });
  app.delete('/v1/chats/:id/memories/:memoryId',{preHandler:owned},async(req:any,reply)=>{
    const r=await db.memory.deleteMany({where:{id:req.params.memoryId,chatId:req.params.id}});
    return r.count?reply.code(204).send():reply.code(404).send({error:'MEMORY_NOT_FOUND'});
  });
  app.post('/v1/chats/:id/branch',{preHandler:owned},async(req:any,reply)=>{
    const p=z.object({messageId:z.string().uuid(),title:z.string().min(1).max(150).default('Новая ветка')}).strict().safeParse(req.body);
    if(!p.success)return reply.code(400).send({error:'INVALID_BRANCH'});
    const message=await db.message.findFirst({where:{id:p.data.messageId,chatId:req.params.id}});
    if(!message)return reply.code(404).send({error:'MESSAGE_NOT_FOUND'});
    const source=(await db.chat.findUnique({where:{id:req.params.id}}))!;
    const chat=await db.$transaction(async tx=>{
      const c=await tx.chat.create({data:{userId:req.user.sub,characterId:source.characterId,participantIds:source.participantIds,
        title:p.data.title,parentChatId:source.id,persona:source.persona,scenario:source.scenario,responseStyle:source.responseStyle,tone:source.tone}});
      const messages=await tx.message.findMany({where:{chatId:source.id,sequence:{lte:message.sequence}},orderBy:{sequence:'asc'},take:10000});
      const turnIds = new Map<string, string>();
      for (const m of messages) {
        if (m.role === 'user' && m.requestId) turnIds.set(m.requestId, randomUUID());
      }
      await tx.message.createMany({data:messages.map(m=>({
        chatId:c.id,role:m.role,content:m.content,pinned:m.pinned,createdAt:m.createdAt,speakerId:m.speakerId,
        // The branch links its own copied messages without reusing the original AI or billing request.
        requestId:m.requestId ? turnIds.get(m.requestId) : undefined,
        replyToRequestId:m.replyToRequestId ? turnIds.get(m.replyToRequestId) : undefined,
        turnRequestId:m.turnRequestId ? turnIds.get(m.turnRequestId) : undefined,
      }))});
      // No summaries/facts from the future are copied into an earlier timeline.
      const memories=await tx.memory.findMany({where:{chatId:source.id,kind:{not:'SUMMARY'},updatedAt:{lte:message.createdAt}}});
      await tx.memory.createMany({data:memories.map(m=>({chatId:c.id,kind:m.kind,content:m.content,pinned:m.pinned,source:m.source}))});
      return c;
    });
    return reply.code(201).send({chat});
  });
  // Pin any message; edit the text of the user's own messages. Edits never touch charges or replies;
  // "Другой ответ" on the latest reply then answers the edited text.
  app.patch('/v1/chats/:id/messages/:messageId',{preHandler:owned},async(req:any,reply)=>{
    const p=z.object({pinned:z.boolean().optional(),content:z.string().trim().min(1).max(4000).optional()}).strict()
      .refine(v=>v.pinned!==undefined||v.content!==undefined).safeParse(req.body);
    if(!p.success)return reply.code(400).send({error:'INVALID_INPUT'});
    const message=await db.message.findFirst({where:{chatId:req.params.id,id:req.params.messageId}});
    if(!message)return reply.code(404).send({error:'MESSAGE_NOT_FOUND'});
    if(p.data.content!==undefined&&message.role!=='user')return reply.code(409).send({error:'ONLY_OWN_MESSAGES_EDITABLE'});
    const updated=await db.message.update({where:{id:message.id},data:{...p.data,...(p.data.content!==undefined?{editedAt:new Date()}:{})}});
    return {updated:true,message:updated};
  });
  // Delete a message. Deleting your message also removes the AI reply it produced, so the story stays coherent.
  app.delete('/v1/chats/:id/messages/:messageId',{preHandler:owned},async(req:any,reply)=>{
    const message=await db.message.findFirst({where:{chatId:req.params.id,id:req.params.messageId}});
    if(!message)return reply.code(404).send({error:'MESSAGE_NOT_FOUND'});
    const r=await db.message.deleteMany({where:{chatId:req.params.id,OR:[{id:message.id},
      ...(message.role==='user'&&message.requestId?[{replyToRequestId:message.requestId},{turnRequestId:message.requestId}]:[])]}});
    return {deleted:r.count};
  });
}
