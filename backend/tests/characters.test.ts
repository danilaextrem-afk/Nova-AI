import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { registerCharacterRoutes } from '../src/characters.js';

const db = new PrismaClient();
const beforePublicationWrite = vi.fn();
const routeDb = db.$extends({ query: { publication: {
  upsert({ args, query }) {
    beforePublicationWrite();
    return query(args);
  },
} } });
const app = Fastify();
const users: string[] = [];
let owner: string;
let stranger: string;
let characterId: string;

async function createTestUser() {
  const user = await db.user.create({
    data: { email: 'characters-' + randomUUID() + '@example.test', passwordHash: 'test-only' },
  });
  users.push(user.id);
  return user.id;
}

function headers(userId: string) {
  return { authorization: 'Bearer ' + app.jwt.sign({ sub: userId }) };
}

beforeAll(async () => {
  await db.$connect();
  await app.register(jwt, { secret: 'unit-tests-only' });
  app.decorate('auth', async (req: any, reply: any) => {
    try { await req.jwtVerify(); } catch { return reply.code(401).send({ error: 'UNAUTHORIZED' }); }
  });
  await registerCharacterRoutes(app, routeDb as unknown as PrismaClient);
  await app.ready();
  owner = await createTestUser();
  stranger = await createTestUser();
});

afterAll(async () => {
  await app.close();
  await db.user.deleteMany({ where: { id: { in: users } } });
  await db.$disconnect();
});

