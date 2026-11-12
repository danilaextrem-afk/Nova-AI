import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import rateLimit from '@fastify/rate-limit';
import helmet from '@fastify/helmet';
import { PrismaClient } from '@prisma/client';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import argon2 from 'argon2';
import { z } from 'zod';
import { config, PRODUCT_SEED } from './config.js';
import { balance } from './ledger.js';
import { verifySsv } from './admobSsv.js';
import { verifyAndCreditPurchase } from './billing.js';
import { registerCharacterRoutes } from './characters.js';
import { registerSocialRoutes } from './socialRoutes.js';
import { registerStoryRoutes } from './storyRoutes.js';
import { registerProductRoutes } from './productRoutes.js';
import { registerAdminRoutes } from './adminRoutes.js';
import { registerChatRoutes } from './chatRoutes.js';
import { registerRewardRoutes } from './rewardRoutes.js';
import { registerArtRoutes } from './artRoutes.js';
import { seedStarterCast } from './starterCast.js';

declare module 'fastify' {
  interface FastifyInstance {
    auth: (req: any, reply: any) => Promise<void>;
  }
}

export async function buildApp(db:PrismaClient = new PrismaClient()) {
const app = Fastify({
  disableRequestLogging: true,
  bodyLimit: 65536,
  logger: {
    level: 'info',
    // Never log bodies or auth headers: chats are private.
    redact: ['req.headers.authorization', 'req.body', 'res.body'],
  },
  trustProxy: process.env.TRUST_PROXY_CIDR || false,
});

await app.register(helmet);
// Behind Render (Cloudflare edge) every request arrives from the platform proxy, so the socket IP is shared by
// all users. NOVA_CLIENT_IP_HEADER=true-client-ip keys rate limits on the edge-set client IP instead. Do not
// use X-Forwarded-For for this: Render appends to it without stripping client-supplied values.
const clientIpHeader = (process.env.NOVA_CLIENT_IP_HEADER || '').toLowerCase().trim();
const clientKey = (req: { headers: Record<string, unknown>; ip: string }) => {
  const raw = clientIpHeader ? req.headers[clientIpHeader] : undefined;
  const value = typeof raw === 'string' ? raw.split(',')[0].trim() : '';
  return /^[0-9a-fA-F:.]{3,45}$/.test(value) ? value : req.ip;
};
await app.register(rateLimit, { max: 120, timeWindow: '1 minute', keyGenerator: clientKey });
await app.register(jwt, { secret: config.jwtSecret, sign: { expiresIn: config.accessTtlSec, algorithm:'HS256' }, verify:{algorithms:['HS256']} });

app.decorate('auth', async (req: any, reply: any) => {
  try {
    await req.jwtVerify();
    if(req.user.scope==='admin')return reply.code(401).send({error:'UNAUTHORIZED'});
    const u=await db.user.findUnique({where:{id:req.user.sub},select:{status:true}});
    if(!u||u.status!=='ACTIVE')return reply.code(401).send({error:'UNAUTHORIZED'});
  } catch {
    return reply.code(401).send({ error: 'UNAUTHORIZED' });
  }
});

const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');

async function issueTokens(userId: string, familyId: string = randomUUID()) {
  const refresh = randomBytes(48).toString('base64url');
  await db.session.create({
    data: {
      userId,
      familyId,
      refreshTokenHash: sha256(refresh),
      expiresAt: new Date(Date.now() + config.refreshTtlSec * 1000),
    },
  });
  return { accessToken: app.jwt.sign({ sub: userId }), refreshToken: refresh };
}

const creds = z.object({ email: z.string().trim().email().max(254), password: z.string().min(10).max(128), adultConfirmed:z.boolean().optional() });

/** Validation stays strict; the error code only says which field to fix so the app can explain it. */
function credsError(error: z.ZodError): string {
  const issue = error.issues[0];
  const field = issue?.path[0];
  if (field === 'email') return 'EMAIL_INVALID';
  if (field === 'password') return issue.code === 'too_big' ? 'PASSWORD_TOO_LONG' : issue.code === 'too_small' ? 'PASSWORD_TOO_SHORT' : 'PASSWORD_INVALID';
  return 'INVALID_INPUT';
}

// Render sets RENDER_GIT_COMMIT; exposing the short SHA lets the live check confirm which build is serving.
app.get('/health', async () => ({ ok: true, commit: (process.env.RENDER_GIT_COMMIT || '').slice(0, 7) || null }));
app.get('/ready',async(_req,reply)=>{try{await db.$queryRaw`SELECT 1`;return {ok:true};}catch{return reply.code(503).send({ok:false});}});
app.setErrorHandler((error,req,reply)=>{
 const status=(error as any).statusCode;
 if(Number.isInteger(status)&&status>=400&&status<500)return reply.code(status).send({error:status===429?'RATE_LIMITED':status===413?'BODY_TOO_LARGE':'INVALID_REQUEST'});
 req.log.error({code:(error as any).code||'INTERNAL'},'Request failed');return reply.code(500).send({error:'INTERNAL_ERROR'});
});
app.post('/v1/auth/logout',{preHandler:app.auth},async(req:any)=>{await db.session.updateMany({where:{userId:req.user.sub},data:{revokedAt:new Date()}});return {ok:true};});

app.post('/v1/auth/register', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req, reply) => {
  const body = creds.safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: credsError(body.error) });
  if(body.data.adultConfirmed!==true)return reply.code(400).send({error:'ADULT_CONFIRMATION_REQUIRED'});
  const email = body.data.email.toLowerCase();
  if (await db.user.findUnique({ where: { email } })) return reply.code(409).send({ error: 'EMAIL_TAKEN' });
  const passwordHash = await argon2.hash(body.data.password, { type: argon2.argon2id });
  const user = await db.user.create({
    data: { email, passwordHash, profile: { create: { displayName: email.split('@')[0] } }, wallet: { create: {} } },
  });
  return issueTokens(user.id);
});

