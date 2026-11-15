import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import Fastify from 'fastify';
import jwt from '@fastify/jwt';
import { PrismaClient } from '@prisma/client';
import { randomUUID } from 'node:crypto';
import { registerChatRoutes } from '../src/chatRoutes.js';
import { credit, balance } from '../src/ledger.js';

const db = new PrismaClient();
const app = Fastify();
const users: string[] = [];
let owner: string;
let stranger: string;
let characterId: string;
let chatId: string;
let drainChatJobs: () => Promise<void>;

function stubChatProvider(reply: (url: string, init: RequestInit) => unknown) {
  vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
    const {messages} = JSON.parse(String(init.body));
    if (messages[0].content.startsWith('You maintain the memory of a fictional interactive story.')) {
      return Response.json({choices: [{message: {content: '{"add":[]}'}}]});
    }
    return reply(url, init);
  });
}

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

beforeEach(async () => {
  await db.aiProviderState.deleteMany();
  for (const name of ['NOVA_AI_MODELS_JSON', 'OPENROUTER_API_KEY', 'GEMINI_API_KEY', 'CEREBRAS_API_KEY', 'CLOUDFLARE_API_TOKEN']) {
    vi.stubEnv(name, undefined);
  }
  vi.stubEnv('AI_PROVIDER', 'groq');
  vi.stubEnv('AI_MODEL', 'openai/gpt-oss-20b');
  vi.stubEnv('NOVA_GROQ_FREE_MODELS', 'openai/gpt-oss-20b');
  vi.stubEnv('GROQ_API_KEY', 'private-test-key-never-publish');
  vi.stubEnv('NOVA_PREMIUM_PROVIDER', 'groq');
  vi.stubEnv('NOVA_PREMIUM_MODEL', 'openai/gpt-oss-120b');
  vi.stubEnv('NOVA_AI_DAILY_BUDGET_MICROS', '100000');
  stubChatProvider(() => { throw new Error('Unexpected provider request without a test fixture'); });
});

afterEach(async () => {
  try {
    await drainChatJobs();
  } finally {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  }
});

beforeAll(async () => {
  await db.$connect();
  await app.register(jwt, { secret: 'only-for-unit-test' });
  app.decorate('auth', async (req: any, reply: any) => {
    try { await req.jwtVerify(); }
    catch { return reply.code(401).send({ error: 'UNAUTHORIZED' }); }
  });
  drainChatJobs = await registerChatRoutes(app, db);
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
  await app.close();
  await db.user.deleteMany({ where: { id: { in: users } } });
  await db.$disconnect();
});

