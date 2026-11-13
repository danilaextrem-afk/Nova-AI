import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { registerChatRoutes } from '../src/chatRoutes.js';

const db = new PrismaClient();
const app = Fastify();
let owner: string;
let characterId: string;
let drainChatJobs: () => Promise<void>;

const auth = (userId: string) => ({authorization: 'Bearer ' + app.jwt.sign({sub: userId})});

beforeAll(async () => {
  await app.register(jwt, {secret: 'chat-attempt-test-only'});
  app.decorate('auth', async (req, reply) => {
    try { await req.jwtVerify(); }
    catch { await reply.code(401).send({error: 'UNAUTHORIZED'}); }
  });
  drainChatJobs = await registerChatRoutes(app, db);
  await app.ready();
  owner = (await db.user.create({data: {email: randomUUID() + '@example.test', passwordHash: 'test'}})).id;
  characterId = (await db.character.create({data: {
    ownerId: owner, name: 'Тестовый герой', greeting: 'Привет', category: 'fiction', tags: [],
  }})).id;
});

beforeEach(async () => {
  await db.aiProviderState.deleteMany();
  const model = {
    provider: 'groq', model: 'test-free', tier: 'free', approved: true,
    rpm: 10000, rpd: 10000, tpm: 10000000, tpd: 100000000,
    inputUsdPerMillion: 0, outputUsdPerMillion: 0, dailyBudgetMicros: 0,
  };
  vi.stubEnv('NOVA_AI_MODELS_JSON', JSON.stringify([model]));
  vi.stubEnv('GROQ_API_KEY', 'fixture-key');
});

afterEach(async () => {
  try { await drainChatJobs(); }
  finally { vi.unstubAllGlobals(); vi.unstubAllEnvs(); }
});

afterAll(async () => {
  await app.close();
  if (owner) await db.user.deleteMany({where: {id: owner}});
  await db.$disconnect();
});

describe('Chat retry isolation', () => {
  it.each([true, false])('keeps a retried request independent of an expired worker (late success: %s)', async (lateSuccess) => {
    const thread = await db.chat.create({data: {userId: owner, characterId}});
    const requestId = randomUUID();
    const payload = {content: 'Повтор после таймаута', requestId};
    const sendAttempt = () => app.inject({method: 'POST', url: `/v1/chats/${thread.id}/messages`, headers: auth(owner), payload});
    let releaseOld!: () => void;
    let releaseNew!: () => void;
    let oldCalled!: () => void;
    let newCalled!: () => void;
    const oldStarted = new Promise<void>(resolve => {oldCalled = resolve;});
    const newStarted = new Promise<void>(resolve => {newCalled = resolve;});
    const oldGate = new Promise<void>(resolve => {releaseOld = resolve;});
    const newGate = new Promise<void>(resolve => {releaseNew = resolve;});
    const response = (content: string) => Response.json({choices: [{message: {content}}]});
    const provider = vi.fn()
      .mockImplementationOnce(async () => {oldCalled(); await oldGate; return lateSuccess ? response('Старый ответ') : new Response('', {status: 429});})
      .mockImplementationOnce(async () => {newCalled(); await newGate; return response('Ответ новой попытки');});
    vi.stubGlobal('fetch', provider);
    const first = sendAttempt();
    let retry: ReturnType<typeof sendAttempt> | undefined;
    try {
      await Promise.race([oldStarted, first.then(r => {throw new Error('First attempt returned early: ' + r.statusCode);})]);
      await db.chatRequest.update({where: {id: requestId}, data: {startedAt: new Date(Date.now() - 181000)}});
      retry = sendAttempt();
      await Promise.race([newStarted, retry.then(r => {throw new Error('Retry returned early: ' + r.statusCode);})]);
      releaseOld();
      const oldReply = await first;
      expect(oldReply.statusCode).toBe(lateSuccess ? 409 : 429);
      expect(await db.chatRequest.findUnique({where: {id: requestId}})).toMatchObject({status: 'PENDING', errorCode: null});
      expect(await db.message.count({where: {chatId: thread.id}})).toBe(0);
      releaseNew();
      const reply = await retry;
      expect(reply.statusCode, reply.body).toBe(200);
      expect(reply.json().assistantMessage.content).toBe('Ответ новой попытки');
      expect(await db.message.count({where: {chatId: thread.id}})).toBe(2);
      expect(await db.aiUsage.count({where: {id: requestId}})).toBe(1);
    } finally {
      releaseOld();
      releaseNew();
      await Promise.allSettled([first, retry]);

    }
  });
});
