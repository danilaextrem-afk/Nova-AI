import type { FastifyInstance } from 'fastify';
import type { PrismaClient, Prisma } from '@prisma/client';
import { createHash } from 'node:crypto';
import { z } from 'zod';
import { aiSettings, completeAiChat, AiProviderError, premiumReady, modelPool } from './aiGateway.js';
import type { AiStreamOptions } from './aiGateway.js';
import { buildCharacterPrompt } from './characterPrompt.js';
import { settleReferral } from './rewardRoutes.js';
import { showsMature } from './characters.js';
import { balance, debitInTransaction, InsufficientTokensError } from './ledger.js';
import { extractMemories, pickSpeakers, stripSpeakerPrefix, keepOwnVoice, registerMemoryRoutes } from './memoryEngine.js';
import { shapeReply } from './characterPrompt.js';

export const visibleCharacter = (userId:string,showMature=false):Prisma.CharacterWhereInput => ({OR:[
  {ownerId:userId},{isHidden:false,publication:{is:{status:'PUBLISHED'}},owner:{blocksGot:{none:{blockerId:userId}},blocksMade:{none:{blockedId:userId}}},
    ...(showMature?{}:{mature:false})},
]});
export async function premiumPrice(db:PrismaClient) {
  const setting=await db.setting.findUnique({where:{key:'premiumPriceTokens'}});
  const price=Number(setting?.value ?? process.env.NOVA_PREMIUM_TOKENS_PER_MESSAGE ?? 5);
  return Number.isInteger(price)&&price>0&&price<=1000?price:5;
}
const id=z.string().uuid();
const sendInput=z.object({content:z.string().trim().min(1).max(4000).optional(),requestId:id,
  tier:z.enum(['free','premium']).default('free'),expectedPriceTokens:z.number().int().min(0).max(1000).optional(),
  // Regenerate the latest assistant reply instead of sending new text. Earlier versions are kept.
  regenerateMessageId:id.optional()}).strict()
  .refine(v=>!!v.content!==!!v.regenerateMessageId,{message:'Exactly one of content or regenerateMessageId'});