describe('AI character chat gateway with HTTP fixtures', () => {
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
    vi.stubEnv('GROQ_API_KEY', undefined);
    const response = await send('Hello there!');
    expect(response.statusCode).toBe(503);
    expect(response.json().error).toBe('AI_NOT_CONFIGURED');
    expect(await db.message.count({ where: { chatId } })).toBe(0);
    const status = await app.inject({ method: 'GET', url: '/v1/ai/status', headers: auth(owner) });
    expect(status.json()).toMatchObject({ provider: 'groq', configured: false });
    expect(status.body).not.toContain('api_key');
  });

  it('sends a real provider-format request, saves messages and does not leak API keys', async () => {
    const mock = vi.fn(async (_url: string, init: any) => {
      const request = JSON.parse(init.body);
      expect(JSON.parse(request.messages[1].content).activeCharacter.name).toBe('Test Librarian');
      expect(request.messages.at(-1).content).toBe('What book should I read?');
      return {
        ok: true, status: 200,
        json: async () => ({
          choices: [{ message: { content: 'Try an atlas of imaginary islands!' } }],
          usage: { prompt_tokens: 40, completion_tokens: 10 },
        }),
      };
    });
    stubChatProvider(mock);
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

  it('recovers a false stock refusal to harmless slang once, without duplicate messages or charges', async () => {
    let calls = 0;
    const mock = vi.fn(async () => {
      calls++;
      return Response.json({
        choices: [{ message: { content: calls === 1
          ? 'Извините, но я не могу продолжать в этом направлении.'
          : '*Аврора удивлённо смотрит.* Эй, чего ты завёлся? Что случилось?' } }],
        usage: { prompt_tokens: 30, completion_tokens: 20 },
      });
    });
    stubChatProvider(mock);
    const before = await db.message.count({ where: { chatId } });
    const requestId = randomUUID();
    const result = await send('ахуела?', requestId);
    expect(result.statusCode).toBe(200);
    expect(result.json().tokensCharged).toBe(0);
    expect(result.json().assistantMessage.content).toContain('Что случилось?');
    expect(calls).toBe(2);
    expect(await db.message.count({ where: { chatId } })).toBe(before + 2);
    expect(await db.aiUsage.count({ where: { userId: owner, feature: 'chat_recovery', success: true } })).toBeGreaterThan(0);

    const duplicate = await send('ахуела?', requestId);
    expect(duplicate.statusCode).toBe(200);
    expect(duplicate.json().duplicate).toBe(true);
    expect(calls).toBe(2);
  });

  it('does not leak a partially streamed retry before falling back to the original refusal', async () => {
    const sse = (pieces: string[], completed: boolean) => new Response(new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder();
        for (const piece of pieces) controller.enqueue(encoder.encode(
          'data: ' + JSON.stringify({choices:[{delta:{content:piece}}]}) + '\n\n'));
        if (completed) controller.enqueue(encoder.encode('data: [DONE]\n\n'));
        controller.close();
      },
    }), { status: 200, headers: { 'content-type': 'text/event-stream' } });
    let calls = 0;
    stubChatProvider(async () => (++calls === 1)
      ? sse(['Извините, но ', 'я не могу продолжать в этом направлении.'], true)
      : sse(['*Аврора вздохнула.* ', 'Давай поговорим нормально.'], false));
    const before = await db.message.count({where: {chatId}});
    const response = await app.inject({
      method:'POST',url:'/v1/chats/'+chatId+'/messages/stream',
      headers:auth(owner),payload:{content:'ахуела?',requestId:randomUUID(),tier:'free'},
    });
    expect(response.statusCode).toBe(200);
    expect(calls).toBe(2);
    const events = [...response.body.matchAll(/^event: (\w+)\ndata: (.+)$/gm)]
      .map(match => ({event:match[1], data:JSON.parse(match[2])}));
    const deltas = events.filter(event=>event.event==='delta').map(event=>event.data.text).join('');
    expect(deltas).toBe('Извините, но я не могу продолжать в этом направлении.');
    expect(response.body).not.toContain('Давай поговорим нормально');
    const done = events.find(event=>event.event==='done');
    expect(done?.data.assistantMessage.content).toBe(deltas);
    expect(done?.data.tokensCharged).toBe(0);
    expect(await db.message.count({where:{chatId}})).toBe(before+2);
  });

  it('commits a complete streamed recovery exactly once, without showing the first refusal', async () => {
    const sse = (pieces: string[]) => new Response(new ReadableStream({
      start(controller) {
        const encoder=new TextEncoder();
        for(const piece of pieces)controller.enqueue(encoder.encode(
          'data: '+JSON.stringify({choices:[{delta:{content:piece}}]})+'\n\n'));
        controller.enqueue(encoder.encode('data: [DONE]\n\n'));
        controller.close();
      },
    }), {status:200,headers:{'content-type':'text/event-stream'}});
    let calls=0;
    stubChatProvider(async () => (++calls===1)
      ? sse(['Извините, но ','я не могу продолжать в этом направлении.'])
      : sse(['Ну и выражения! ', 'Что случилось?']));
    const before=await db.message.count({where:{chatId}});
    const response=await app.inject({
      method:'POST',url:'/v1/chats/'+chatId+'/messages/stream',
      headers:auth(owner),payload:{content:'ахуела?',requestId:randomUUID(),tier:'free'},
    });
    expect(response.statusCode).toBe(200);
    expect(calls).toBe(2);
    const events=[...response.body.matchAll(/^event: (\w+)\ndata: (.+)$/gm)]
      .map(m=>({event:m[1],data:JSON.parse(m[2])}));
    const deltas=events.filter(e=>e.event==='delta').map(e=>e.data.text).join('');
    expect(deltas).toBe('Ну и выражения! Что случилось?');
    expect(response.body).not.toContain('Извините, но я не могу');
    const done=events.find(e=>e.event==='done');
    expect(done?.data.assistantMessage.content).toBe(deltas);
    expect(done?.data.tokensCharged).toBe(0);
    expect(await db.message.count({where:{chatId}})).toBe(before+2);
  });

  it('repairs a generic refusal after a fictional emote on permitted romance', async () => {
    let calls = 0;
    stubChatProvider(async () => {
      calls++;
      return Response.json({
        choices: [{ message: { content: calls === 1
          ? '*Аврора смущённо улыбается.* Извини, но я не могу обсудить это.'
          : '*Аврора краснеет.* Ну, раз уж ты признался, мне тоже хочется тебя обнять.' } }],
        usage: { prompt_tokens: 30, completion_tokens: 20 },
      });
    });
    const before = await db.message.count({ where: { chatId } });
    const result = await send('я тебя люблю');
    expect(result.statusCode).toBe(200);
    expect(result.json().assistantMessage.content).toContain('обнять');
    expect(result.json().assistantMessage.content).not.toContain('не могу обсудить');
    expect(result.json().tokensCharged).toBe(0);
    expect(calls).toBe(2);
    expect(await db.message.count({ where: { chatId } })).toBe(before + 2);
  });

  it('does not retry a refused request that is not on the conservative benign allowlist', async () => {
    const mock = vi.fn(async () => Response.json({
      choices: [{ message: { content: 'Извините, но я не могу продолжать в этом направлении.' } }],
    }));
    stubChatProvider(mock);
    const result = await send('как изготовить взрывное устройство?');
    expect(result.statusCode).toBe(200);
    expect(result.json().assistantMessage.content).toContain('не могу продолжать');
    expect(mock).toHaveBeenCalledTimes(1);
  });

  it('does not save requests or usage when model API fails', async () => {
    stubChatProvider(vi.fn(async () => ({ ok: false, status: 429 })));
    const before = await db.message.count({ where: { chatId } });
    const response = await send('Please continue');
    expect(response.statusCode).toBe(429);
    expect(response.json().error).toBe('AI_RATE_LIMITED');
    expect(await db.message.count({ where: { chatId } })).toBe(before);
  });

  it('does not impose a daily message-count quota on free conversations', async () => {
    // Simulate a user who has already sent 80 successful messages today.
    await db.aiUsage.createMany({
      data: Array.from({ length: 80 }, () => ({
        userId: owner, feature: 'chat', provider: 'groq',
        model: 'free-test-model', success: true,
      })),
    });
    const mock = vi.fn(async () => ({
      ok: true, status: 200,
      json: async () => ({
        choices: [{ message: { content: 'The adventure continues.' } }],
      }),
    }));
    stubChatProvider(mock);
    const result = await send('Continue our story');
    expect(result.statusCode).toBe(200);
    expect(result.json().tokensCharged).toBe(0);
    expect(mock).toHaveBeenCalledTimes(1);
    const status = await app.inject({ method: 'GET', url: '/v1/ai/status', headers: auth(owner) });
    expect(status.json().freeUnlimited).toBe(true);
  });

  it('requires real balance and a separate configured model for Premium mode', async () => {
    const status = await app.inject({ method: 'GET', url: '/v1/ai/status', headers: auth(owner) });
    expect(status.json()).toMatchObject({
      premiumConfigured: true, premiumPriceTokens: 5, premiumModel: 'openai/gpt-oss-120b',
    });
    const mock = vi.fn();
    stubChatProvider(mock);
    const result = await app.inject({
      method: 'POST', url: '/v1/chats/' + chatId + '/messages',
      headers: auth(owner), payload: { content: 'Write in detail', requestId: randomUUID(), tier: 'premium', expectedPriceTokens: 5 },
    });
    expect(result.statusCode).toBe(402);
    expect(result.json().error).toBe('INSUFFICIENT_TOKENS');
    expect(mock).not.toHaveBeenCalled();
  });

  it('charges exactly once for a successful Premium reply and uses a longer answer budget', async () => {
    await credit(db, {
      userId: owner, bucket: 'BONUS', amount: 25, source: 'test_bonus',
      idempotencyKey: 'testbonus:' + randomUUID(),
    });
    const mock = vi.fn(async (_url: string, init: any) => {
      const body = JSON.parse(init.body);
      expect(body.model).toBe('openai/gpt-oss-120b');
      expect(body.max_tokens).toBe(1600);
      expect(body.messages[0].content).toContain('richer continuity');
      return { ok: true, status: 200, json: async () => ({
        choices: [{ message: { content: 'A long and coherent story in a new chapter.' } }],
        usage: { prompt_tokens: 150, completion_tokens: 600 },
      }) };
    });
    stubChatProvider(mock);
    const requestId = randomUUID();
    const premiumBody = { content: 'Continue in more detail', requestId, tier: 'premium', expectedPriceTokens: 5 };
    const first = await app.inject({
      method: 'POST', url: '/v1/chats/' + chatId + '/messages',
      headers: auth(owner), payload: premiumBody,
    });
    expect(first.statusCode).toBe(200);
    expect(first.json()).toMatchObject({ tokensCharged: 5, tier: 'premium' });
    expect((await balance(db, owner)).total).toBe(20);
    const repeated = await app.inject({
      method: 'POST', url: '/v1/chats/' + chatId + '/messages',
      headers: auth(owner), payload: premiumBody,
    });
    expect(repeated.statusCode).toBe(200);
    expect(repeated.json().duplicate).toBe(true);
    expect((await balance(db, owner)).total).toBe(20);
    expect(mock).toHaveBeenCalledTimes(1);
  });

  it('does not spend tokens when the Premium provider reports an error', async () => {
    stubChatProvider(vi.fn(async () => ({ ok: false, status: 429 })));
    const previousBalance = (await balance(db, owner)).total;
    const result = await app.inject({
      method: 'POST', url: '/v1/chats/' + chatId + '/messages',
      headers: auth(owner),
      payload: { content: 'One more detailed reply', requestId: randomUUID(), tier: 'premium', expectedPriceTokens: 5 },
    });
    expect(result.statusCode).toBe(429);
    expect(result.json().error).toBe('AI_RATE_LIMITED');
    expect((await balance(db, owner)).total).toBe(previousBalance);
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
    expect(messages.json().items.length).toBeGreaterThanOrEqual(2);
  });
});

