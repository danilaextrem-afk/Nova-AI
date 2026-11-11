import type { FastifyInstance } from 'fastify';
import { Prisma, type PrismaClient } from '@prisma/client';
import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import { credit, creditInTransaction } from './ledger.js';

/**
 * Daily check-in bonus and referral rewards. Every credit goes through the idempotent ledger, so a
 * retried or concurrent request can never pay twice. Amounts are owner settings (0 disables).
 */
export const REFERRAL_REQUIRED_TURNS = 3; // Invitee must really use the chat before anyone is paid.
export const REFERRAL_APPLY_WINDOW_DAYS = 7;
export const REFERRAL_MONTHLY_CAP = 30; // Rewarded invites per inviter per 30 days.
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // No 0/O/1/I.

async function setting(db: PrismaClient | Prisma.TransactionClient, key: string, envName: string, fallback: number) {
  const row = await db.setting.findUnique({ where: { key } });
  const value = Number(row?.value ?? process.env[envName] ?? fallback);
  return Number.isInteger(value) && value >= 0 && value <= 1000 ? value : fallback;
}
export const dailyBonusTokens = (db: PrismaClient) => setting(db, 'dailyBonusTokens', 'NOVA_DAILY_BONUS_TOKENS', 2);
export const referralBonusTokens = (db: PrismaClient | Prisma.TransactionClient) =>
  setting(db, 'referralBonusTokens', 'NOVA_REFERRAL_BONUS_TOKENS', 20);

/** Calendar day for the bonus, in the app's main time zone (Moscow by default). */
export function rewardDay(now = Date.now()) {
  const offsetMin = Number(process.env.NOVA_REWARD_UTC_OFFSET_MINUTES ?? 180);
  const local = new Date(now + offsetMin * 60000);
  const day = local.toISOString().slice(0, 10);
  const nextAt = new Date(Date.UTC(local.getUTCFullYear(), local.getUTCMonth(), local.getUTCDate() + 1) - offsetMin * 60000);
  return { day, nextAt };
}