describe('characters and moderation safety', () => {
  it('requires login before creating characters', async () => {
    const res = await app.inject({
      method: 'POST', url: '/v1/characters',
      payload: { name: 'Explorer', greeting: 'Hello', category: 'adventure' },
    });
    expect(res.statusCode).toBe(401);
  });

  it('creates a private character and excludes it from the public catalog', async () => {
    const res = await app.inject({
      method: 'POST', url: '/v1/characters', headers: headers(owner),
      payload: { name: 'Explorer', greeting: 'Hello', category: 'adventure', tags: ['scifi'] },
    });
    expect(res.statusCode).toBe(201);
    characterId = res.json().character.id;
    const catalog = await app.inject({
      method: 'GET', url: '/v1/characters/catalog', headers: headers(stranger),
    });
    expect(catalog.json().items.some((c: any) => c.id === characterId)).toBe(false);
  });

  it('prevents another user from viewing or editing a private character', async () => {
    const hidden = await app.inject({
      method: 'GET', url: '/v1/characters/' + characterId, headers: headers(stranger),
    });
    expect(hidden.statusCode).toBe(404);
    const edit = await app.inject({
      method: 'PATCH', url: '/v1/characters/' + characterId, headers: headers(stranger),
      payload: { name: 'Stolen' },
    });
    expect(edit.statusCode).toBe(404);
  });

  it('places published characters in the review queue, not the catalog', async () => {
    const submit = await app.inject({
      method: 'POST', url: '/v1/characters/' + characterId + '/publish', headers: headers(owner),
    });
    expect(submit.statusCode).toBe(200);
    expect(submit.json().publicationStatus).toBe('PENDING_REVIEW');
    const catalog = await app.inject({
      method: 'GET', url: '/v1/characters/catalog', headers: headers(stranger),
    });
    expect(catalog.json().items.some((c: any) => c.id === characterId)).toBe(false);
  });

  it('removes approval when the creator edits the character', async () => {
    await db.publication.update({ where: { characterId }, data: { status: 'PUBLISHED' } });
    const catalogBefore = await app.inject({
      method: 'GET', url: '/v1/characters/catalog', headers: headers(stranger),
    });
    expect(catalogBefore.json().items.some((c: any) => c.id === characterId)).toBe(true);
    const edit = await app.inject({
      method: 'PATCH', url: '/v1/characters/' + characterId, headers: headers(owner),
      payload: { greeting: 'A new unreviewed greeting' },
    });
    expect(edit.statusCode).toBe(200);
    const catalogAfter = await app.inject({
      method: 'GET', url: '/v1/characters/catalog', headers: headers(stranger),
    });
    expect(catalogAfter.json().items.some((c: any) => c.id === characterId)).toBe(false);
    const state = await db.publication.findUnique({ where: { characterId } });
    expect(state?.status).toBe('PENDING_REVIEW');
  });

  it('keeps a character hidden if its submission to moderation fails', async () => {
    const item = await db.character.create({ data: {
      ownerId: owner, name: 'Hidden explorer', greeting: 'Hello', category: 'adventure', isHidden: true,
      publication: { create: { status: 'HIDDEN' } },
    } });
    const url = '/v1/characters/' + item.id + '/publish';
    beforePublicationWrite.mockImplementationOnce(() => { throw new Error('Publication write failed'); });
    const failed = await app.inject({ method: 'POST', url, headers: headers(owner) });
    expect(failed.statusCode).toBe(500);
    expect(await db.character.findUnique({ where: { id: item.id }, include: { publication: true } }))
      .toMatchObject({ isHidden: true, publication: { status: 'HIDDEN', publishedAt: null } });
    const retry = await app.inject({ method: 'POST', url, headers: headers(owner) });
    expect(retry.statusCode).toBe(200);
    expect(await db.character.findUnique({ where: { id: item.id }, include: { publication: true } }))
      .toMatchObject({ isHidden: false, publication: { status: 'PENDING_REVIEW', publishedAt: null } });
  });

  it('discovers only moderated public characters by text, category and tag', async () => {
    const stamp = randomUUID().slice(0, 10);
    const q = 'NovaScout' + stamp;
    const publicItem = await db.character.create({
      data: {
        ownerId: owner, name: q, greeting: 'Greetings explorer',
        category: 'adventure', tags: ['quest'],
        publication: { create: { status: 'PUBLISHED' } },
      },
    });
    await db.character.create({
      data: {
        ownerId: owner, name: q + '-PRIVATE', greeting: 'Unlisted',
        category: 'adventure', tags: ['quest'],
      },
    });
    const response = await app.inject({
      method: 'GET',
      url: '/v1/characters/catalog?q=' + q + '&category=ADVENTURE&tag=quest&sort=name',
      headers: headers(stranger),
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().items.map((c: any) => c.id)).toEqual([publicItem.id]);
    const excluded = await app.inject({
      method: 'GET',
      url: '/v1/characters/catalog?q=' + q + '&category=science',
      headers: headers(stranger),
    });
    expect(excluded.json().items).toEqual([]);
  });

  it('rejects oversized discover-search input', async () => {
    const response = await app.inject({
      method: 'GET',
      url: '/v1/characters/catalog?q=' + 'x'.repeat(81),
      headers: headers(stranger),
    });
    expect(response.statusCode).toBe(400);
  });

  it('allows private favorites for the owner, not a stranger', async () => {
    const favorite = await app.inject({
      method: 'PUT', url: '/v1/characters/' + characterId + '/favorite', headers: headers(owner),
    });
    expect(favorite.statusCode).toBe(200);
    const forbidden = await app.inject({
      method: 'PUT', url: '/v1/characters/' + characterId + '/favorite', headers: headers(stranger),
    });
    expect(forbidden.statusCode).toBe(404);
  });

  it('accepts concurrent repeats when adding the same favorite', async () => {
    const item = await db.character.create({ data: {
      ownerId: owner, name: 'Favorite explorer', greeting: 'Hello', category: 'adventure',
    } });
    const responses = await Promise.all(Array.from({ length: 8 }, () => app.inject({
      method: 'PUT', url: '/v1/characters/' + item.id + '/favorite', headers: headers(owner),
    })));
    expect(responses.map(response => response.statusCode)).toEqual(Array(8).fill(200));
    expect(await db.favorite.count({ where: { userId: owner, characterId: item.id } })).toBe(1);
  });
});
