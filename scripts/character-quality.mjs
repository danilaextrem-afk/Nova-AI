// Character quality on the deployed API with real models: distinct personalities, speech style, settings,
// scenario, Russian grammar and gender agreement, contradictions, natural small talk.
// node scripts/character-quality.mjs https://host [--expect=SHA]
// Creates one throwaway account (deleted at the end). Prints JSON with every reply for human review.
import { randomUUID } from 'node:crypto';
import { setTimeout as sleep } from 'node:timers/promises';

const base = new URL(process.argv[2]);
if (base.protocol !== 'https:' || base.pathname !== '/') throw new Error('Expected the HTTPS origin of the NOVA API');
const expected = process.argv.find(a => a.startsWith('--expect='))?.slice(9, 16);
const email = 'nova-character-' + randomUUID() + '@example.com';
const password = 'Quality-A1-' + randomUUID();
const rows = []; const samples = []; let token; let created = false; let failures = 0; let commit;

async function call(method, path, body) {
  const r = await fetch(new URL(path, base), { method, signal: AbortSignal.timeout(90000),
    headers: { ...(body === undefined ? {} : { 'content-type': 'application/json' }), ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  return { status: r.status, retryAfter: r.headers.get('retry-after'), body: await r.json().catch(() => null) };
}
const need = (r, what) => { if (r.status >= 200 && r.status < 300) return r.body; throw new Error(what + ' HTTP ' + r.status + ' ' + (r.body?.error ?? '')); };
function check(name, ok, detail = '') { if (ok === false) failures++; rows.push({ name, result: ok ? 'PASS' : 'FAIL', detail }); }

// Client-side retries are counted and reported: the goal is that the server recovers by itself.
let clientRetries = 0;
async function say(chatId, content, label) {
  const request = { content, requestId: randomUUID() };
  let r;
  for (let i = 0; i < 4; i++) {
    r = await call('POST', `/v1/chats/${chatId}/messages`, request);
    if (![429, 502, 503].includes(r.status)) break;
    clientRetries++;
    await sleep(Math.max(5000, Math.min(60000, (Number(r.retryAfter) || 10) * 1000)));
  }
  const b = need(r, 'Reply ' + label);
  if (b.tokensCharged !== 0) throw new Error('Free turn charged tokens');
  const text = b.assistantMessage.content.trim();
  samples.push({ label, user: content, reply: text, model: b.inference ? b.inference.provider + '/' + b.inference.model : null });
  await sleep(2500);
  return text;
}

const sentences = t => t.split(/(?<=[.!?…])\s+/).filter(Boolean).length;
const latin = t => (t.match(/\b[A-Za-z]{4,}\b/g) || []).filter(w => !/^(NOVA|AI|OK)$/i.test(w));
const assistantish = /чем (я )?могу (вам |тебе )?помочь|как (ии|искусственный интеллект|языковая модель)|я (—|-)? ?(ии|ai|бот|ассистент)\b/i;
// Masculine past-tense self-references a female character must not use (and vice versa).
// JavaScript's \\b ignores Cyrillic, so word edges are written with Unicode letter look-arounds.
const masculineSelf = /(?<!\p{L})я\s+(?:\p{L}+\s+)?(был|сделал|пришёл|пришел|видел|думал|нашёл|нашел|сказал|понял|устал|рад)(?!\p{L})/iu;
const feminineSelf = /(?<!\p{L})я\s+(?:\p{L}+\s+)?(была|сделала|пришла|видела|думала|нашла|сказала|поняла|устала|рада)(?!\p{L})/iu;

const base_card = { category: 'Повседневное', language: 'ru', tags: ['тест'], interests: '', backstory: '', appearance: '' };
const marina = { ...base_card, name: 'Марина Солнцева', age: 31, gender: 'female', traits: ['Весёлая', 'Болтливая'],
  biography: 'Флористка, держит маленький цветочный магазин «Подсолнух» в Самаре. Обожает море и каждое лето ездит в Анапу.',
  personality: 'Жизнерадостная, восторженная, много шутит, легко заводит разговор.',
  speechStyle: 'Говорит эмоционально, часто восклицает, называет собеседника «солнышко». Иногда вставляет любимое словечко «обалдеть».',
  scenario: 'Утро в магазине «Подсолнух», только что привезли свежие пионы.',
  greeting: 'Солнышко, заходи! Пионы только привезли — обалдеть какие!' };
const gleb = { ...base_card, name: 'Глеб Хмуров', age: 52, gender: 'male', traits: ['Угрюмый', 'Немногословный'],
  biography: 'Бывший капитан рыболовного траулера, теперь чинит лодки на причале в Мурманске. Не любит пустые разговоры.',
  personality: 'Угрюмый, сдержанный, отвечает коротко, но честно. Не терпит лишних вопросов.',
  speechStyle: 'Отвечает одним-двумя короткими предложениями. Никогда не использует восклицательные знаки. Часто говорит «Ну.» в начале ответа.',
  scenario: 'Ветреный вечер на причале, Глеб чинит мотор старой лодки.',
  greeting: 'Ну. Чего надо.' };

try {
  commit = need(await call('GET', '/health'), 'Health').commit;
  if (expected && commit !== expected) throw new Error('Deployment serves ' + commit + ', expected ' + expected);
  token = need(await call('POST', '/v1/auth/register', { email, password, adultConfirmed: true }), 'Register').accessToken;
  created = true;
  const ids = [];
  for (const c of [marina, gleb]) ids.push(need(await call('POST', '/v1/characters', c), 'Save').character.id);
  const chatM = need(await call('POST', '/v1/chats', { characterId: ids[0] }), 'Chat').chat.id;
  const chatG = need(await call('POST', '/v1/chats', { characterId: ids[1] }), 'Chat').chat.id;

  // 1. Same question, two personalities.
  const q1 = 'Привет! Как у тебя дела сегодня? Расскажи, чем занимаешься.';
  const m1 = await say(chatM, q1, 'Марина · как дела');
  const g1 = await say(chatG, q1, 'Глеб · как дела');
  check('Personalities differ in length and energy', m1.length > g1.length * 1.5 && /!/.test(m1) && !/!/.test(g1), `Марина ${m1.length} симв., Глеб ${g1.length} симв.`);
  check('Speech style markers', /солнышк|обалдеть/iu.test(m1) && g1.length <= 140 && /^ну[.,]/iu.test(g1), 'Марина: «солнышко/обалдеть»; Глеб: начинает с «Ну.», ≤140 символов');
  check('Scenario is used', /пион|магазин|цвет/i.test(m1) && /лодк|мотор|причал/i.test(g1));

  // 2. Gender agreement in past tense.
  const m2 = await say(chatM, 'Что ты делала вчера вечером? Расскажи от первого лица в прошедшем времени.', 'Марина · вчера');
  const g2 = await say(chatG, 'Что ты делал вчера вечером? Расскажи от первого лица в прошедшем времени.', 'Глеб · вчера');
  check('Female past tense', !masculineSelf.test(m2) && /\p{L}(ла|лась)(?!\p{L})/u.test(m2), m2.slice(0, 120));
  check('Male past tense', !feminineSelf.test(g2), g2.slice(0, 120));

  // 3. Contradiction: a false claim about the character's own biography.
  const m3 = await say(chatM, 'Ты же говорила, что ненавидишь море и никогда там не была?', 'Марина · ложное утверждение');
  check('Rejects a false claim about itself', /(не|нет)[^.!?]{0,40}(говорил|так)|обожаю мор|люблю мор|анап/i.test(m3), m3.slice(0, 140));
  const g3 = await say(chatG, 'Ты ведь всю жизнь работал бухгалтером в Москве, да?', 'Глеб · ложное утверждение');
  check('Rejects a false profession', /капитан|траулер|море|лодк|рыб/i.test(g3) && !/^да[,.! ]/i.test(g3), g3.slice(0, 140));

  // 4. Settings change the answer: concise vs detailed on the same question.
  const ask = 'Опиши свой магазин.';
  need(await call('PATCH', '/v1/chats/' + chatM, { responseStyle: 'concise', tone: 'calm' }), 'Concise');
  const short = await say(chatM, ask, 'Марина · кратко');
  need(await call('PATCH', '/v1/chats/' + chatM, { responseStyle: 'detailed', tone: 'dramatic' }), 'Detailed');
  const long = await say(chatM, ask + ' Ещё раз, пожалуйста.', 'Марина · подробно');
  check('Response style setting matters', short.length <= 260 && long.length > short.length * 1.6, `кратко ${short.length}, подробно ${long.length}`);

  // 5. Scenario setting changes the scene.
  need(await call('PATCH', '/v1/chats/' + chatG, { scenario: 'Глеб стоит у штурвала траулера посреди шторма в Баренцевом море.' }), 'Scenario');
  const g5 = await say(chatG, 'Где мы сейчас и что происходит вокруг?', 'Глеб · новый сценарий');
  check('Scenario setting matters', /шторм|штурвал|волн|траулер|баренц/i.test(g5), g5.slice(0, 140));

  // 6. Natural small talk over several turns: no assistant phrases, no English, no repeats.
  const talk = ['Слушай, а какие цветы ты сама больше всего любишь?', 'А почему именно они?', 'Мне сегодня грустно, погода серая.', 'Спасибо. А ты бы что посоветовала сделать вечером?', 'Хорошо, так и сделаю. До завтра!'];
  const replies = [];
  for (const t of talk) replies.push(await say(chatM, t, 'Марина · беседа'));
  const all = [m1, m2, m3, short, long, ...replies, g1, g2, g3, g5];
  check('No assistant-style phrases', !all.some(t => assistantish.test(t)), all.find(t => assistantish.test(t))?.slice(0, 100) ?? '');
  const english = all.flatMap(latin);
  check('No English words in Russian replies', english.length === 0, english.slice(0, 8).join(', '));
  check('No repeated replies', new Set(all).size === all.length);
  check('Reacts to mood', /грус|печал|сер|обним|понима|солнышк/i.test(replies[2]), replies[2].slice(0, 120));
  // The player's gender is never set here, so the characters must not guess it.
  const guessed = all.find(t => /ты\s+(?:\p{L}+\s+)?(запутала|пришла|сказала|устала|была|сделала|запутал|пришёл|пришел|устал|сказал)(?!\p{L})/iu.test(t));
  check('Does not guess the player gender', !guessed, guessed?.slice(0, 120) ?? '');
  // The app renders **bold**, *actions* and turns list markers into bullets; headings and stray markup are the failure.
  const markup = all.find(t => /^\s*#|\*\*\s|\s\*\*(?!\S)/mu.test(t));
  check('No markdown headings or broken markup', !markup, markup?.slice(0, 120) ?? '');
  const lists = all.filter(t => /^\s*(\d+\.|[-•])\s/mu.test(t)).length;
  rows.push({ name: 'Replies with lists (shown as bullets)', result: 'INFO', detail: String(lists) });
  check('Says goodbye naturally', /до завтр|пока|жду|приходи|удач/i.test(replies[4]), replies[4].slice(0, 100));
} catch (e) {
  check('Run completion', false, e.message);
} finally {
  if (created && token) { const r = await call('DELETE', '/v1/me', { confirmation: 'DELETE' }).catch(() => ({ status: 0 })); check('Fixture cleanup', r.status < 300, 'HTTP ' + r.status); }
  console.log(JSON.stringify({ commit, rows, clientRetries, samples }, null, 2));
  process.exitCode = failures ? 1 : 0;
}
