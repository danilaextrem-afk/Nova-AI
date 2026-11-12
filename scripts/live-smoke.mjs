// Live end-to-end check against the deployed NOVA AI API (real HTTPS, real PostgreSQL, real AI if configured).
// Usage: node scripts/live-smoke.mjs https://host [expectedCommitPrefix]
// Creates two throwaway accounts and deletes them at the end. Prints a Markdown report to stdout.
const base = (process.argv[2] || '').replace(/\/$/, '');
const expectCommit = (process.argv[3] || '').slice(0, 7);
if (!/^https:\/\//.test(base)) { console.error('HTTPS base URL required'); process.exit(2); }
const report = []; let failed = false;
const ok = (name, detail = '') => report.push(`| ✅ | ${name} | ${detail} |`);
const bad = (name, detail = '') => { failed = true; report.push(`| ❌ | ${name} | ${detail} |`); };
const note = (name, detail = '') => report.push(`| ⚠️ | ${name} | ${detail} |`);
const sleep = ms => new Promise(r => setTimeout(r, ms));
async function call(method, path, body, token) {
  // Like the Android client (Retrofit without @Body), send no Content-Type when there is no body.
  const r = await fetch(base + path, { method, headers: { ...(body === undefined ? {} : { 'content-type': 'application/json' }), ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(120000) });
  const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch {}
  return { status: r.status, json, text };
}
const tokens = [];
try {
  // 1. Wake the free instance (cold start ~1 min) and wait for this commit when one is expected.
  let health = null; const start = Date.now();
  while (Date.now() - start < 12 * 60000) {
    try { health = await call('GET', '/health'); } catch { health = null; }
    if (health?.status === 200 && (!expectCommit || health.json?.commit === expectCommit)) break;
    await sleep(15000);
  }
  if (health?.status !== 200) throw new Error('API unreachable: ' + (health?.status ?? 'no response'));
  ok('HTTPS /health', `commit ${health.json?.commit ?? '?'}${expectCommit && health.json?.commit !== expectCommit ? ' (expected ' + expectCommit + ' — older build still serving)' : ''}`);
  if (expectCommit && health.json?.commit !== expectCommit) bad('Deployed commit', 'new build did not go live in 12 min');
  const ready = await call('GET', '/ready'); ready.status === 200 ? ok('PostgreSQL reachable (/ready)') : bad('/ready', String(ready.status));

  // 2. Register + login two adults.
  const pw = 'Live-check-' + Math.random().toString(36).slice(2) + '-A1';
  const emails = [1, 2].map(i => `live-check-${Date.now()}-${i}@example.com`);
  for (const email of emails) {
    const r = await call('POST', '/v1/auth/register', { email, password: pw, adultConfirmed: true });
    if (![200, 201].includes(r.status)) throw new Error('register ' + r.status + ' ' + r.text.slice(0, 200));
    const l = await call('POST', '/v1/auth/login', { email, password: pw });
    if (l.status !== 200 || !l.json?.accessToken) throw new Error('login ' + l.status);
    tokens.push(l.json.accessToken);
  }
  ok('Register + login', '2 accounts');
  const [a, b] = tokens;

  // 3. Catalog.
  const cat = await call('GET', '/v1/characters/catalog', undefined, a);
  const items = cat.json?.items ?? [];
  items.length >= 12 ? ok('Catalog', items.length + ' characters') : bad('Catalog', 'only ' + items.length);
  const hidden = await call('GET', '/v1/characters/catalog?section=mature', undefined, a);
  hidden.status === 403 ? ok('18+ section hidden by default') : bad('18+ default', String(hidden.status));

  // 4. Chat + real AI.
  const chat = (await call('POST', '/v1/chats', { characterId: items[0].id }, a)).json?.chat;
  chat?.id ? ok('New chat', items[0].name) : bad('New chat');
  const status = (await call('GET', '/v1/ai/status', undefined, a)).json;
  const msg = await call('POST', `/v1/chats/${chat.id}/messages`, { content: 'Привет! Представься одним предложением.', requestId: crypto.randomUUID() }, a);
  if (status?.configured) {
    if (msg.status === 200 && msg.json?.assistantMessage?.content?.trim()) ok('Live AI reply', `${status.provider}/${status.model}: «${msg.json.assistantMessage.content.slice(0, 120).replace(/\|/g, '/')}»`);
    else if (msg.status === 429 || msg.status === 503) note('Live AI reply', 'provider busy/limited: HTTP ' + msg.status + ' ' + (msg.json?.error ?? ''));
    else bad('Live AI reply', msg.status + ' ' + msg.text.slice(0, 200));
  } else {
    msg.status === 503 && msg.json?.error === 'AI_NOT_CONFIGURED' ? note('Live AI reply', 'no provider key on the server yet — honest 503, nothing saved') : bad('AI not configured but got', String(msg.status));
  }
  const hist = await call('GET', `/v1/chats/${chat.id}/messages`, undefined, a);
  const saved = hist.json?.items?.length ?? 0;
  status?.configured && msg.status === 200 ? (saved >= 2 ? ok('History in PostgreSQL', saved + ' messages') : bad('History', saved + ' messages'))
    : (saved === 0 ? ok('No fake messages saved on AI failure') : bad('Messages saved without AI', String(saved)));

  // 4b. Group scene with real models: separate replies from different characters; lasting character memory.
  const group = (await call('POST', '/v1/chats', { characterId: items[0].id, participantIds: [items[1].id, items[2].id] }, a)).json?.chat;
  const turn = await call('POST', `/v1/chats/${group.id}/messages`, { content: 'Привет всем! Представьтесь по одному предложению.', requestId: crypto.randomUUID() }, a);
  if (turn.status === 200) {
    const replies = [turn.json.assistantMessage, ...(turn.json.extraMessages ?? [])];
    const speakers = replies.map(m => m?.speakerName);
    new Set(speakers).size >= 2 && speakers.every(Boolean) && replies.every(m => m.content.trim())
      ? ok('Group scene: separate speakers', speakers.join(' → ') + `: «${replies[1].content.slice(0, 80).replace(/\|/g, '/')}»`)
      : note('Group scene', 'only ' + speakers.join(', ') + ' answered (second speaker is best-effort)');
  } else if (turn.status === 429 || turn.status === 503) note('Group scene', 'provider busy: HTTP ' + turn.status);
  else bad('Group scene', turn.status + ' ' + turn.text.slice(0, 160));
  const mem = await call('POST', `/v1/characters/${items[0].id}/memories`, { content: 'Игрок любит ночные прогулки' }, a);
  const memList = (await call('GET', `/v1/characters/${items[0].id}/memories`, undefined, a)).json?.items ?? [];
  const memOther = (await call('GET', `/v1/characters/${items[0].id}/memories`, undefined, b)).json?.items ?? [];
  mem.status === 201 && memList.length === 1 && memOther.length === 0 ? ok('Character memory is private to its user') : bad('Character memory', mem.status + ' ' + memList.length + '/' + memOther.length);

  // 4c. Smart creator with a real model: one sentence -> full card; the saved character knows its own sheet.
  const made = await call('POST', '/v1/characters/assist', { mode: 'full', idea: 'Весёлая рыжеволосая путешественница, которая любит приключения', current: { age: 27, gender: 'female' } }, a);
  if (made.status === 200 && made.json?.card?.name && made.json.card.greeting) {
    const c = made.json.card;
    ok('AI character card from one sentence', `${c.name} · ${c.category} · ${(c.tags || []).join(', ')}: «${c.greeting.slice(0, 80).replace(/\|/g, '/')}»`);
    const saved = await call('POST', '/v1/characters', { name: c.name, greeting: c.greeting, category: c.category, tags: c.tags, biography: c.biography,
      personality: c.personality, interests: c.interests, appearance: c.appearance, speechStyle: c.speechStyle, backstory: c.backstory, scenario: c.scenario,
      age: 27, gender: 'female', traits: c.traits }, a);
    const own = (await call('POST', '/v1/chats', { characterId: saved.json?.character?.id }, a)).json?.chat;
    const ask = own ? await call('POST', `/v1/chats/${own.id}/messages`, { content: 'Как тебя зовут и сколько тебе лет? Ответь одной фразой.', requestId: crypto.randomUUID() }, a) : null;
    const reply = ask?.json?.assistantMessage?.content ?? '';
    const first = c.name.split(/\s+/)[0];
    reply.includes(first) && /27|двадцать сем/i.test(reply) ? ok('Created character knows its name and age', `«${reply.slice(0, 120).replace(/\|/g, '/')}»`)
      : note('Created character self-knowledge', (ask?.status ?? 'no chat') + ` «${reply.slice(0, 120).replace(/\|/g, '/')}»`);
  } else if (made.status === 429 || made.status === 503) note('AI character card', 'provider busy: HTTP ' + made.status + ' ' + (made.json?.error ?? ''));
  else bad('AI character card', made.status + ' ' + made.text.slice(0, 160));

  // 4d. Editor helper for one field, portrait storage and privacy, streaming, no charges for free chat.
  const field = await call('POST', '/v1/characters/assist', { mode: 'field', field: 'biography', action: 'variants', current: { name: 'Вера Снегирёва', age: 28, gender: 'female', personality: 'Спокойная, наблюдательная' } }, a);
  if (field.status === 200 && field.json?.variants?.length === 3) ok('AI helper: three variants for one field', `«${field.json.variants[0].slice(0, 90).replace(/\|/g, '/')}»`);
  else if (field.status === 429 || field.status === 503) note('AI helper field', 'provider busy: HTTP ' + field.status);
  else bad('AI helper field', field.status + ' ' + field.text.slice(0, 160));
  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(3000, 0x55), Buffer.from([0xff, 0xd9])]);
  const up = await call('POST', '/v1/media/avatar', { data: jpeg.toString('base64'), source: 'upload', rightsConfirmed: true }, a);
  if (up.status === 201 && up.json?.url) {
    const path = new URL(up.json.url).pathname;
    const withPhoto = await call('POST', '/v1/characters', { name: 'Портрет Тест', greeting: 'Привет', category: 'Повседневное', age: 30, gender: 'other', avatarUrl: up.json.url }, a);
    // A fresh session (as after restarting the app) still sees the saved portrait.
    const relog = await call('POST', '/v1/auth/login', { email: emails[0], password: pw });
    const fresh = relog.json?.accessToken;
    const again = (await call('GET', '/v1/characters/' + withPhoto.json?.character?.id, undefined, fresh)).json?.character;
    const own = await fetch(base + path, { headers: { authorization: 'Bearer ' + fresh } });
    const ownBytes = Buffer.from(await own.arrayBuffer());
    const other = await fetch(base + path, { headers: { authorization: 'Bearer ' + b } });
    const anon = await fetch(base + path);
    again?.avatarUrl === up.json.url && own.status === 200 && ownBytes.equals(jpeg) ? ok('Portrait saved and reloaded in a new session', `${ownBytes.length} bytes`) : bad('Portrait reload', `${again?.avatarUrl} ${own.status} ${ownBytes.length}`);
    other.status === 404 && anon.status === 404 ? ok('Private portrait hidden from other account and anonymous') : bad('Portrait privacy', `other ${other.status}, anonymous ${anon.status}`);
  } else bad('Portrait upload', up.status + ' ' + up.text.slice(0, 160));
  const streamChat = (await call('POST', '/v1/chats', { characterId: items[0].id }, a)).json?.chat;
  const sres = await fetch(`${base}/v1/chats/${streamChat.id}/messages/stream`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ' + a },
    body: JSON.stringify({ content: 'Опиши небо этой ночью в двух предложениях.', requestId: crypto.randomUUID() }), signal: AbortSignal.timeout(120000) });
  const sse = await sres.text();
  const deltas = (sse.match(/^event: delta$/gm) || []).length;
  const done = /^event: done$/m.test(sse);
  const streamed = (await call('GET', `/v1/chats/${streamChat.id}/messages`, undefined, a)).json?.items ?? [];
  if (done && deltas >= 2 && streamed.length === 2 && streamed[1].content.length > 10) ok('Streaming reply saved to history', `${deltas} chunks, ${streamed[1].content.length} chars`);
  else if (/AI_|429|503/.test(sse) && !done) note('Streaming', 'provider busy: ' + sse.slice(0, 120));
  else bad('Streaming', `deltas ${deltas}, done ${done}, saved ${streamed.length}: ${sse.slice(0, 160)}`);
  const free = (await call('GET', '/v1/wallet', undefined, a)).json;
  free?.total === 0 ? ok('Free chat and helper charged nothing', 'balance 0 after all free turns') : bad('Free chat charges', JSON.stringify(free));
  const ai = (await call('GET', '/v1/ai/status', undefined, a)).json;
  if (!ai?.premiumConfigured) note('Premium charge', 'Premium route is off on the server; double-charge protection covered by backend tests only');

  // 5. Daily bonus (idempotent) and referral.
  const d1 = await call('POST', '/v1/rewards/daily', undefined, a); const d2 = await call('POST', '/v1/rewards/daily', undefined, a);
  const wallet = (await call('GET', '/v1/wallet', undefined, a)).json;
  d1.json?.granted && !d2.json?.granted && wallet?.total === d1.json.amount ? ok('Daily bonus once', '+' + d1.json.amount + ' tokens, repeat ignored') : bad('Daily bonus', JSON.stringify([d1.json, d2.json, wallet]));
  const code = (await call('GET', '/v1/referrals', undefined, a)).json?.code;
  const applied = await call('POST', '/v1/referrals/apply', { code }, b);
  applied.status === 200 && applied.json?.rewarded === false ? ok('Referral code accepted, reward pending until 3 chats') : bad('Referral', applied.status + ' ' + applied.text.slice(0, 120));

  // 5b. AI Art: one real picture from Cloudflare, and an unsafe prompt refused.
  const art = (await call('GET', '/v1/art/status', undefined, b)).json;
  if (art?.configured) {
    const pic = await call('POST', '/v1/art', { prompt: 'Маяк на скале на закате', style: 'fantasy', requestId: crypto.randomUUID() }, b);
    if (pic.status === 200 && pic.json?.image?.length > 1000) ok('AI Art picture', `${Math.round(pic.json.image.length * 0.75 / 1024)} KB JPEG, free left ${pic.json.freeLeft}`);
    else if (pic.status === 503 || pic.status === 429) note('AI Art picture', 'provider busy: HTTP ' + pic.status);
    else bad('AI Art picture', pic.status + ' ' + pic.text.slice(0, 160));
    const unsafe = await call('POST', '/v1/art', { prompt: 'голая девушка', style: 'anime', requestId: crypto.randomUUID() }, b);
    unsafe.status === 422 ? ok('AI Art refuses unsafe prompt') : bad('AI Art unsafe prompt', String(unsafe.status));
  } else bad('AI Art', 'not configured on the server');

  // 5c. Sign-in errors are specific (the app turns them into clear messages); validation stays strict.
  const shortPw = await call('POST', '/v1/auth/login', { email: emails[0], password: 'short' });
  const badMail = await call('POST', '/v1/auth/register', { email: 'not-an-email', password: pw, adultConfirmed: true });
  const wrongPw = await call('POST', '/v1/auth/login', { email: emails[0], password: 'wrong-password-123' });
  const taken = await call('POST', '/v1/auth/register', { email: emails[0], password: pw, adultConfirmed: true });
  const noAge = await call('POST', '/v1/auth/register', { email: 'x' + emails[0], password: pw });
  const spaced = await call('POST', '/v1/auth/login', { email: ' ' + emails[0].toUpperCase() + ' ', password: pw });
  const codes = [shortPw, badMail, wrongPw, taken, noAge, spaced].map(r => r.status + ' ' + (r.json?.error ?? 'ok'));
  shortPw.status === 400 && shortPw.json?.error === 'PASSWORD_TOO_SHORT' && badMail.json?.error === 'EMAIL_INVALID' &&
    wrongPw.status === 401 && taken.status === 409 && noAge.json?.error === 'ADULT_CONFIRMATION_REQUIRED' && spaced.status === 200
    ? ok('Sign-in errors are specific', codes.join(', ')) : bad('Sign-in errors', codes.join(', '));

  // 6. Auth and privacy negatives.
  const anon = await call('GET', '/v1/wallet'); anon.status === 401 ? ok('Anonymous access refused') : bad('Anonymous wallet', String(anon.status));
  const foreign = await call('GET', `/v1/chats/${chat.id}/messages`, undefined, b);
  foreign.status === 404 ? ok("Other user's chat is invisible") : bad('Foreign chat', String(foreign.status));
} catch (e) {
  bad('Run aborted', String(e?.message || e).slice(0, 300));
} finally {
  let removed = 0;
  for (const t of tokens) { try { const r = await call('DELETE', '/v1/me', { confirmation: 'DELETE' }, t); if (r.status < 300) removed++; } catch {} }
  report.push(`| ${removed === tokens.length ? '✅' : '⚠️'} | Test accounts deleted | ${removed}/${tokens.length} |`);
  console.log(`## NOVA AI live check — ${base}\n\n| | Check | Detail |\n|---|---|---|\n` + report.join('\n'));
  process.exit(failed ? 1 : 0);
}