// Per-account brute-force guard (the IP limit alone does not stop a distributed attack on one account):
// 8 failed attempts per email within 15 minutes lock that email's login for the rest of the window.
const failedLogins = new Map<string, { count: number; until: number }>();
const LOGIN_WINDOW_MS = 15 * 60_000, LOGIN_MAX_FAILURES = 8;
// Unknown emails still run one argon2 verification so response timing does not reveal registered accounts.
const dummyHash = await argon2.hash(randomBytes(16).toString('hex'), { type: argon2.argon2id });
app.post('/v1/auth/login', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req, reply) => {
  const body = creds.safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: credsError(body.error) });
  const email = body.data.email.toLowerCase();
  const now = Date.now();
  const entry = failedLogins.get(email);
  if (entry && entry.until > now && entry.count >= LOGIN_MAX_FAILURES) {
    reply.header('Retry-After', Math.ceil((entry.until - now) / 1000));
    return reply.code(429).send({ error: 'TOO_MANY_LOGIN_ATTEMPTS' });
  }
  const user = await db.user.findUnique({ where: { email } });
  const valid = await argon2.verify(user?.passwordHash ?? dummyHash, body.data.password).catch(() => false);
  if (!user || user.status !== 'ACTIVE' || !valid) {
    const current = entry && entry.until > now ? entry : { count: 0, until: now + LOGIN_WINDOW_MS };
    current.count++; failedLogins.set(email, current);
    if (failedLogins.size > 50_000) for (const [k, v] of failedLogins) if (v.until <= now) failedLogins.delete(k);
    return reply.code(401).send({ error: 'INVALID_CREDENTIALS' });
  }
  failedLogins.delete(email);
  return issueTokens(user.id);
});

