// Character-creator quality on the deployed API with a real model: are generated cards specific and distinct,
// do greetings start in the scene (not «Привет, я …»), do scenarios give the player a hook?
// node scripts/creator-quality.mjs https://host [--expect=SHA]
// One throwaway account (deleted at the end); about 4-8 free model calls. Prints JSON for human review.
import { randomUUID } from 'node:crypto';

const base = new URL(process.argv[2]);
if (base.protocol !== 'https:' || base.pathname !== '/') throw new Error('Expected the HTTPS origin of the NOVA API');
const expected = process.argv.find(a => a.startsWith('--expect='))?.slice(9, 16);
const rows = []; const cards = []; let token; let created = false; let failures = 0; let commit;

async function call(method, path, body) {
  const r = await fetch(new URL(path, base), { method, signal: AbortSignal.timeout(120000),
    headers: { ...(body === undefined ? {} : { 'content-type': 'application/json' }), ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body) });
  return { status: r.status, body: await r.json().catch(() => null) };
}
const need = (r, what) => { if (r.status >= 200 && r.status < 300) return r.body; throw new Error(what + ' HTTP ' + r.status + ' ' + (r.body?.error ?? '')); };
const check = (name, ok, detail = '') => { if (ok === false) failures++; rows.push({ name, result: ok ? 'PASS' : 'FAIL', detail }); };
const info = (name, detail) => rows.push({ name, result: 'INFO', detail });

