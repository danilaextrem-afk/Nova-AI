import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { registerChatRoutes } from '../src/chatRoutes.js';

const db = new PrismaClient();
const app = Fastify();
const users: string[] = [];
let owner: string;
let stranger: string;
let characterId: string;
let chatId: string;
const previousProvider = process.env.AI_PROVIDER;
const previousKey = process.env.GROQ_API_KEY;
const previousModel = process.env.AI_MODEL;

async function makeUser() {
  const user = await db.user.create({
    data: { email: 'chat-' + randomUUID() + '@example.test', passwordHash: 'test-only' },
  });
  users.push(user.id);
  return user.id;
}
function auth(userId: string) {
  return { authorization: 'Bearer ' + app.jwt.sign({ sub: userId }) };
}
async function send(content: string, requestId = randomUUID()) {
  return app.inject({
    method: 'POST', url: '/v1/chats/' + chatId + '/messages',
    headers: auth(owner), payload: { content, requestId },
  });
}

beforeAll(async () => {
  await db.$connect();
  await app.register(jwt, { secret: 'only-for-unit-test' });
  app.decorate('auth', async (req: any, reply: any) => {
    try { await req.jwtVerify(); }
    catch { return reply.code(401).send({ error: 'UNAUTHORIZED' }); }
  });
  await registerChatRoutes(app, db);
  await app.ready();
  owner = await makeUser();
  stranger = await makeUser();
  const character = await db.character.create({
    data: {
      ownerId: owner, name: 'Test Librarian', greeting: 'Welcome to the library',
      category: 'fantasy', tags: ['books'],
    },
  });
  characterId = character.id;
});

afterAll(async () => {
  vi.unstubAllGlobals();
  for (const [name, value] of [
    ['AI_PROVIDER', previousProvider], ['GROQ_API_KEY', previousKey], ['AI_MODEL', previousModel],
  ] as const) {
    if (value === undefined) delete process.env[name];
    else process.env[name] = value;
  }
  await app.close();
  await db.user.deleteMany({ where: { id: { in: users } } });
  await db.$disconnect();
});

describe('real AI character chat gateway', () => {
  it('requires authentication and restricts private character creation', async () => {
    const unauthorized = await app.inject({ method: 'POST', url: '/v1/chats', payload: { characterId } });
    expect(unauthorized.statusCode).toBe(401);
    const other = await app.inject({
      method: 'POST', url: '/v1/chats', headers: auth(stranger), payload: { characterId },
    });
    expect(other.statusCode).toBe(404);
  });

  it('opens a private chat, but does not expose it across accounts', async () => {
    const opened = await app.inject({
      method: 'POST', url: '/v1/chats',
      headers: auth(owner), payload: { characterId },
    });
    expect(opened.statusCode).toBe(201);
    chatId = opened.json().chat.id;
    const forbidden = await app.inject({
      method: 'GET', url: '/v1/chats/' + chatId + '/messages', headers: auth(stranger),
    });
    expect(forbidden.statusCode).toBe(404);
    const listing = await app.inject({ method: 'GET', url: '/v1/chats', headers: auth(stranger) });
    expect(listing.json().items.some((chat: any) => chat.id === chatId)).toBe(false);
  });

  it('fails honestly when no AI provider is configured, without saving fake messages', async () => {
    process.env.AI_PROVIDER = 'groq';
    delete process.env.GROQ_API_KEY;
    const response = await send('Hello there!');
    expect(response.statusCode).toBe(503);
    expect(response.json().error).toBe('AI_NOT_CONFIGURED');
    expect(await db.message.count({ where: { chatId } })).toBe(0);
    const status = await app.inject({ method: 'GET', url: '/v1/ai/status', headers: auth(owner) });
    expect(status.json()).toMatchObject({ provider: 'groq', configured: false });
    expect(status.body).not.toContain('api_key');
  });

  it('sends a real provider-format request, saves messages and does not leak API keys', async () => {
    process.env.GROQ_API_KEY = 'private-test-key-never-publish';
    process.env.AI_MODEL = 'openai/gpt-oss-20b';
    const mock = vi.fn(async (_url: string, init: any) => {
      const request = JSON.parse(init.body);
      expect(request.messages[0].content).toContain('Test Librarian');
      expect(request.messages.at(-1).content).toBe('What book should I read?');
      return {
        ok: true, status: 200,
        json: async () => ({
          choices: [{ message: { content: 'Try an atlas of imaginary islands!' } }],
          usage: { prompt_tokens: 40, completion_tokens: 10 },
        }),
      };
    });
    vi.stubGlobal('fetch', mock);
    const requestId = randomUUID();
    const response = await send('What book should I read?', requestId);
    expect(response.statusCode).toBe(200);
    expect(response.json().assistantMessage.content).toContain('atlas');
    expect(response.body).not.toContain('private-test-key');
    expect(mock).toHaveBeenCalledTimes(1);
    const count = await db.message.count({ where: { chatId } });
    expect(count).toBe(2);
    const used = await db.aiUsage.count({ where: { userId: owner, feature: 'chat', success: true } });
    expect(used).toBe(1);
    const duplicate = await send('What book should I read?', requestId);
    expect(duplicate.statusCode).toBe(200);
    expect(duplicate.json().duplicate).toBe(true);
    expect(mock).toHaveBeenCalledTimes(1);
  });

  it('does not save requests or usage when model API fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 429 })));
    const before = await db.message.count({ where: { chatId } });
    const response = await send('Please continue');
    expect(response.statusCode).toBe(429);
    expect(response.json().error).toBe('AI_RATE_LIMITED');
    expect(await db.message.count({ where: { chatId } })).toBe(before);
  });

  it('does not permit another user to delete the private chat', async () => {
    const forbidden = await app.inject({
      method: 'DELETE', url: '/v1/chats/' + chatId, headers: auth(stranger),
    });
    expect(forbidden.statusCode).toBe(404);
    const messages = await app.inject({
      method: 'GET', url: '/v1/chats/' + chatId + '/messages', headers: auth(owner),
    });
    expect(messages.statusCode).toBe(200);
    expect(messages.json().items).toHaveLength(2);
  });
});
