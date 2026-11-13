import type { FastifyInstance } from 'fastify';
import type { PrismaClient } from '@prisma/client';
import argon2 from 'argon2';
import { z } from 'zod';
export async function registerAdminRoutes(app:FastifyInstance,db:PrismaClient){
 if(process.env.NOVA_ADMIN_EMAIL&&process.env.NOVA_ADMIN_PASSWORD){
  if(process.env.NOVA_ADMIN_PASSWORD.length<16)throw new Error('Admin password must be at least 16 characters');
  const email=process.env.NOVA_ADMIN_EMAIL.toLowerCase();
  if(!await db.adminUser.findUnique({where:{email}}))await db.adminUser.createMany({
   data:{email,passwordHash:await argon2.hash(process.env.NOVA_ADMIN_PASSWORD)},skipDuplicates:true,
  });
 }
 const admin=async(req:any,reply:any)=>{try{await req.jwtVerify();if(req.user.scope!=='admin'||!await db.adminUser.findUnique({where:{id:req.user.sub}}))throw new Error();}catch{return reply.code(401).send({error:'ADMIN_AUTH_REQUIRED'});}};
 app.post('/v1/admin/login',{config:{rateLimit:{max:5,timeWindow:'1 minute'}}},async(req:any,reply)=>{
  const p=z.object({email:z.string().email(),password:z.string().min(1).max(128)}).safeParse(req.body);
  if(!p.success)return reply.code(400).send({error:'INVALID_INPUT'});
  const a=await db.adminUser.findUnique({where:{email:p.data.email.toLowerCase()}});
  if(!a||!await argon2.verify(a.passwordHash,p.data.password))return reply.code(401).send({error:'INVALID_CREDENTIALS'});
  return {accessToken:app.jwt.sign({sub:a.id,scope:'admin'},{expiresIn:'10m'})};
 });
 app.get('/v1/admin/status',{preHandler:admin},async()=>({providers:await db.aiProviderState.findMany(),
   attempts:await db.aiAttempt.findMany({orderBy:{createdAt:'desc'},take:100}),settings:await db.setting.findMany(),
   comments:await db.socialComment.findMany({where:{status:'PENDING_REVIEW'},take:100}),
   reports:await db.report.findMany({where:{status:'OPEN'},take:100}),
   publications:await db.publication.findMany({where:{status:'PENDING_REVIEW'},include:{character:true},take:100})}));
 app.put('/v1/admin/premium-price',{preHandler:admin},async(req:any,reply)=>{
  const p=z.object({tokens:z.number().int().min(1).max(1000)}).strict().safeParse(req.body);if(!p.success)return reply.code(400).send({error:'INVALID_PRICE'});
  await db.$transaction(async tx=>{await tx.setting.upsert({where:{key:'premiumPriceTokens'},create:{key:'premiumPriceTokens',value:p.data.tokens},update:{value:p.data.tokens}});
   await tx.auditLog.create({data:{adminId:req.user.sub,action:'CHANGE_PREMIUM_PRICE',meta:{tokens:p.data.tokens}}});});return {ok:true};
 });
 app.put('/v1/admin/reward-settings',{preHandler:admin},async(req:any,reply)=>{
  const p=z.object({dailyBonusTokens:z.number().int().min(0).max(100).optional(),referralBonusTokens:z.number().int().min(0).max(500).optional()})
   .strict().refine(v=>Object.keys(v).length>0).safeParse(req.body);if(!p.success)return reply.code(400).send({error:'INVALID_REWARD_SETTINGS'});
  await db.$transaction(async tx=>{for(const [key,value] of Object.entries(p.data))await tx.setting.upsert({where:{key},create:{key,value},update:{value}});
   await tx.auditLog.create({data:{adminId:req.user.sub,action:'CHANGE_REWARD_SETTINGS',meta:p.data}});});return {ok:true};
 });
 app.put('/v1/admin/publications/:id',{preHandler:admin},async(req:any,reply)=>{
  const p=z.object({status:z.enum(['PUBLISHED','REJECTED','HIDDEN'])}).strict().safeParse(req.body);if(!p.success)return reply.code(400).send({error:'INVALID_STATUS'});
  await db.$transaction(async tx=>{const publication=await tx.publication.update({where:{id:req.params.id},data:{status:p.data.status,publishedAt:p.data.status==='PUBLISHED'?new Date():null},include:{character:true}});
   await tx.auditLog.create({data:{adminId:req.user.sub,action:'MODERATE_PUBLICATION',target:req.params.id,meta:p.data}});
   await tx.notification.create({data:{userId:publication.character.ownerId,title:'Публикация персонажа',body:publication.character.name+': '+p.data.status}});});return {ok:true};
 });
 app.put('/v1/admin/comments/:id',{preHandler:admin},async(req:any,reply)=>{
  const p=z.object({status:z.enum(['PUBLISHED','REJECTED'])}).strict().safeParse(req.body);if(!p.success)return reply.code(400).send({error:'INVALID_STATUS'});
  await db.$transaction(async tx=>{await tx.socialComment.update({where:{id:req.params.id},data:p.data});await tx.auditLog.create({data:{adminId:req.user.sub,action:'MODERATE_COMMENT',target:req.params.id,meta:p.data}});});return {ok:true};
 });
 app.get('/admin',async(_req,reply)=>reply.type('text/html').send(`<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>NOVA Admin</title><link rel="stylesheet" href="/admin.css"><h1>NOVA · управление</h1><form id="login"><input id="email" type="email" placeholder="Email" required><input id="password" type="password" placeholder="Пароль" required><button>Войти</button></form><form id="price"><label>Цена Premium, NOVA Tokens <input id="tokens" type="number" min="1" max="1000" value="5"></label><button>Сохранить цену</button></form><button id="refresh">Обновить диагностику и очередь</button><form id="moderate"><select id="kind"><option value="publications">Персонаж</option><option value="comments">Комментарий</option></select><input id="target" placeholder="ID из очереди" required><select id="status"><option>PUBLISHED</option><option>REJECTED</option></select><button>Применить решение</button></form><pre id="result"></pre><script src="/admin.js"></script></html>`));
 app.get('/admin.css',async(_req,reply)=>reply.type('text/css').send('body{font:16px system-ui;background:#10121c;color:#eee;max-width:960px;margin:32px auto;padding:20px}input,button{font:inherit;padding:12px;margin:8px;border-radius:8px}button{background:#b391ff;border:0}pre{white-space:pre-wrap;overflow-wrap:anywhere}'));
 app.get('/admin.js',async(_req,reply)=>reply.type('application/javascript').send(`let token='';const el=id=>document.getElementById(id);async function call(path,method='GET',body){const r=await fetch('/v1/admin/'+path,{method,headers:{'Content-Type':'application/json',Authorization:'Bearer '+token},body:body?JSON.stringify(body):undefined});const d=await r.json();el('result').textContent=JSON.stringify(d,null,2);return d;}el('login').onsubmit=async e=>{e.preventDefault();token=(await call('login','POST',{email:el('email').value,password:el('password').value})).accessToken||'';el('password').value='';};el('price').onsubmit=e=>{e.preventDefault();call('premium-price','PUT',{tokens:Number(el('tokens').value)});};el('refresh').onclick=()=>call('status');el('moderate').onsubmit=e=>{e.preventDefault();call(el('kind').value+'/'+encodeURIComponent(el('target').value),'PUT',{status:el('status').value});};`));
}