// Refresh-token rotation: each token is single-use; reuse revokes the whole family.
app.post('/v1/auth/refresh', async (req, reply) => {
  const body = z.object({ refreshToken: z.string().min(20) }).safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: 'INVALID_INPUT' });
  const refresh=randomBytes(48).toString('base64url');
  const result=await db.$transaction(async tx=>{
    const hash=sha256(body.data.refreshToken);
    await tx.$queryRaw`SELECT id FROM "Session" WHERE "refreshTokenHash" = ${hash} FOR UPDATE`;
    const s=await tx.session.findUnique({where:{refreshTokenHash:hash},include:{user:{select:{status:true}}}});
    if(!s||s.expiresAt<new Date()||s.user.status!=='ACTIVE')return null;
    if(s.revokedAt){await tx.session.updateMany({where:{familyId:s.familyId},data:{revokedAt:new Date()}});return null;}
    await tx.session.update({where:{id:s.id},data:{revokedAt:new Date()}});
    await tx.session.create({data:{userId:s.userId,familyId:s.familyId,refreshTokenHash:sha256(refresh),expiresAt:new Date(Date.now()+config.refreshTtlSec*1000)}});
    return s.userId;
  });
  if(!result)return reply.code(401).send({error:'INVALID_REFRESH'});
  return {accessToken:app.jwt.sign({sub:result}),refreshToken:refresh};
});

app.get('/v1/wallet', { preHandler: app.auth }, async (req: any) => balance(db, req.user.sub));

app.get('/v1/wallet/transactions', { preHandler: app.auth }, async (req: any) => {
  const q = z.object({ cursor: z.string().optional(), limit: z.coerce.number().int().min(1).max(100).default(30) }).parse(req.query);
  const items = await db.walletTransaction.findMany({
    where: { userId: req.user.sub }, // IDOR-safe: always scoped to the caller
    orderBy: { createdAt: 'desc' },
    take: q.limit,
    ...(q.cursor ? { cursor: { id: q.cursor }, skip: 1 } : {}),
  });
  return { items, nextCursor: items.length === q.limit ? items[items.length - 1].id : null };
});

app.post('/v1/billing/verify', { preHandler: app.auth }, async (req: any, reply) => {
  if(process.env.NOVA_MONETIZATION_ENABLED!=='true')return reply.code(503).send({error:'BILLING_DISABLED'});
  const body = z.object({ sku: z.string(), purchaseToken: z.string().min(10) }).safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: 'INVALID_INPUT' });
  try {
    return await verifyAndCreditPurchase(db, { userId: req.user.sub, ...body.data });
  } catch (e: any) {
    if (e.message === 'PLAY_BILLING_NOT_CONFIGURED') return reply.code(503).send({ error: e.message });
    req.log.error({ code:'PLAY_VERIFY_FAILED' }, 'billing verify failed');
    return reply.code(502).send({ error: 'PLAY_VERIFY_FAILED' });
  }
});

// Status for the "watch ad" screen: remaining daily views and cooldown.
app.get('/v1/ads/status', { preHandler: app.auth }, async (req: any) => {
  const since = new Date(Date.now() - 24 * 3600_000);
  const [count, last] = await Promise.all([
    db.adReward.count({ where: { userId: req.user.sub, createdAt: { gte: since } } }),
    db.adReward.findFirst({ where: { userId: req.user.sub }, orderBy: { createdAt: 'desc' } }),
  ]);
  const nextAt = last ? last.createdAt.getTime() + config.admob.minIntervalSec * 1000 : 0;
  return {
    configured:process.env.NOVA_MONETIZATION_ENABLED==='true'&&!!config.admob.adUnit,
    adUnit:process.env.NOVA_MONETIZATION_ENABLED==='true'?config.admob.adUnit:null,
    userId:req.user.sub,
    rewardAmount: config.admob.rewardAmount,
    remainingToday: Math.max(0, config.admob.dailyLimit - count),
    secondsUntilNext: Math.max(0, Math.ceil((nextAt - Date.now()) / 1000)),
  };
});

