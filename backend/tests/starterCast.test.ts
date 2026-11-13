import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { PRODUCT_SEED } from '../src/config.js';
import { buildApp } from '../src/server.js';
import { seedStarterCast, STARTER_CAST } from '../src/starterCast.js';

const db = new PrismaClient();
const accountEmail = 'nova-studio-catalog@nova.invalid';

beforeAll(async () => { await db.$connect(); });
afterAll(async () => {
  const system = await db.user.findUnique({ where: { email: accountEmail } });
  if (system) await db.user.delete({ where: { id: system.id } });
  await db.$disconnect();
});

describe('Original NOVA starter characters', () => {
  it('contains 12 original adults with balanced female/male cast', () => {
    expect(STARTER_CAST).toHaveLength(12);
    expect(STARTER_CAST.filter(x => x.gender === 'woman')).toHaveLength(6);
    expect(STARTER_CAST.filter(x => x.gender === 'man')).toHaveLength(6);
    expect(new Set(STARTER_CAST.map(x => x.id)).size).toBe(12);
    expect(STARTER_CAST.every(x => x.name && x.greeting && x.scenario && x.personality)).toBe(true);
  });

  it('seeds all 12 moderated public library characters once', async () => {
    await db.user.deleteMany({ where: { email: accountEmail } });
    await Promise.all(Array.from({ length: 4 }, () => seedStarterCast(db)));
    const system = await db.user.findUniqueOrThrow({ where: { email: accountEmail } });
    expect(system.status).toBe('BLOCKED');
    const list = await db.character.findMany({ where: { ownerId: system.id }, include: { publication: true } });
    expect(list).toHaveLength(12);
    expect(list.every(c => c.isLibrary && !c.isHidden && c.publication?.status === 'PUBLISHED')).toBe(true);
  });

  it('does not override an explicit moderation decision on a rerun', async () => {
    const id = STARTER_CAST[0].id;
    await db.publication.update({ where: { characterId: id }, data: { status: 'HIDDEN' } });
    await seedStarterCast(db);
    const status = await db.publication.findUnique({ where: { characterId: id } });
    expect(status?.status).toBe('HIDDEN');
  });

  it('starts multiple instances without duplicating the initial admin or payment products', async () => {
    const email = 'startup-' + randomUUID() + '@test.invalid';
    const skus = Object.keys(PRODUCT_SEED);
    const existing = await db.paymentProduct.findMany({ where: { sku: { in: skus } } });
    const newSkus = skus.filter(sku => !existing.some(product => product.sku === sku));
    await db.paymentProduct.deleteMany({ where: { sku: { in: skus } } });
    vi.stubEnv('NOVA_ADMIN_EMAIL', email);
    vi.stubEnv('NOVA_ADMIN_PASSWORD', 'startup-fixture-admin-password');
    try {
      const results = await Promise.allSettled(Array.from({ length: 3 }, () => buildApp()));
      for (const result of results) if (result.status === 'fulfilled') await result.value.close();
      expect(results.map(result => result.status)).toEqual(Array(3).fill('fulfilled'));
      expect(await db.adminUser.count({ where: { email } })).toBe(1);
      expect(await db.paymentProduct.count({ where: { sku: { in: skus } } })).toBe(skus.length);
    } finally {
      vi.unstubAllEnvs();
      await db.adminUser.deleteMany({ where: { email } });
      await db.paymentProduct.deleteMany({ where: { sku: { in: newSkus } } });
      for (const { sku, ...data } of existing) {
        await db.paymentProduct.upsert({ where: { sku }, create: { sku, ...data }, update: data });
      }
    }
  });
});
