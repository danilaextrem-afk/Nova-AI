import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import rateLimit from '@fastify/rate-limit';
import helmet from '@fastify/helmet';
import { PrismaClient } from '@prisma/client';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import argon2 from 'argon2';
import { z } from 'zod';
import { config, PRODUCT_SEED } from './config.js';
import { balance, credit } from './ledger.js';
import { verifySsv } from './admobSsv.js';
import { verifyAndCreditPurchase } from './billing.js';

const db = new PrismaClient();
const app = Fastify({
  logger: {
    level: 'info',
    // Never log bodies or auth headers: chats are private.
    redact: ['req.headers.authorization', 'req.body', 'res.body'],
  },
  trustProxy: true,
});

await app.register(helmet);
await app.register(rateLimit, { max: 120, timeWindow: '1 minute' });
await app.register(jwt, { secret: config.jwtSecret, sign: { expiresIn: config.accessTtlSec } });

declare module 'fastify' {
  interface FastifyInstance {
    auth: (req: any, reply: any) => Promise<void>;
  }
}
app.decorate('auth', async (req: any, reply: any) => {
  try {
    await req.jwtVerify();
  } catch {
    return reply.code(401).send({ error: 'UNAUTHORIZED' });
  }
});

const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');

async function issueTokens(userId: string, familyId = randomUUID()) {
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

const creds = z.object({ email: z.string().email().max(254), password: z.string().min(10).max(128) });

app.get('/health', async () => ({ ok: true }));

app.post('/v1/auth/register', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req, reply) => {
  const body = creds.safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: 'INVALID_INPUT' });
  const email = body.data.email.toLowerCase();
  if (await db.user.findUnique({ where: { email } })) return reply.code(409).send({ error: 'EMAIL_TAKEN' });
  const passwordHash = await argon2.hash(body.data.password, { type: argon2.argon2id });
  const user = await db.user.create({
    data: { email, passwordHash, profile: { create: { displayName: email.split('@')[0] } }, wallet: { create: {} } },
  });
  return issueTokens(user.id);
});

app.post('/v1/auth/login', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (req, reply) => {
  const body = creds.safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: 'INVALID_INPUT' });
  const user = await db.user.findUnique({ where: { email: body.data.email.toLowerCase() } });
  if (!user || user.status !== 'ACTIVE' || !(await argon2.verify(user.passwordHash, body.data.password))) {
    return reply.code(401).send({ error: 'INVALID_CREDENTIALS' });
  }
  return issueTokens(user.id);
});

// Refresh-token rotation: each token is single-use; reuse revokes the whole family.
app.post('/v1/auth/refresh', async (req, reply) => {
  const body = z.object({ refreshToken: z.string().min(20) }).safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: 'INVALID_INPUT' });
  const s = await db.session.findUnique({ where: { refreshTokenHash: sha256(body.data.refreshToken) } });
  if (!s || s.expiresAt < new Date()) return reply.code(401).send({ error: 'INVALID_REFRESH' });
  if (s.revokedAt) {
    await db.session.updateMany({ where: { familyId: s.familyId, revokedAt: null }, data: { revokedAt: new Date() } });
    return reply.code(401).send({ error: 'REFRESH_REUSED' });
  }
  await db.session.update({ where: { id: s.id }, data: { revokedAt: new Date() } });
  return issueTokens(s.userId, s.familyId);
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
  const body = z.object({ sku: z.string(), purchaseToken: z.string().min(10) }).safeParse(req.body);
  if (!body.success) return reply.code(400).send({ error: 'INVALID_INPUT' });
  try {
    return await verifyAndCreditPurchase(db, { userId: req.user.sub, ...body.data });
  } catch (e: any) {
    if (e.message === 'PLAY_BILLING_NOT_CONFIGURED') return reply.code(503).send({ error: e.message });
    req.log.error({ err: e.message }, 'billing verify failed');
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
    rewardAmount: config.admob.rewardAmount,
    remainingToday: Math.max(0, config.admob.dailyLimit - count),
    secondsUntilNext: Math.max(0, Math.ceil((nextAt - Date.now()) / 1000)),
  };
});

// AdMob SSV callback (called by Google, unauthenticated; the signature is the authentication).
app.get('/v1/ads/ssv', async (req, reply) => {
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
  await db.adReward.create({
    data: { userId: user.id, transactionId: r.transactionId, adUnit: r.adUnit, amount: r.amount },
  });
  await credit(db, {
    userId: user.id,
    bucket: 'AD_REWARD',
    amount: r.amount,
    source: 'admob_ssv',
    externalId: r.transactionId,
    idempotencyKey: `ssv:${r.transactionId}`,
  });
  return { credited: true };
});

for (const [sku, tokens] of Object.entries(PRODUCT_SEED)) {
  await db.paymentProduct.upsert({ where: { sku }, create: { sku, tokens }, update: {} });
}

await app.listen({ port: config.port, host: '0.0.0.0' });