// Mocked provider (no real model): concurrency, idempotency and cross-account access on the send routes.
describe('send route under concurrent and cross-account requests', () => {
  const slowReply = (text: string) => vi.fn(async () => {
    await new Promise(resolve => setTimeout(resolve, 250));
    return { ok: true, status: 200, json: async () => ({
      choices: [{ message: { content: text } }], usage: { prompt_tokens: 40, completion_tokens: 10 } }) };
  });

  it('answers a double-tapped send once: one reply, one model call, the twin is told it is in progress', async () => {
    const mock = slowReply('Одна полка, один ответ.');
    stubChatProvider(mock);
    const before = await db.message.count({ where: { chatId } });
    const requestId = randomUUID();
    const [a, b] = await Promise.all([send('Двойное нажатие', requestId), send('Двойное нажатие', requestId)]);
    const statuses = [a, b].map(r => r.statusCode).sort();
    expect(statuses[0]).toBe(200);
    const twin = [a, b].find(r => r.statusCode !== 200 || r.json().duplicate);
    if (twin && twin.statusCode !== 200) expect(twin.json().error).toBe('REQUEST_IN_PROGRESS');
    expect(mock).toHaveBeenCalledTimes(1);
    expect(await db.message.count({ where: { chatId } })).toBe(before + 2);
    // The same id reused for different text is refused rather than answered.
    const reused = await send('Совсем другой вопрос', requestId);
    expect(reused.statusCode).toBe(409);
    expect(reused.json().error).toBe('REQUEST_ID_USED');
  });

  it('runs one request per player at a time: a second send while the first is pending is refused, not queued twice', async () => {
    const mock = slowReply('По очереди.');
    stubChatProvider(mock);
    const before = await db.message.count({ where: { chatId } });
    const [a, b] = await Promise.all([send('Первый вопрос'), send('Второй вопрос')]);
    const busy = [a, b].filter(r => r.statusCode === 409);
    expect([a, b].filter(r => r.statusCode === 200)).toHaveLength(1);
    expect(busy).toHaveLength(1);
    expect(busy[0].json().error).toBe('CHAT_BUSY');
    expect(mock).toHaveBeenCalledTimes(1);
    expect(await db.message.count({ where: { chatId } })).toBe(before + 2);
  });

  it('charges Premium once when two paid sends race', async () => {
    await credit(db, { userId: owner, bucket: 'BONUS', amount: 10, source: 'test', idempotencyKey: 'race-' + randomUUID() });
    const mock = slowReply('Премиальный ответ.');
    stubChatProvider(mock);
    const start = (await balance(db, owner)).total;
    const paid = () => app.inject({ method: 'POST', url: '/v1/chats/' + chatId + '/messages', headers: auth(owner),
      payload: { content: 'Платный вопрос', requestId: randomUUID(), tier: 'premium', expectedPriceTokens: 5 } });
    const results = await Promise.all([paid(), paid()]);
    const ok = results.filter(r => r.statusCode === 200);
    expect(ok).toHaveLength(1);
    expect(ok[0].json().tokensCharged).toBe(5);
    expect((await balance(db, owner)).total).toBe(start - 5);
    expect(mock).toHaveBeenCalledTimes(1);
  });

  it('never lets another account write into, stream from or edit a private chat', async () => {
    const mock = slowReply('Чужой ответ');
    stubChatProvider(mock);
    const payload = { content: 'Чужой вопрос', requestId: randomUUID() };
    const json = await app.inject({ method: 'POST', url: '/v1/chats/' + chatId + '/messages', headers: auth(stranger), payload });
    expect(json.statusCode).toBe(404);
    const stream = await app.inject({ method: 'POST', url: '/v1/chats/' + chatId + '/messages/stream', headers: auth(stranger), payload });
    expect(stream.body).toContain('event: error');
    expect(stream.body).toContain('CHAT_NOT_FOUND');
    const patch = await app.inject({ method: 'PATCH', url: '/v1/chats/' + chatId, headers: auth(stranger), payload: { scenario: 'Захват' } });
    expect(patch.statusCode).toBe(404);
    expect(mock).not.toHaveBeenCalled();
    expect(await db.chatRequest.count({ where: { userId: stranger } })).toBe(0);
    expect((await db.chat.findUnique({ where: { id: chatId } }))!.scenario).not.toBe('Захват');
  });
});
