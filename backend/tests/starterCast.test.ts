import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { PrismaClient } from '@prisma/client';
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
    await seedStarterCast(db);
    await seedStarterCast(db);
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
});