function newCode() {
  const bytes = randomBytes(8);
  return Array.from(bytes, b => CODE_ALPHABET[b % CODE_ALPHABET.length]).join('');
}
async function ensureCode(db: PrismaClient, userId: string): Promise<string> {
  for (let i = 0; i < 5; i++) {
    const user = await db.user.findUnique({ where: { id: userId }, select: { referralCode: true } });
    if (user?.referralCode) return user.referralCode;
    try {
      await db.user.updateMany({ where: { id: userId, referralCode: null }, data: { referralCode: newCode() } });
    } catch (e) {
      if (!(e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002')) throw e; // Collision: retry.
    }
  }
  throw new Error('REFERRAL_CODE_UNAVAILABLE');
}

/**
 * Pays both sides once the invitee has completed enough real chat turns. Safe to call any number of
 * times from anywhere: rows are locked, the ledger keys are idempotent and the reward is marked paid
 * in the same transaction.
 */
export async function settleReferral(db: PrismaClient, inviteeId: string) {
  const amount = await referralBonusTokens(db);
  if (!amount) return false;
  return db.$transaction(async tx => {
    await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${inviteeId} FOR UPDATE`;
    const invitee = await tx.user.findUnique({ where: { id: inviteeId } });
    if (!invitee?.referredById || invitee.referralRewardedAt || invitee.status !== 'ACTIVE') return false;
    const turns = await tx.chatRequest.count({ where: { userId: inviteeId, status: 'SUCCEEDED' } });
    if (turns < REFERRAL_REQUIRED_TURNS) return false;
    await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${invitee.referredById} FOR UPDATE`;
    const inviter = await tx.user.findUnique({ where: { id: invitee.referredById } });
    if (!inviter || inviter.status !== 'ACTIVE') return false;
    const recent = await tx.user.count({ where: { referredById: inviter.id,
      referralRewardedAt: { gt: new Date(Date.now() - 30 * 86400000) } } });
    if (recent >= REFERRAL_MONTHLY_CAP) return false; // Stays pending; paid when the window frees up.
    await creditInTransaction(tx, { userId: inviteeId, bucket: 'BONUS', amount, source: 'referral_welcome',
      idempotencyKey: 'referral:invitee:' + inviteeId });
    await creditInTransaction(tx, { userId: inviter.id, bucket: 'BONUS', amount, source: 'referral_invite',
      idempotencyKey: 'referral:inviter:' + inviteeId });
    await tx.user.update({ where: { id: inviteeId }, data: { referralRewardedAt: new Date() } });
    await tx.notification.create({ data: { userId: inviter.id, title: 'Друг присоединился',
      body: `По твоему приглашению начислено ${amount} NOVA Tokens.` } });
    return true;
  });
}

export async function registerRewardRoutes(app: FastifyInstance, db: PrismaClient) {
  const auth = { preHandler: app.auth, config: { rateLimit: { max: 20, timeWindow: '1 minute' } } };

  app.get('/v1/rewards/daily', auth, async (req: any) => {
    const { day, nextAt } = rewardDay();
    const amount = await dailyBonusTokens(db);
    const claimed = !!await db.walletTransaction.findUnique({ where: { idempotencyKey: `daily:${req.user.sub}:${day}` } });
    return { enabled: amount > 0, amount, claimedToday: claimed, day, nextAt };
  });

  app.post('/v1/rewards/daily', auth, async (req: any, reply) => {
    const { day, nextAt } = rewardDay();
    const amount = await dailyBonusTokens(db);
    if (!amount) return reply.code(403).send({ error: 'DAILY_BONUS_DISABLED' });
    const user = await db.user.findUnique({ where: { id: req.user.sub } });
    if (!user || user.status !== 'ACTIVE') return reply.code(403).send({ error: 'ACCOUNT_NOT_ACTIVE' });
    const result = await credit(db, { userId: req.user.sub, bucket: 'BONUS', amount, source: 'daily_bonus',
      idempotencyKey: `daily:${req.user.sub}:${day}` });
    // A repeat on the same day is not an error for the app; it simply reports that nothing new was paid.
    return { granted: !result.duplicate, amount: result.duplicate ? 0 : amount, day, nextAt };
  });

  app.get('/v1/referrals', auth, async (req: any) => {
    const userId = req.user.sub as string;
    await settleReferral(db, userId).catch(error => req.log.warn({ err: error }, 'Referral settle deferred'));
    const [code, me, invited, rewarded, turns, amount] = await Promise.all([
      ensureCode(db, userId), db.user.findUnique({ where: { id: userId } }),
      db.user.count({ where: { referredById: userId } }),
      db.user.count({ where: { referredById: userId, referralRewardedAt: { not: null } } }),
      db.chatRequest.count({ where: { userId, status: 'SUCCEEDED' } }), referralBonusTokens(db),
    ]);
    const canApply = !me?.referredById && Date.now() - me!.createdAt.getTime() < REFERRAL_APPLY_WINDOW_DAYS * 86400000;
    return { code, bonusTokens: amount, invited, rewarded, requiredTurns: REFERRAL_REQUIRED_TURNS,
      myInvite: me?.referredById ? { rewarded: !!me.referralRewardedAt, turns: Math.min(turns, REFERRAL_REQUIRED_TURNS) } : null,
      canApplyCode: canApply };
  });

  app.post('/v1/referrals/apply', { ...auth, config: { rateLimit: { max: 5, timeWindow: '1 minute' } } }, async (req: any, reply) => {
    const p = z.object({ code: z.string().trim().toUpperCase().regex(/^[A-Z0-9]{8}$/) }).strict().safeParse(req.body);
    if (!p.success) return reply.code(400).send({ error: 'INVALID_CODE' });
    const userId = req.user.sub as string;
    const outcome = await db.$transaction(async tx => {
      await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${userId} FOR UPDATE`;
      const me = await tx.user.findUnique({ where: { id: userId } });
      if (!me) return 'NOT_FOUND';
      if (me.referredById) return 'ALREADY_APPLIED';
      if (Date.now() - me.createdAt.getTime() >= REFERRAL_APPLY_WINDOW_DAYS * 86400000) return 'APPLY_WINDOW_CLOSED';
      const inviter = await tx.user.findUnique({ where: { referralCode: p.data.code } });
      if (!inviter || inviter.status !== 'ACTIVE') return 'CODE_NOT_FOUND';
      if (inviter.id === userId) return 'OWN_CODE';
      if (inviter.referredById === userId) return 'CIRCULAR_REFERRAL';
      await tx.user.update({ where: { id: userId }, data: { referredById: inviter.id } });
      return 'APPLIED';
    });
    if (outcome !== 'APPLIED') return reply.code(outcome === 'CODE_NOT_FOUND' || outcome === 'NOT_FOUND' ? 404 : 409).send({ error: outcome });
    const paid = await settleReferral(db, userId);
    return { applied: true, rewarded: paid, requiredTurns: REFERRAL_REQUIRED_TURNS };
  });
}