// AdMob SSV callback (called by Google, unauthenticated; the signature is the authentication).
app.get('/v1/ads/ssv', async (req, reply) => {
  // Fail closed until an app-specific rewarded unit is configured.
  if (process.env.NOVA_MONETIZATION_ENABLED!=='true'||!config.admob.adUnit || !Number.isSafeInteger(config.admob.rewardAmount) || config.admob.rewardAmount <= 0) {
    return reply.code(503).send({ error: 'ADMOB_NOT_CONFIGURED' });
  }
  const rawQuery = req.raw.url!.split('?')[1] ?? '';
  const r = await verifySsv(rawQuery, {
    expectedAdUnit: config.admob.adUnit || undefined,
    expectedAmount: config.admob.rewardAmount,
    maxAgeMs: config.admob.maxAgeMs,
  });
  if (!r.ok) {
    req.log.warn({ reason: r.reason }, 'ssv rejected');
    return reply.code(r.reason === 'BAD_SIGNATURE' ? 403 : 400).send({ error: r.reason });
  }
  const user = await db.user.findUnique({ where: { id: r.userId } });
  if (!user || user.status !== 'ACTIVE') return reply.code(400).send({ error: 'UNKNOWN_USER' });

  const since = new Date(Date.now() - 24 * 3600_000);
  const count = await db.adReward.count({ where: { userId: user.id, createdAt: { gte: since } } });
  if (count >= config.admob.dailyLimit) return reply.code(200).send({ credited: false, reason: 'DAILY_LIMIT' });

  // AdReward.transactionId is UNIQUE and the ledger key is derived from it: a replay credits nothing.
  const dup = await db.adReward.findUnique({ where: { transactionId: r.transactionId } });
  if (dup) return { credited: false, reason: 'DUPLICATE' };
  // Record the verified impression and its wallet credit in ONE database transaction.
  // If either write fails, neither is committed. A unique transactionId blocks replays.
  try {
    const credited = await db.$transaction(async (tx) => {
      const existing = await tx.adReward.findUnique({ where: { transactionId: r.transactionId } });
      if (existing) return false;
      // Serialize rewards for the same user to enforce the daily cap under concurrency.
      await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${user.id} FOR UPDATE`;
      const recentCount = await tx.adReward.count({
        where: { userId: user.id, createdAt: { gte: since } },
      });
      if (recentCount >= config.admob.dailyLimit) return false;
      const lastReward = await tx.adReward.findFirst({
        where: { userId: user.id }, orderBy: { createdAt: 'desc' },
      });
      if (lastReward && Date.now() - lastReward.createdAt.getTime() < config.admob.minIntervalSec * 1000) {
        return false;
      }
      await tx.walletAccount.upsert({ where: { userId: user.id }, create: { userId: user.id }, update: {} });
      await tx.adReward.create({
        data: { userId: user.id, transactionId: r.transactionId, adUnit: r.adUnit, amount: r.amount },
      });
      await tx.walletTransaction.create({
        data: {
          userId: user.id, type: 'CREDIT_AD_REWARD', bucket: 'AD_REWARD',
          source: 'admob_ssv', amount: r.amount, externalId: r.transactionId,
          idempotencyKey: `ssv:${r.transactionId}`,
        },
      });
      await tx.walletAccount.update({
        where: { userId: user.id }, data: { adReward: { increment: r.amount } },
      });
      return true;
    });
    return { credited, ...(!credited ? { reason: 'DUPLICATE_LIMIT_OR_COOLDOWN' } : {}) };
  } catch (e: any) {
    if (e?.code === 'P2002') return { credited: false, reason: 'DUPLICATE' };
    req.log.error({ code:'REWARD_RETRYABLE' }, 'ssv transaction failed');
    return reply.code(503).send({ error: 'REWARD_RETRYABLE' });
  }
});

await registerCharacterRoutes(app, db);
await registerChatRoutes(app, db);
await registerStoryRoutes(app,db);
await registerSocialRoutes(app,db);
await registerProductRoutes(app,db);
await registerAdminRoutes(app,db);
await registerRewardRoutes(app,db);
await registerArtRoutes(app,db);

for (const [sku, tokens] of Object.entries(PRODUCT_SEED)) {
  await db.paymentProduct.upsert({ where: { sku }, create: { sku, tokens }, update: {} });
}

await seedStarterCast(db);

app.addHook('onClose',async()=>{await db.$disconnect();});
return app;
}
