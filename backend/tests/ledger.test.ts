import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { balance, credit, debit, refundSpend, InsufficientTokensError } from '../src/ledger.js';

const db = new PrismaClient();
const userIds: string[] = [];

async function makeUser(createWallet = true) {
  const user = await db.user.create({
    data: {
      email: 'ledger-' + randomUUID() + '@example.test',
      passwordHash: 'test-only',
      ...(createWallet ? { wallet: { create: {} } } : {}),
    },
  });
  userIds.push(user.id);
  return user.id;
}

beforeAll(async () => {
  await db.$connect();
});

afterAll(async () => {
  await db.user.deleteMany({ where: { id: { in: userIds } } });
  await db.$disconnect();
});

describe('server-authoritative token ledger', () => {
  it('applies distinct concurrent credits when the wallet does not exist yet', async () => {
    const userId = await makeUser(false);
    const results = await Promise.allSettled(Array.from({ length: 8 }, () => credit(db, {
      userId, bucket: 'BONUS', amount: 10, source: 'test_credit', idempotencyKey: randomUUID(),
    })));
    expect(results.filter(result => result.status === 'rejected')).toEqual([]);
    expect(await balance(db, userId)).toEqual({ purchased: 0, adReward: 0, bonus: 80, total: 80 });
    expect(await db.walletTransaction.count({ where: { userId } })).toBe(8);
  });

  it('applies concurrent retries of the same credit only once', async () => {
    const userId = await makeUser(false);
    const grant = { userId, bucket: 'BONUS' as const, amount: 10, source: 'test_credit', idempotencyKey: randomUUID() };
    const results = await Promise.all(Array.from({ length: 8 }, () => credit(db, grant)));
    expect(results.filter(result => !result.duplicate)).toHaveLength(1);
    expect(new Set(results.map(result => result.transaction.id)).size).toBe(1);
    expect((await balance(db, userId)).total).toBe(10);
  });

  it('credits once for the same idempotency key', async () => {
    const userId = await makeUser();
    const first = await credit(db, {
      userId, bucket: 'PURCHASED', amount: 100,
      source: 'test_purchase', externalId: randomUUID(), idempotencyKey: 'purchase-' + randomUUID(),
    });
    const second = await credit(db, {
      userId, bucket: 'PURCHASED', amount: 100,
      source: 'another_source', idempotencyKey: first.transaction.idempotencyKey,
    });
    expect(first.duplicate).toBe(false);
    expect(second.duplicate).toBe(true);
    expect(await balance(db, userId)).toEqual({
      purchased: 100, adReward: 0, bonus: 0, total: 100,
    });
  });

  it('spends bonus, then ad credits, then purchased credits with no negative balance', async () => {
    const userId = await makeUser();
    for (const [bucket, amount] of [
      ['PURCHASED', 100], ['AD_REWARD', 20], ['BONUS', 10],
    ] as const) {
      await credit(db, {
        userId, bucket, amount, source: 'test_credit',
        idempotencyKey: randomUUID(),
      });
    }
    const key = randomUUID();
    const result = await debit(db, { userId, amount: 45, source: 'chat', idempotencyKey: key });
    expect(result.duplicate).toBe(false);
    expect(result.transactions.map(t => [t.bucket, t.amount])).toEqual([
      ['BONUS', 10], ['AD_REWARD', 20], ['PURCHASED', 15],
    ]);
    const retry = await debit(db, { userId, amount: 45, source: 'chat', idempotencyKey: key });
    expect(retry.duplicate).toBe(true);
    expect(await balance(db, userId)).toEqual({
      purchased: 85, adReward: 0, bonus: 0, total: 85,
    });
    await expect(debit(db, {
      userId, amount: 86, source: 'chat', idempotencyKey: randomUUID(),
    })).rejects.toBeInstanceOf(InsufficientTokensError);
    expect((await balance(db, userId)).total).toBe(85);
  });

  it('refunds a technical failure only once', async () => {
    const userId = await makeUser();
    const first = await refundSpend(db, userId, 15, 'chat:' + randomUUID());
    const next = await refundSpend(db, userId, 15, first.transaction.idempotencyKey.slice(7));
    expect(first.duplicate).toBe(false);
    expect(next.duplicate).toBe(true);
    expect((await balance(db, userId)).bonus).toBe(15);
  });

  it('rejects invalid credit amounts', async () => {
    const userId = await makeUser();
    await expect(credit(db, {
      userId, bucket: 'BONUS', amount: -1, source: 'bad', idempotencyKey: randomUUID(),
    })).rejects.toThrow('INVALID_AMOUNT');
  });
});
