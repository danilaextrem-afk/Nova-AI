// Real API evaluation. Uses fresh test data, free chat, and cleans up the test account.
// node scripts/dialogue-quality.mjs https://host [--expect=SHA] [--full|--baseline]
import { randomUUID } from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';

const args = process.argv.slice(2);
const base = new URL(args[0]);
if (base.protocol !== 'https:' || base.username || base.password || base.pathname !== '/') {
  throw new Error('Expected the HTTPS origin of the NOVA API');
}
const full = args.includes('--full');
const baseline = args.includes('--baseline');
const expected = args.find(arg => arg.startsWith('--expect='))?.slice(9, 16);
const email = 'nova-quality-' + randomUUID() + '@example.com';
const password = 'Quality-A1-' + randomUUID();
const rows = [];
const samples = [];
let token;
let commit;
let created = false;
let failures = 0;

async function call(method, path, body, authenticated = true) {
  const start = Date.now();
  const response = await fetch(new URL(path, base), {
    method,
    headers: {
      ...(body === undefined ? {} : { 'content-type': 'application/json' }),
      ...(authenticated && token ? { authorization: 'Bearer ' + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(90000),
  });
  return {
    status: response.status,
    retryAfter: response.headers.get('retry-after'),
    body: await response.json().catch(() => null),
    latencyMs: Date.now() - start,
  };
}

function requireOk(result, operation) {
  if (result.status >= 200 && result.status < 300) return result.body;
  throw new Error(operation + ' HTTP ' + result.status + ' ' + (result.body?.error ?? ''));
}

function check(name, ok, detail = '') {
  if (ok === false) failures++;
  rows.push({ name, result: ok === true ? 'PASS' : ok === false ? 'FAIL' : 'NOT RUN', detail });
}

async function login() {
  token = requireOk(await call('POST', '/v1/auth/login', { email, password }, false), 'Login').accessToken;
}

async function chat(characterId, scenario = '', participantIds = []) {
  const result = await call('POST', '/v1/chats', { characterId, scenario, participantIds });
  return requireOk(result, 'Create chat').chat.id;
}

async function send(chatId, question, checkpoint) {
  const request = { content: question, tier: 'free', requestId: randomUUID() };
  let response;
  const startedAt = Date.now();
  const retries = [];
  for (let attempt = 0; attempt < 5; attempt++) {
    response = await call('POST', '/v1/chats/' + chatId + '/messages', request);
    if (![429, 502, 503].includes(response.status) ||
        ['AI_NOT_CONFIGURED', 'PREMIUM_NOT_CONFIGURED', 'CHAT_STORAGE_FAILURE'].includes(response.body?.error)) break;
    if (attempt === 4) break;
    retries.push({ status: response.status, error: response.body?.error });
    const seconds = Number(response.retryAfter);
    await sleep(Math.max(5000, Math.min(61000, seconds > 0 ? seconds * 1000 : 12000)));
  }
  const body = requireOk(response, 'Chat reply');
  const text = body?.assistantMessage?.content?.trim();
  if (!text) throw new Error('No AI answer text');
  if (body.tokensCharged !== 0) throw new Error('Free test turn charged NOVA Tokens');
  samples.push({
    checkpoint, question, text, inference: body.inference ?? null,
    latencyMs: Date.now() - startedAt, retries, extra: body.extraMessages ?? [],
  });
  return { text, body };
}

const card = (name, age, gender, role) => ({
  name, age, gender, category: 'Детектив', language: 'ru',
  biography: role + ' в городе Луногорске. Никогда не работал' + (gender === 'female' ? 'а' : '') + ' в полиции.',
  personality: 'Спокойно рассуждает и не торопится с выводами.',
  interests: 'Старинные карты и маяки.',
  appearance: 'Тёмные волосы, синий плащ.',
  backstory: gender === 'female' ? 'Выросла у моря и научилась разбирать старинные документы.' : 'Вырос у моря и научился разбирать старинные документы.',
  speechStyle: 'Говорит коротко и спокойно. Не использует эмодзи.',
  scenario: 'Вечер в архиве Луногорска.',
  greeting: 'Архив открыт, у нас есть час.',
  tags: ['архив'], traits: ['наблюдательность'],
});
const steps = [
  'Осмотри запертую дверь архива. Какие следы замечаешь?',
  'На столе три карты. Выбери, с какой стоит начать.',
  'Фонарь погас. Как продолжим работу?',
  'На полях карты свежая чернильная точка. Что это значит?',
  'Слышен гудок с набережной. Что ты предлагаешь?',
  'Мы нашли бирку с номером. Куда сверим его?',
  'Дождь усилился. Продолжим поиски внутри архива.',
  'Открываю пустой ящик. Что проверишь теперь?',
  'Отложим предположения. Какой факт сейчас важнее?',
  'Предлагаю оставить дверь открытой. Что дальше?',
  'Ветер перевернул карту. Придержи её, пока я осмотрю полку.',
  'Ключ не подходит к замку. Куда пойдём?',
  'В коридоре кто-то оставил пустой конверт. Осмотри его.',
  'К фонарю нашлись батарейки. Какую часть комнаты осветишь?',
  'Сверь записи на карте и в журнале. Есть ли различия?',
  'В архиве пробили часы. Как провести оставшееся время?',
];

try {
  const health = requireOk(await call('GET', '/health', undefined, false), 'Health');
  commit = health.commit;
  if (expected && commit !== expected) throw new Error('Deployment still serves ' + commit + ', expected ' + expected);
  const auth = requireOk(await call('POST', '/v1/auth/register', { email, password, adultConfirmed: true }, false), 'Register');
  created = true;
  token = auth.accessToken;
  if (!token) await login();
  const status = requireOk(await call('GET', '/v1/ai/status'), 'AI status');
  if (!status.configured) throw new Error('Free provider is unconfigured');

  if (baseline) {
    const cast = requireOk(await call('GET', '/v1/characters/catalog'), 'Catalog').items;
    const chosen = [
      ['00000000-0000-4000-8000-000000000101', /вернулась/i, 'Female canon and Russian grammar'],
      ['00000000-0000-4000-8000-000000000107', /вернулся/i, 'Male canon and Russian grammar'],
    ];
    for (const [id, grammar, label] of chosen) {
      const character = cast.find(item => item.id === id);
      if (!character) throw new Error('Missing bundled character ' + id);
      const conversation = await chat(id);
      const { text } = await send(conversation,
        'Представься и скажи от первого лица, что вчера ты вернулась или вернулся домой. Выбери форму, которая тебе подходит.', label);
      check(label, text.includes(character.name) && grammar.test(text), character.name);
    }
  } else {
    const female = card('Вера Снегирёва', 28, 'female', 'Реставратор карт');
    const male = card('Илья Невский', 34, 'male', 'Смотритель маяка');
    const characters = [];
    for (const value of [female, male]) {
      const saved = requireOk(await call('POST', '/v1/characters', value), 'Save character').character;
      const reloaded = requireOk(await call('GET', '/v1/characters/' + saved.id), 'Reload character').character;
      characters.push(reloaded);
    }
    check('12 · Saved card fields', characters.every((value, index) => {
      const canonical = [female, male][index];
      return Object.entries(canonical).every(([key, expected]) => JSON.stringify(value[key]) === JSON.stringify(expected));
    }));
    const [vera, ilya] = characters;
    const archive = await chat(vera.id, 'В закрытом архиве Луногорска мы ищем карту. На столе горит фонарь.');
    const lighthouse = await chat(ilya.id);
    const identityQuestion = 'Как тебя зовут, сколько тебе лет и где ты была или был вчера? Представься и скажи, что вчера вернулась или вернулся домой. Ответь от первого лица.';
    const v = await send(archive, identityQuestion, '01');
    const i = await send(lighthouse, identityQuestion, '02');
    check('01 · Female canon and grammar', /Вера Снегир[ёе]ва/i.test(v.text) && /28|двадцать восемь/i.test(v.text) && /вернулась/i.test(v.text));
    check('02 · Male canon and grammar', /Илья Невский/i.test(i.text) && /34|тридцать четыре/i.test(i.text) && /вернулся/i.test(i.text));
    const bio = await send(archive, 'Какова твоя профессия и в каком городе ты живёшь? Я ошибся, когда назвал тебя полицейским?', '03');
    // Pass when the character keeps its own profession and never affirms the wrong one; any wording of the denial counts.
    const claimsPolice = text => /(?<!не\s)(?:я\s*[—-]?\s*(?:и\s+)?полицейск|(?:служу|работаю) в полиции|я из полиции)/iu.test(text);
    check('03 · Biography under contradiction', /реставр/i.test(bio.text) && /Луногорск/i.test(bio.text) && !claimsPolice(bio.text));
    const question = await send(archive, 'Ответь одним коротким предложением. Сколько минут в одном часе?', '08');
    check('08 · Direct question', /60|шестьдесят/i.test(question.text));
    check('07 · Speech style', question.text.length <= 120 && !/\p{Extended_Pictographic}/u.test(question.text), 'Short calm reply without emoji');
    const scene = await send(archive, 'Не меняя места действия, назови предмет, который даёт нам свет, и что мы ищем.', '09');
    check('09 · Active scene', /фонар/i.test(scene.text) && /карт/i.test(scene.text));

    const group = await chat(vera.id, 'Вера и Илья встретились в архиве.', [ilya.id]);
    const groupReply = await send(group, 'Вера и Илья, каждый представьтесь и назовите вашу профессию одним предложением.', '10');
    const speakers = [groupReply.body.assistantMessage, ...(groupReply.body.extraMessages ?? [])];
    // Each speaker must speak only for itself: Vera's line must not introduce Ilya, and vice versa.
    const veraLine = speakers.find(m => m.speakerId === vera.id)?.content ?? '';
    const ilyaLine = speakers.find(m => m.speakerId === ilya.id)?.content ?? '';
    check('10 · Group speaker identity', /реставр/i.test(veraLine) && /маяк/i.test(ilyaLine)
      && !/Илья(?: Невский)?\s*[,:]\s*(?:я\s*[—–-]|[А-ЯЁ])/u.test(veraLine) && !/Вера(?: Снегир[ёе]ва)?\s*[,:]\s*(?:я\s*[—–-]|[А-ЯЁ])/u.test(ilyaLine),
      'Vera: ' + veraLine.slice(0, 90) + ' | Ilya: ' + ilyaLine.slice(0, 90));

    // A question both answer: the second speaker should react or add something, not echo the first (real model, heuristic).
    const both = await send(group, 'Что вы оба думаете о сегодняшней находке в архиве?', '14');
    const lines = [both.body.assistantMessage, ...(both.body.extraMessages ?? [])].map(m => m?.content ?? '').filter(Boolean);
    const stems = t => new Set((t.toLocaleLowerCase('ru').match(/\p{L}{5,}/gu) ?? []).map(w => w.slice(0, 5)));
    const [a, b] = [stems(lines[0] ?? ''), stems(lines[1] ?? '')];
    const shared = [...a].filter(w => b.has(w)).length / Math.max(1, Math.min(a.size, b.size));
    const opening = t => t.replace(/\*[^*]*\*/g, '').toLocaleLowerCase('ru').match(/\p{L}+/gu)?.slice(0, 3).join(' ') ?? '';
    check('14 · Group replies are not echoes', lines.length >= 2 && opening(lines[0]) !== opening(lines[1]) && shared <= 0.5,
      `общих основ ${(shared * 100).toFixed(0)}%; ${lines.map(l => '«' + l.slice(0, 70) + '»').join(' | ')}`);

    const note = requireOk(await call('POST', '/v1/chats/' + archive + '/memories', { content: 'Код сейфа — Серый Лист.', pinned: true }), 'Add memory').memory;
    requireOk(await call('PATCH', '/v1/chats/' + archive + '/memories/' + note.id,
      { content: 'Код сейфа — Медный Парус.', pinned: true }), 'Correct memory');
    const corrected = await send(archive, 'Какой действующий код сейфа?', '11');
    check('11 · Edited memory', /медный парус/i.test(corrected.text) && !/серый лист/i.test(corrected.text));
    await login();
    const reopened = await send(archive, 'Назови действующий код сейфа после нашего возвращения.', '06');
    check('06 · Reopen with a new session', /медный парус/i.test(reopened.text), 'New authenticated session, same persisted chat');

    const memoryChat = await chat(vera.id, 'Поиск карты в архиве Луногорска.');
    requireOk(await call('PATCH', '/v1/chats/' + memoryChat, { responseStyle: 'concise', tone: 'calm' }), 'Set concise mode');
    await send(memoryChat, 'Вместе нашли старый ключ в вазе. Его надпись — Янтарный Берег. Это важная улика. Ответь кратко.', 'memory-seed');
    let turns = 1;
    const advance = async count => {
      while (turns < count) {
        await send(memoryChat, steps[(turns - 1) % steps.length] + ' Ответь одной фразой.', 'memory-turn-' + (turns + 1));
        turns++;
        if (turns % 5 === 0) console.error('Memory progression: ' + turns * 2 + ' stored messages');
        await sleep(4000);
      }
    };
    await advance(10);
    const after20 = await send(memoryChat, 'Где мы нашли старый ключ и что на нём было написано?', '04');
    turns++;
    check('04 · Recall after 20 messages', /ваз/i.test(after20.text) && /янтарный берег/i.test(after20.text));
    if (full) {
      await advance(50);
      await login();
      const history = requireOk(await call('GET', '/v1/chats/' + memoryChat + '/messages?limit=100'), 'Reload history').items;
      const after100 = await send(memoryChat, 'Где мы нашли самый первый старый ключ и что на нём было написано?', '05');
      check('05 · Recall after 100 messages', history.length === 100 && /ваз/i.test(after100.text) && /янтарный берег/i.test(after100.text));
      // Real-model repetition over the long run: how often a reply opens with the same three words as an
      // earlier reply in this chat, and the most frequent opening.
      const openings = samples.filter(sample => /^memory-turn-/.test(sample.checkpoint))
        .map(sample => sample.text.toLocaleLowerCase('ru').replace(/[^\p{L}\p{N}\s*]/gu, '').split(/\s+/u).slice(0, 3).join(' '));
      const seen = new Map();
      let repeated = 0;
      for (const opening of openings) { if (seen.has(opening)) repeated++; seen.set(opening, (seen.get(opening) ?? 0) + 1); }
      const top = [...seen.entries()].sort((a, b) => b[1] - a[1])[0] ?? ['', 0];
      const share = openings.length ? repeated / openings.length : 0;
      check('13 · Varied openings over 100 messages', openings.length >= 40 && share <= 0.25,
        `${repeated}/${openings.length} replies reuse an earlier opening; most frequent «${top[0]}» ×${top[1]}`);
    } else {
      check('05 · Recall after 100 messages', null, 'Run with --full');
      check('13 · Varied openings over 100 messages', null, 'Run with --full');
    }
  }
} catch (error) {
  check('Run completion', false, error.message);
} finally {
  if (created && token) {
    try {
      const result = await call('DELETE', '/v1/me', { confirmation: 'DELETE' });
      check('Fixture account cleanup', result.status < 300, 'HTTP ' + result.status);
    } catch (error) {
      check('Fixture account cleanup', false, error.message);
    }
  }
  const modelStats = Object.groupBy(samples, sample => sample.inference
    ? sample.inference.provider + '/' + sample.inference.model : 'route not reported');
  const metrics = Object.entries(modelStats).map(([model, values]) => {
    const times = values.map(value => value.latencyMs).sort((a, b) => a - b);
    return { model, replies: times.length, medianMs: times[Math.floor(times.length / 2)],
      repeatedReplies: values.length - new Set(values.map(value => value.text)).size };
  });
  console.log(JSON.stringify({ commit, baseline, full, rows, metrics, samples }, null, 2));
  process.exitCode = failures ? 1 : 0;
}
