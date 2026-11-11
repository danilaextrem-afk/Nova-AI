import { Prisma, PrismaClient, TokenBucket, TxType } from '@prisma/client';

export class InsufficientTokensError extends Error {
  constructor() {
    super('INSUFFICIENT_TOKENS');
  }
}

const CREDIT_TYPE: Record<TokenBucket, TxType> = {
  PURCHASED: 'CREDIT_PURCHASE',
  AD_REWARD: 'CREDIT_AD_REWARD',
  BONUS: 'CREDIT_BONUS',
};

const COLUMN: Record<TokenBucket, 'purchased' | 'adReward' | 'bonus'> = {
  PURCHASED: 'purchased',
  AD_REWARD: 'adReward',
  BONUS: 'bonus',
};

type Tx = Prisma.TransactionClient;

async function lockWallet(tx: Tx, userId: string) {
  await tx.walletAccount.upsert({ where: { userId }, create: { userId }, update: {} });
  const rows = await tx.$queryRaw<{ purchased: number; adReward: number; bonus: number }[]>`
    SELECT "purchased", "adReward", "bonus" FROM "WalletAccount" WHERE "userId" = ${userId} FOR UPDATE`;
  return rows[0];
}

/**
 * Atomic, idempotent credit. A repeated idempotencyKey returns the original
 * transaction and changes no balance.
 */
export async function credit(
  db: PrismaClient,
  p: {
    userId: string;
    bucket: TokenBucket;
    amount: number;
    source: string;
    externalId?: string;
    idempotencyKey: string;
    type?: TxType;
  },
) {
  if (!Number.isInteger(p.amount) || p.amount <= 0) throw new Error('INVALID_AMOUNT');
  try {
    return await db.$transaction(async (tx) => {
      const dup = await tx.walletTransaction.findUnique({ where: { idempotencyKey: p.idempotencyKey } });
      if (dup) return { transaction: dup, duplicate: true };
      await lockWallet(tx, p.userId);
      const transaction = await tx.walletTransaction.create({
        data: {
          userId: p.userId,
          type: p.type ?? CREDIT_TYPE[p.bucket],
          bucket: p.bucket,
          source: p.source,
          amount: p.amount,
          externalId: p.externalId,
          idempotencyKey: p.idempotencyKey,
        },
      });
      await tx.walletAccount.update({
        where: { userId: p.userId },
        data: { [COLUMN[p.bucket]]: { increment: p.amount } },
      });
      return { transaction, duplicate: false };
    });
  } catch (e) {
    // Two concurrent requests with the same key: the unique index decides the winner.
    if (e instanceof Prisma.PrismaClientKnownRequestError && e.code === 'P2002') {
      const dup = await db.walletTransaction.findUnique({ where: { idempotencyKey: p.idempotencyKey } });
      if (dup) return { transaction: dup, duplicate: true };
    }
    throw e;
  }
}

/**
 * Atomic debit. Spends BONUS first, then AD_REWARD, then PURCHASED.
 * Throws InsufficientTokensError instead of going negative.
 */
export async function debit(
  db: PrismaClient,
  p: { userId: string; amount: number; source: string; idempotencyKey: string },
) {
  if (!Number.isInteger(p.amount) || p.amount <= 0) throw new Error('INVALID_AMOUNT');
  return db.$transaction(async (tx) => {
    const dup = await tx.walletTransaction.findUnique({ where: { idempotencyKey: p.idempotencyKey } });
    if (dup) return { transactions: [dup], duplicate: true };
    const w = await lockWallet(tx, p.userId);
    if (w.purchased + w.adReward + w.bonus < p.amount) throw new InsufficientTokensError();

    let left = p.amount;
    const transactions = [];
    const order: [TokenBucket, number][] = [
      ['BONUS', w.bonus],
      ['AD_REWARD', w.adReward],
      ['PURCHASED', w.purchased],
    ];
    for (const [bucket, have] of order) {
      const take = Math.min(left, have);
      if (take === 0) continue;
      await tx.walletAccount.update({
        where: { userId: p.userId },
        data: { [COLUMN[bucket]]: { decrement: take } },
      });
      transactions.push(
        await tx.walletTransaction.create({
          data: {
            userId: p.userId,
            type: 'DEBIT_SPEND',
            bucket,
            source: p.source,
            amount: take,
            idempotencyKey: `${p.idempotencyKey}:${bucket}`,
          },
        }),
      );
      left -= take;
      if (left === 0) break;
    }
    // Remember the umbrella key so a retry of the whole debit is a no-op.
    await tx.walletTransaction.update({
      where: { id: transactions[0].id },
      data: { idempotencyKey: p.idempotencyKey },
    });
    return { transactions, duplicate: false };
  });
}

/** Refund a spend after a confirmed technical failure. Refunds go to BONUS so they never expire into cash value. */
export function refundSpend(db: PrismaClient, userId: string, amount: number, spendKey: string) {
  return credit(db, {
    userId,
    bucket: 'BONUS',
    amount,
    source: 'refund',
    type: 'CREDIT_REFUND',
    idempotencyKey: `refund:${spendKey}`,
  });
}

export async function balance(db: PrismaClient, userId: string) {
  const w = await db.walletAccount.findUnique({ where: { userId } });
  const purchased = w?.purchased ?? 0;
  const adReward = w?.adReward ?? 0;
  const bonus = w?.bonus ?? 0;
  return { purchased, adReward, bonus, total: purchased + adReward + bonus };
}