class RegenerateTargetChanged extends Error {}
class ChatAttemptExpired extends Error {}
export async function registerChatRoutes(app:FastifyInstance,db:PrismaClient){
  const background = new Set<Promise<void>>();
  const runInBackground = (task: Promise<unknown>, onFailure: () => void) => {
    const pending = task.then(() => undefined, onFailure).finally(() => background.delete(pending));
    background.add(pending);
  };
  const drainChatJobs = async () => { await Promise.all(background); };
  app.addHook('onClose', drainChatJobs);
  const auth={preHandler:app.auth};
  await registerMemoryRoutes(app,db,async userId=>visibleCharacter(userId,await showsMature(db,userId)));
  /** Adds the speaking character's name to group replies (names only; the chat is the caller's own). */
  const withNames=async<T extends {speakerId?:string|null}>(rows:(T|null)[])=>{
    const ids=Array.from(new Set(rows.map(r=>r?.speakerId).filter((x):x is string=>!!x)));
    const names=new Map((ids.length?await db.character.findMany({where:{id:{in:ids}},select:{id:true,name:true}}):[]).map(c=>[c.id,c.name]));
    return rows.map(r=>r?{...r,speakerName:r.speakerId?names.get(r.speakerId)??null:null}:null);
  };
  app.get('/v1/ai/status',auth,async(req:any)=>({ ...aiSettings(),freeUnlimited:true,
    premiumConfigured:premiumReady(),premiumProvider:aiSettings('premium').provider,
    premiumModel:premiumReady()?aiSettings('premium').model:null,premiumPriceTokens:await premiumPrice(db),
    tokenBalance:(await balance(db,req.user.sub)).total }));
  app.post('/v1/chats',auth,async(req:any,reply)=>{
    const parsed=z.object({characterId:id,participantIds:z.array(id).max(4).default([]),
      scenario:z.string().max(2000).default(''),persona:z.string().max(1000).default(''),scenarioId:id.optional()}).strict().safeParse(req.body);
    if(!parsed.success)return reply.code(400).send({error:'INVALID_CHAT_INPUT'});
    const members=Array.from(new Set([parsed.data.characterId,...parsed.data.participantIds]));
    const characters=await db.character.findMany({where:{id:{in:members},...visibleCharacter(req.user.sub,await showsMature(db,req.user.sub))}});
    if(characters.length!==members.length)return reply.code(404).send({error:'CHARACTER_NOT_FOUND'});
    // An author's scenario ("alternate universe") becomes this chat's own editable scene.
    const preset=parsed.data.scenarioId?await db.characterScenario.findFirst({where:{id:parsed.data.scenarioId,characterId:parsed.data.characterId}}):null;
    if(parsed.data.scenarioId&&!preset)return reply.code(404).send({error:'SCENARIO_NOT_FOUND'});
    const chat=await db.chat.create({data:{userId:req.user.sub,characterId:parsed.data.characterId,
      participantIds:members.filter(x=>x!==parsed.data.characterId),scenario:parsed.data.scenario||preset?.scenario||'',persona:parsed.data.persona,
      title:(characters.map(x=>x.name).join(', ')+(preset?' · '+preset.title:'')).slice(0,200)}});
    const lead = characters.find(character => character.id === chat.characterId)!;
    return reply.code(201).send({
      chat: { ...chat, characterName: lead.name, characterAvatarUrl: lead.avatarUrl },
      greeting: preset?.greeting || null,
    });
  });
  app.get('/v1/chats',auth,async(req:any,reply)=>{
    const q=z.object({limit:z.coerce.number().int().min(1).max(100).default(50),q:z.string().max(100).optional()}).safeParse(req.query);
    if(!q.success)return reply.code(400).send({error:'INVALID_QUERY'});
    const items=await db.chat.findMany({where:{userId:req.user.sub,...(q.data.q?{OR:[{title:{contains:q.data.q,mode:'insensitive'}},{messages:{some:{content:{contains:q.data.q,mode:'insensitive'}}}}]}:{})},orderBy:[{updatedAt:'desc'},{id:'desc'}],take:q.data.limit,include:{character:{select:{name:true,avatarUrl:true}}}});
    return {items:items.map(({character,...chat})=>({...chat,characterName:character.name,characterAvatarUrl:character.avatarUrl}))};
  });
  app.get('/v1/chats/:id/messages',auth,async(req:any,reply)=>{
    const q=z.object({before:z.coerce.number().int().positive().optional(),limit:z.coerce.number().int().min(1).max(100).default(50),
      q:z.string().trim().min(1).max(100).optional()}).safeParse(req.query); // q: search inside this conversation
    if(!id.safeParse(req.params.id).success||!q.success)return reply.code(400).send({error:'INVALID_QUERY'});
    if(!await db.chat.findFirst({where:{id:req.params.id,userId:req.user.sub}}))return reply.code(404).send({error:'CHAT_NOT_FOUND'});
    const rows=await db.message.findMany({where:{chatId:req.params.id,...(q.data.before?{sequence:{lt:q.data.before}}:{}),
      ...(q.data.q?{content:{contains:q.data.q,mode:'insensitive'}}:{})},orderBy:{sequence:'desc'},take:q.data.limit});
    rows.reverse(); // Oldest first; the cursor is the oldest row of this page.
    return {items:await withNames(rows),nextBefore:rows.length===q.data.limit?rows[0].sequence:null};
  });
  app.get('/v1/chats/:id/requests',auth,async(req:any,reply)=>{
    if(!await db.chat.findFirst({where:{id:req.params.id,userId:req.user.sub}}))return reply.code(404).send({error:'CHAT_NOT_FOUND'});
    return {items:await db.chatRequest.findMany({where:{chatId:req.params.id,userId:req.user.sub,status:{not:'SUCCEEDED'}},orderBy:{startedAt:'desc'},take:20})};
  });
  app.patch('/v1/chats/:id',auth,async(req:any,reply)=>{
    const p=z.object({persona:z.string().max(1000).optional(),scenario:z.string().max(2000).optional(),
      responseStyle:z.enum(['concise','balanced','detailed','story']).optional(),tone:z.enum(['friendly','dramatic','humorous','calm']).optional(),title:z.string().min(1).max(200).optional(),
      participantIds:z.array(id).max(4).optional()}).strict().safeParse(req.body); // Group cast: main character + up to 4 others.
    if(!p.success)return reply.code(400).send({error:'INVALID_INPUT'});
    if(p.data.participantIds){
      const own=await db.chat.findFirst({where:{id:req.params.id,userId:req.user.sub}});
      if(!own)return reply.code(404).send({error:'CHAT_NOT_FOUND'});
      const wanted=Array.from(new Set(p.data.participantIds)).filter(x=>x!==own.characterId);
      const found=await db.character.count({where:{id:{in:wanted},...visibleCharacter(req.user.sub,await showsMature(db,req.user.sub))}});
      if(found!==wanted.length)return reply.code(404).send({error:'CHARACTER_NOT_FOUND'});
      p.data.participantIds=wanted;
    }
    const updated=await db.chat.updateMany({where:{id:req.params.id,userId:req.user.sub},data:p.data});
    return updated.count?{updated:true}:reply.code(404).send({error:'CHAT_NOT_FOUND'});
  });
  app.delete('/v1/chats/:id',auth,async(req:any,reply)=>{
    const r=await db.chat.deleteMany({where:{id:req.params.id,userId:req.user.sub}});
    return r.count?reply.code(204).send():reply.code(404).send({error:'CHAT_NOT_FOUND'});
  });
  const sendConfig={preHandler:app.auth,config:{rateLimit:{max:20,timeWindow:'1 minute'}}};
  app.post('/v1/chats/:id/messages',sendConfig,async(req:any,reply)=>sendMessage(req,reply));
  // Server-Sent Events: "delta" events while the model writes, then one "done" or "error" event.
  // Same idempotency, queue and charging as the JSON route. Closing the connection cancels the
  // upstream call; a cancelled or interrupted answer is not saved and never charged.
  app.post('/v1/chats/:id/messages/stream',sendConfig,async(req:any,reply)=>{
    const controller=new AbortController();
    reply.hijack();
    const raw=reply.raw;
    raw.writeHead(200,{'Content-Type':'text/event-stream; charset=utf-8','Cache-Control':'no-cache, no-transform',
      'X-Accel-Buffering':'no','X-Content-Type-Options':'nosniff',Connection:'keep-alive'});
    raw.on('close',()=>{if(!raw.writableFinished)controller.abort();});
    const event=(name:string,data:unknown)=>{if(!raw.destroyed&&!raw.writableEnded)raw.write(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);};
    const ping=setInterval(()=>{if(!raw.destroyed&&!raw.writableEnded)raw.write(': ping\n\n');},15000);
    const captured:any={statusCode:200,headers:{} as Record<string,unknown>,body:undefined,
      code(c:number){this.statusCode=c;return this;},header(k:string,v:unknown){this.headers[k]=v;return this;},
      send(b:unknown){this.body=b;return captured;}};
    try{
      const result=await sendMessage(req,captured,{onDelta:text=>event('delta',{text}),signal:controller.signal});
      if(result===captured)event(captured.statusCode<300?'done':'error',{status:captured.statusCode,
        retryAfter:captured.headers['Retry-After']??null,...(captured.body as object)});
      else event('done',result);
    }catch(error){req.log.error({code:'STREAM_FAILURE'},'Stream handler failed');event('error',{status:500,error:'STREAM_FAILURE'});}
    finally{clearInterval(ping);if(!raw.writableEnded)raw.end();}
  });
  async function sendMessage(req:any,reply:any,stream:AiStreamOptions={}){
    const body=sendInput.safeParse(req.body);
    if(!id.safeParse(req.params.id).success||!body.success)return reply.code(400).send({error:'INVALID_MESSAGE'});
    const userId=req.user.sub as string;
    const chat=await db.chat.findFirst({where:{id:req.params.id,userId},include:{character:true}});
    if(!chat)return reply.code(404).send({error:'CHAT_NOT_FOUND'});
    const canViewMature = chat.character.ownerId !== userId || chat.participantIds.length > 0
      ? await showsMature(db, userId) : false;
    if (chat.character.mature && chat.character.ownerId !== userId && !canViewMature) {
      return reply.code(403).send({error: 'MATURE_SECTION_DISABLED'});
    }
    const {tier,requestId,regenerateMessageId}=body.data;
    // For regeneration the prompt is the user turn that preceded the latest assistant reply.
    let content=body.data.content??'';let target:{id:string,sequence:number}|null=null;let promptUser:{sequence:number}|null=null;
    let regenerationSpeaker: typeof chat.character | undefined;
    if(regenerateMessageId){
      const last=await db.message.findFirst({where:{chatId:chat.id},orderBy:{sequence:'desc'}});
      const prior=await db.chatRequest.findUnique({where:{id:requestId}});
      const priorPrint=createHash('sha256').update(JSON.stringify([chat.id,'regenerate',regenerateMessageId,tier])).digest('hex');
      if(prior&&(prior.userId!==userId||prior.fingerprint!==priorPrint))return reply.code(409).send({error:'REQUEST_ID_USED'});
      if(prior?.status==='SUCCEEDED'){
        // Replay returns the committed result even if newer turns were added since.
        return {duplicate:true,userMessage:null,assistantMessage:(await db.message.findUnique({where:{regeneratedByRequestId:requestId}}))??(prior.targetMessageId?await db.message.findUnique({where:{id:prior.targetMessageId}}):null),tier:prior.tier,tokensCharged:prior.priceTokens};
      }
      if(!last||last.id!==regenerateMessageId||last.role!=='assistant')return reply.code(409).send({error:'REGENERATE_ONLY_LATEST_REPLY'});
      const user=await db.message.findFirst({where:{chatId:chat.id,role:'user',sequence:{lt:last.sequence}},orderBy:{sequence:'desc'}});
      if(!user)return reply.code(409).send({error:'REGENERATE_NO_USER_TURN'});
      if (last.speakerId && last.speakerId !== chat.characterId) {
        if (!chat.participantIds.includes(last.speakerId)) {
          return reply.code(409).send({error: 'REGENERATE_SPEAKER_UNAVAILABLE'});
        }
        const speaker = await db.character.findFirst({
          where: {id: last.speakerId, ...visibleCharacter(userId, canViewMature)},
        });
        if (!speaker) return reply.code(409).send({error: 'REGENERATE_SPEAKER_UNAVAILABLE'});
        regenerationSpeaker = speaker;
      }
      target={id:last.id,sequence:last.sequence};promptUser={sequence:user.sequence};content=user.content.slice(0,4000);
    }
    const fingerprint=createHash('sha256').update(JSON.stringify(regenerateMessageId?[chat.id,'regenerate',regenerateMessageId,tier]:[chat.id,content,tier])).digest('hex');
    const old=await db.chatRequest.findUnique({where:{id:requestId}});
    if(old&&(old.userId!==userId||old.fingerprint!==fingerprint))return reply.code(409).send({error:'REQUEST_ID_USED'});
    if(old?.status==='FAILED'&&['CHAT_STORAGE_FAILURE','AI_COORDINATOR_FAILURE'].includes(old.errorCode||''))return reply.code(409).send({error:'REQUEST_REQUIRES_REVIEW'});
    if(old?.status==='SUCCEEDED'&&!regenerateMessageId){
      const [userMessage,first,extra]=await Promise.all([db.message.findUnique({where:{requestId}}),db.message.findUnique({where:{replyToRequestId:requestId}}),
        db.message.findMany({where:{turnRequestId:requestId},orderBy:{sequence:'asc'}})]);
      const [assistantMessage,...extraMessages]=await withNames([first,...extra]);
      return {duplicate:true,userMessage,assistantMessage,extraMessages,tier:old.tier,tokensCharged:old.priceTokens};
    }
    // Historical replies stay readable. New generation must respect current publication and block state.
    const lead = chat.character.ownerId === userId ? chat.character : await db.character.findFirst({
      where: {id: chat.characterId, ...visibleCharacter(userId, canViewMature)},
    });
    if (!lead) return reply.code(404).send({error: 'CHARACTER_NOT_FOUND'});
    // Legacy requests from previous releases must never be re-billed. No guessing the answer by timestamp.
    if(!old&&await db.message.findUnique({where:{requestId}}))return reply.code(409).send({error:'LEGACY_REQUEST_ALREADY_SAVED'});
    if(tier==='premium'&&!premiumReady())return reply.code(503).send({error:'PREMIUM_NOT_CONFIGURED'});
    if(!aiSettings(tier).configured)return reply.code(503).send({error:'AI_NOT_CONFIGURED'});
    const price=tier==='premium'?await premiumPrice(db):0;
    if(tier==='premium'&&body.data.expectedPriceTokens!==price)return reply.code(409).send({error:'PRICE_CHANGED',priceTokens:price});
    const reservation=await db.$transaction(async tx=>{
      await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${userId} FOR UPDATE`;
      const previous=await tx.chatRequest.findUnique({where:{id:requestId}});
      if(previous&&(previous.fingerprint!==fingerprint||previous.userId!==userId))return 'REQUEST_ID_USED';
      if(previous?.status==='SUCCEEDED')return 'REQUEST_COMPLETED_RETRY';
      const now = Date.now();
      const expiresBefore = new Date(now - 180000);
      if (previous?.status === 'PENDING' && previous.startedAt >= expiresBefore) return 'REQUEST_IN_PROGRESS';
      await tx.chatRequest.updateMany({where:{userId,status:'PENDING',startedAt:{lt:expiresBefore}},data:{status:'FAILED',errorCode:'REQUEST_TIMED_OUT'}});
      if(await tx.chatRequest.count({where:{userId,status:'PENDING'}}))return 'CHAT_BUSY';
      if(tier==='premium'){
        const w=await tx.walletAccount.findUnique({where:{userId}});
        if((w?.purchased??0)+(w?.adReward??0)+(w?.bonus??0)<price)return 'INSUFFICIENT_TOKENS';
      }
      // Persist a distinct attempt timestamp even when retries start in the same millisecond.
      const startedAt = new Date(Math.max(now, (previous?.startedAt.getTime() ?? 0) + 1));
      await tx.chatRequest.upsert({where:{id:requestId},create:{id:requestId,userId,chatId:chat.id,content,fingerprint,tier,priceTokens:price,targetMessageId:target?.id??null,startedAt},
        update:{status:'PENDING',errorCode:null,startedAt,priceTokens:price}});
      return startedAt;
    });
    if (typeof reservation === 'string') return reply.code(reservation==='INSUFFICIENT_TOKENS'?402:409).send({error:reservation});
    const attempt = {id: requestId, userId, status: 'PENDING', startedAt: reservation};
    const started=Date.now();
    try {
      const [history,memories,others,summary]=await Promise.all([
        db.message.findMany({where:{chatId:chat.id,...(promptUser?{sequence:{lt:promptUser.sequence}}:{})},orderBy:{sequence:'desc'},take:tier==='premium'?36:24}),
        db.memory.findMany({where:{chatId:chat.id,kind:{not:'SUMMARY'}},orderBy:[{pinned:'desc'},{updatedAt:'desc'}],take:100}),
        db.character.findMany({where:{id:{in:chat.participantIds},...visibleCharacter(userId,canViewMature)}}),
        db.memory.findFirst({where:{chatId:chat.id,kind:'SUMMARY'},orderBy:{updatedAt:'desc'}}),
      ]);
      others.sort((x,y)=>chat.participantIds.indexOf(x.id)-chat.participantIds.indexOf(y.id)); // Stable cast order.
      const cast=[lead,...others];
      const isGroup=others.length>0;
      const names=new Map(cast.map(c=>[c.id,c.name]));
      // What each character remembers about this player across all of the player's stories (private to the player).
      const lasting=await db.characterMemory.findMany({where:{userId,characterId:{in:cast.map(c=>c.id)}},orderBy:[{pinned:'desc'},{updatedAt:'desc'}],take:60});
      let speakers = [regenerationSpeaker ?? lead];
      if (isGroup && !target) {
        const spoke=await db.message.findMany({where:{chatId:chat.id,role:'assistant'},orderBy:{sequence:'desc'},take:12,select:{speakerId:true}});
        // AI cost control: a turn is 2 replies on the free tier and 3 on Premium, however large the cast.
        speakers=pickSpeakers(cast,content,spoke.map(m=>m.speakerId),tier==='premium'?3:2);
      }
      const preferredLimit = modelPool(tier)[0]?.tpm ?? 8000;
      const tokenBudget = Math.min(tier === 'premium' ? 22000 : 7400, preferredLimit - 200);
      // Each speaker is a separate model call with its own character sheet and memory, and sees what
      // the previous speakers said this turn. The first reply is required; later ones are best-effort.
      const outputs:{speaker:any,output:Awaited<ReturnType<typeof completeAiChat>>,text:string}[]=[];
      const turn: { name: string; text: string }[] = [];
      for(const [i,sp] of speakers.entries()){
        if(stream.signal?.aborted)throw new AiProviderError('AI_CANCELLED',499,1);
        let onDelta = stream.onDelta;
        if (isGroup && stream.onDelta) {
          const emit = stream.onDelta;
          let first = true;
          onDelta = text => {
            emit(first ? (i ? '\n\n' : '') + sp.name + ': ' + text : text);
            first = false;
          };
        }
        const { messages, settings } = buildCharacterPrompt({
          speaker: sp, cast, chat, historyNewestFirst: history, notes: memories,
          characterNotes: lasting.filter(note => note.characterId === sp.id).map(note => ({ ...note, kind: 'FACT' })),
          summary: promptUser && chat.summaryThrough >= promptUser.sequence ? undefined : summary?.content,
          request: content, tier, tokenBudget, earlierReplies: turn,
        });
        let output;
        try {
          output = await completeAiChat(messages, tier, db, i ? undefined : requestId, {
            ...stream, onDelta, maxOutput: settings.maxOutput, temperature: settings.temperature,
          });
        }
        catch(e){if(i===0||stream.signal?.aborted)throw e;break;}
        const text=shapeReply(isGroup?keepOwnVoice(stripSpeakerPrefix(output.text,sp.name),cast.filter(c=>c.id!==sp.id).map(c=>c.name)):output.text,chat.responseStyle,sp.greeting);
        outputs.push({speaker:sp,output,text});turn.push({ name: sp.name, text });
      }
      const output=outputs[0].output;
      const saved=await db.$transaction(async tx=>{
        if(stream.signal?.aborted)throw new AiProviderError('AI_CANCELLED',499,1);
        await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${userId} FOR UPDATE`;
        if(stream.signal?.aborted)throw new AiProviderError('AI_CANCELLED',499,1);
        if (!await tx.chatRequest.findFirst({where: attempt, select: {id: true}})) throw new ChatAttemptExpired();
        let userMessage:any=null;let assistantMessage:any;
        if(target){
          // Lock the chat so a concurrent new turn cannot slip in between the check and the replace.
          await tx.$queryRaw`SELECT id FROM "Chat" WHERE id = ${chat.id} FOR UPDATE`;
          const last=await tx.message.findFirst({where:{chatId:chat.id},orderBy:{sequence:'desc'}});
          if(!last||last.id!==target.id)throw new RegenerateTargetChanged();
          if(price)await debitInTransaction(tx,{userId,amount:price,source:'premium_ai_chat',idempotencyKey:'premium:'+requestId});
          assistantMessage=await tx.message.update({where:{id:target.id},data:{content:outputs[0].text,editedAt:new Date(),
            regeneratedByRequestId:requestId,alternatives:[...last.alternatives,last.content].slice(-5)}});
        }else{
          if(price)await debitInTransaction(tx,{userId,amount:price,source:'premium_ai_chat',idempotencyKey:'premium:'+requestId});
          userMessage=await tx.message.create({data:{chatId:chat.id,role:'user',content,requestId}});
          assistantMessage=await tx.message.create({data:{chatId:chat.id,role:'assistant',content:outputs[0].text,replyToRequestId:requestId,
            speakerId:isGroup?outputs[0].speaker.id:null}});
        }
        const extraMessages=[];
        for(const o of outputs.slice(1)){
          extraMessages.push(await tx.message.create({data:{chatId:chat.id,role:'assistant',content:o.text,turnRequestId:requestId,speakerId:o.speaker.id}}));
          await tx.aiUsage.create({data:{userId,feature:'chat',provider:o.output.provider,model:o.output.model,inputTokens:o.output.inputTokens,
            outputTokens:o.output.outputTokens,costMicros:o.output.costMicros,success:true}});
        }
        await tx.chatRequest.update({where:{id:requestId},data:{status:'SUCCEEDED',errorCode:null}});
        await tx.aiUsage.create({data:{id:requestId,userId,feature:'chat',provider:output.provider,model:output.model,inputTokens:output.inputTokens,
          outputTokens:output.outputTokens,costMicros:output.costMicros,latencyMs:Date.now()-started,success:true}});
        await tx.chat.update({where:{id:chat.id},data:{updatedAt:new Date()}});
        const [named,...extraNamed]=[assistantMessage,...extraMessages].map(m=>({...m,speakerName:m.speakerId?names.get(m.speakerId)??null:null}));
        return {userMessage,assistantMessage:named,extraMessages:extraNamed};
      });
      // Automatic memories are best-effort too: every few turns, one small call, never blocking the reply.
      runInBackground(extractMemories(db, chat.id, userId), () => req.log.warn({ code: 'MEMORY_DEFERRED' }, 'Memory extraction deferred'));
      // Referral rewards unlock after the invitee's first real chats; best-effort, idempotent.
      runInBackground(settleReferral(db, userId), () => req.log.warn({ code: 'REFERRAL_DEFERRED' }, 'Referral settle deferred'));
      return {...saved,duplicate:false,tier,tokensCharged:price,
        inference:{provider:output.provider,model:output.model}};
    }catch(error){
      const code=error instanceof AiProviderError?error.code:error instanceof InsufficientTokensError?'INSUFFICIENT_TOKENS':
        error instanceof RegenerateTargetChanged?'REGENERATE_TARGET_CHANGED':
        error instanceof ChatAttemptExpired?'REQUEST_EXPIRED':'CHAT_STORAGE_FAILURE';
      await db.chatRequest.updateMany({where:attempt,data:{status:'FAILED',errorCode:code}});
      if(error instanceof AiProviderError)reply.header('Retry-After',error.retryAfter);
      req.log.warn({code},'Chat request failed; no token debit committed');
      return reply.code(error instanceof AiProviderError?error.httpStatus:error instanceof InsufficientTokensError?402:
        error instanceof RegenerateTargetChanged||error instanceof ChatAttemptExpired?409:503).send({error:code,requestId});
    }
  }
  return drainChatJobs;
}
