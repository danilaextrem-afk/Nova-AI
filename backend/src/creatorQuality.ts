/**
 * Deterministic quality checks for AI-generated character cards (pure: no DB, no network).
 * They only ever flag; callers use them to spend the one retry and then keep the best attempt.
 */
export const GREETING_MAX = 220;

const ASSISTANT_PHRASES = [
  /чем (могу|можем) (вам |тебе |быть )?(помочь|полезн)/i,
  /как (я )?могу (вам |тебе )?помочь/i,
  /как (ии|ai|искусственный интеллект|языковая модель|нейросеть)/i,
  /я (—|-)? ?(ии|ai|искусственный интеллект|языковая модель|нейросеть|виртуальный помощник)/i,
  /(языков\w+ модел|нейросет\w+) (не может|не умеет)/i,
  /\bas an ai\b|\blanguage model\b|how can i (help|assist)/i,
];
const META_PHRASES = [
  /^\s*(сценарий|scenario|описание|greeting|приветствие)\s*[:：]/i,
  /в (этом|данном) сценарии/i,
  /(игрок|пользователь) (может|должен) (написать|ответить|выбрать)/i,
  /\{\{?\s*(user|char)\s*\}?\}/i,
];

/** Assistant-style phrases that break the illusion of a character. */
export function assistantPhrases(text: string): string[] {
  return ASSISTANT_PHRASES.filter(r => r.test(text)).map(r => r.source);
}

/** English words (3+ Latin letters) that are not part of the character's name. Russian output should have none. */
export function englishWords(text: string, name = ''): string[] {
  const own = new Set((name.match(/[A-Za-z]{2,}/g) ?? []).map(w => w.toLowerCase()));
  return (text.match(/(?<![A-Za-z])[A-Za-z]{3,}(?![A-Za-z])/g) ?? []).filter(w => !own.has(w.toLowerCase()));
}

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const sentenceCount = (s: string) => (s.replace(/\*[^*]*\*/g, ' ').match(/[^.!?…]+[.!?…]+|[^.!?…]+$/g) ?? []).filter(x => x.trim().length > 2).length;

export function greetingProblems(greeting: string, name: string): string[] {
  const g = (greeting ?? '').trim(); const n = (name ?? '').trim();
  const out: string[] = [];
  if (!g) return ['greeting_empty'];
  if (g.length > GREETING_MAX) out.push('greeting_too_long');
  if (!/\*[^*\n]{2,}\*/.test(g)) out.push('greeting_no_action');
  const plain = g.replace(/^(\s*\*[^*]*\*\s*)+/, '').replace(/^[\s«"'—–-]+/, '');
  const head = [g, plain];
  if (n && head.some(h => new RegExp(`^${esc(n)}(?![\\p{L}])`, 'iu').test(h))) out.push('greeting_starts_with_name');
  if (head.some(h => /^(привет|здравствуй\w*|добрый (день|вечер)|доброе утро|приветствую)[\s,!.—–-]*(меня зовут|я\s+(—|–|-)?\s*[А-ЯЁA-Z])/iu.test(h)
    || /^(Меня зовут|Моё имя|Мое имя|меня зовут|я\s+(—|–|-)\s*|я\s+[А-ЯЁ][а-яё]+[,\s.—–-])/u.test(h)
    || (n && new RegExp(`^я\\s+(—|–|-)?\\s*${esc(n)}`, 'iu').test(h)))) out.push('greeting_introduces_self');
  if (assistantPhrases(g).length) out.push('greeting_assistant_phrase');
  const tail = g.replace(/[\s*_"»)]+$/, '');
  if (!(/\?/.test(g.slice(-120)) || /(…|\.\.\.)$/.test(tail))) out.push('greeting_no_hook');
  if (englishWords(g, n).length) out.push('greeting_english');
  return out;
}

export function scenarioProblems(scenario: string): string[] {
  const s = (scenario ?? '').trim(); const out: string[] = [];
  if (!s) return ['scenario_empty'];
  const n = sentenceCount(s);
  if (s.length < 80 || n < 2) out.push('scenario_too_short');
  if (n > 4) out.push('scenario_too_long');
  if (META_PHRASES.some(r => r.test(s))) out.push('scenario_meta');
  if (/^\s*я[\s,]/i.test(s)) out.push('scenario_first_person');
  if (assistantPhrases(s).length) out.push('scenario_assistant_phrase');
  if (englishWords(s).length) out.push('scenario_english');
  return out;
}

export function speechStyleProblems(style: string): string[] {
  const quoted = (style.match(/«[^»]{2,}»|"[^"]{2,}"|“[^”]{2,}”/g) ?? []).length;
  return quoted >= 2 ? [] : ['speech_no_examples'];
}

/** A comma list of one- or two-word adjectives with no sentence is not a personality. */
export function personalityProblems(personality: string): string[] {
  const p = personality.trim();
  const parts = p.split(/[,;]\s*/).map(x => x.trim()).filter(Boolean);
  const listOnly = parts.length >= 4 && parts.every(x => x.split(/\s+/).length <= 2) && !/[.!?]/.test(p.replace(/\.$/, ''));
  return listOnly || p.length < 40 ? ['personality_generic_list'] : [];
}

export interface QualityCard { name: string; personality: string; speechStyle: string; scenario: string; greeting: string; [k: string]: unknown }

export function cardProblems(card: QualityCard): string[] {
  const all = [...greetingProblems(card.greeting, card.name), ...scenarioProblems(card.scenario),
    ...speechStyleProblems(card.speechStyle), ...personalityProblems(card.personality)];
  const text = [card.biography, card.personality, card.speechStyle, card.backstory, card.scenario, card.greeting].filter(x => typeof x === 'string').join(' ');
  if (assistantPhrases(text).length && !all.some(p => p.endsWith('assistant_phrase'))) all.push('assistant_phrase');
  return [...new Set(all)];
}

/** Quality problems for one variant of a single field; fields without rules have none. */
export function fieldProblems(field: string, value: string, name = ''): string[] {
  switch (field) {
    case 'greeting': return greetingProblems(value, name);
    case 'scenario': return scenarioProblems(value);
    case 'speechStyle': return speechStyleProblems(value);
    case 'personality': return personalityProblems(value);
    default: return assistantPhrases(value).length ? ['assistant_phrase'] : [];
  }
}