// The same rules the server applies (backend/src/creatorQuality.ts), restated so this script needs no build.
const introducesSelf = (g, name) => {
  const head = g.replace(/^(\s*\*[^*]*\*\s*)+/, '').replace(/^[\s«"'—–-]+/, '');
  const first = name.split(/\s+/)[0];
  return /^(привет|здравствуй\p{L}*|добрый (день|вечер)|доброе утро)[\s,!.—–-]*(меня зовут|я\s*[—–-]?\s*\p{Lu})/iu.test(head)
    || /^(меня зовут|моё имя|мое имя)/iu.test(head) || (first && new RegExp('^(я\\s*[—–-]?\\s*)?' + first + '(?![\\p{L}])', 'iu').test(head));
};
const assistantish = /чем (могу|можем) .{0,10}помочь|как (я )?могу .{0,8}помочь|языков\p{L}* модел|как ии\b|я\s*[—-]?\s*(ии|нейросеть)/iu;
const sentences = s => (s.replace(/\*[^*]*\*/g, ' ').match(/[^.!?…]+[.!?…]+|[^.!?…]+$/g) ?? []).filter(x => x.trim().length > 2).length;
const words = s => new Set((s.toLocaleLowerCase('ru').match(/\p{L}{5,}/gu) ?? []).map(w => w.slice(0, 6)));
const jaccard = (a, b) => { const A = words(a), B = words(b); const both = [...A].filter(w => B.has(w)).length; return both / Math.max(1, A.size + B.size - both); };

// Each idea implies a gender through Russian grammar; the card must keep it.
const ideas = ['Смотритель маяка на северном острове, бывший штурман', 'Хозяйка ночного книжного магазина в старом квартале',
  'Уличный музыкант-саксофонист, который чинит велосипеды', 'Строгая шеф-повар маленького ресторана у вокзала'];
const impliedGender = ['male', 'female', 'male', 'female'];

try {
  commit = need(await call('GET', '/health'), 'Health').commit;
  if (expected && commit !== expected) throw new Error('Deployment serves ' + commit + ', expected ' + expected);
  token = need(await call('POST', '/v1/auth/register', { email: 'nova-creator-' + randomUUID() + '@example.com', password: 'Quality-A1-' + randomUUID(), adultConfirmed: true }), 'Register').accessToken;
  created = true;
  for (const idea of ideas) {
    let r;
    for (let i = 0; i < 3; i++) { r = await call('POST', '/v1/characters/assist', { mode: 'full', idea, current: {} }); if (r.status !== 429 && r.status !== 503) break; await new Promise(res => setTimeout(res, 20000)); }
    const card = need(r, 'Assist ' + idea).card;
    cards.push({ idea, ...card });
  }
  const greet = cards.map(c => c.greeting ?? '');
  const self = cards.filter(c => introducesSelf(c.greeting ?? '', c.name ?? ''));
  check('Greetings do not introduce the character', self.length === 0, self.map(c => '«' + c.greeting.slice(0, 70) + '»').join(' | '));
  check('Greetings start in the scene with an *action*', greet.filter(g => /\*[^*\n]{2,}\*/.test(g)).length >= 3, greet.filter(g => /\*[^*\n]{2,}\*/.test(g)).length + '/4');
  check('Greetings leave the player something to answer', greet.filter(g => /\?/.test(g)).length >= 3, greet.filter(g => /\?/.test(g)).length + '/4 end with a question');
  check('Greetings are short (≤ 220 characters)', greet.every(g => g.length <= 220), greet.map(g => g.length).join(', '));
  const scen = cards.map(c => c.scenario ?? '');
  check('Scenarios are 2-4 sentences, ≥ 80 characters', scen.every(s => s.length >= 80 && sentences(s) >= 2 && sentences(s) <= 4), scen.map(s => sentences(s) + ' предл./' + s.length).join(', '));
  check('Speech styles give example phrases', cards.filter(c => ((c.speechStyle ?? '').match(/«[^»]{2,}»|"[^"]{2,}"|“[^”]{2,}”/g) ?? []).length >= 2).length >= 3,
    cards.map(c => ((c.speechStyle ?? '').match(/«[^»]{2,}»|"[^"]{2,}"|“[^”]{2,}”/g) ?? []).length).join(', ') + ' examples');
  const all = cards.flatMap(c => [c.biography, c.personality, c.speechStyle, c.backstory, c.scenario, c.greeting].filter(Boolean));
  check('No assistant phrases', !all.some(t => assistantish.test(t)), all.find(t => assistantish.test(t))?.slice(0, 100) ?? '');
  let worst = 0, pair = '';
  for (let i = 0; i < cards.length; i++) for (let j = i + 1; j < cards.length; j++) {
    const s = jaccard(cards[i].personality + ' ' + cards[i].speechStyle, cards[j].personality + ' ' + cards[j].speechStyle);
    if (s > worst) { worst = s; pair = cards[i].name + ' / ' + cards[j].name; }
  }
  check('Personalities are distinct (word overlap ≤ 0.25)', worst <= 0.25, worst.toFixed(2) + ' ' + pair);
  const openings = new Set(greet.map(g => g.replace(/\*[^*]*\*/g, '').toLocaleLowerCase('ru').match(/\p{L}+/gu)?.slice(0, 2).join(' ')));
  check('Greetings open differently', openings.size === greet.length, [...openings].join(' / '));
  // Gender from the card's own Russian: pronouns and past-tense forms about the character in biography/backstory.
  const genderOf = c => {
    const t = ' ' + [c.biography, c.backstory, c.personality].filter(Boolean).join(' ').toLocaleLowerCase('ru') + ' ';
    const f = (t.match(/\s(она|её|ее|ей)\s|\p{L}{2,}(ла|лась)(?=[\s,.!?])/gu) ?? []).length;
    const m = (t.match(/\s(он|его|ему|им)\s|\p{L}{2,}(л|лся)(?=[\s,.!?])/gu) ?? []).length;
    return f > m ? 'female' : m > f ? 'male' : 'unknown';
  };
  const genders = cards.map(genderOf);
  check('Cards keep the gender the idea implies', genders.every((g, i) => g === impliedGender[i]), genders.map((g, i) => cards[i].name + ': ' + g + ' (ожидалось ' + impliedGender[i] + ')').join(', '));
  const marked = greet.find(g => /ты\s+(?:\p{L}+\s+)?(готов|готова|пришёл|пришел|пришла|рад|рада|устал|устала)(?!\p{L})/iu.test(g));
  check('Greetings do not guess the player gender', !marked, marked?.slice(0, 100) ?? '');
  info('Card names', cards.map(c => c.name).join(', '));
} catch (e) {
  check('Run completion', false, e.message);
} finally {
  if (created && token) { const r = await call('DELETE', '/v1/me', { confirmation: 'DELETE' }).catch(() => ({ status: 0 })); check('Fixture cleanup', r.status < 300, 'HTTP ' + r.status); }
  console.log(JSON.stringify({ commit, failures, rows, cards }, null, 2));
  process.exitCode = failures ? 1 : 0;
}
