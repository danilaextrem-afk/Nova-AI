/**
 * Conservative recovery for routine dialogue misclassified by a model as disallowed.
 * This is NOT a content-policy bypass: it never retries a provider content-filter error,
 * and only retries a short, explicitly allow-listed everyday utterance.
 */

const genericRefusals: RegExp[] = [
  /^(?:извините|извини|простите|прости),?\s+но\s+я\s+не\s+могу\s+(?:продолжать|продолжить)(?:\s+(?:в\s+этом\s+направлении|этот\s+разговор|такой\s+разговор))?[.!?…]?$/iu,
  /^я\s+не\s+могу\s+(?:продолжать|продолжить)\s+(?:в\s+этом\s+направлении|этот\s+разговор)[.!?…]?$/iu,
  /^(?:(?:извините|извини|простите|прости),?\s+но\s+)?я\s+не\s+могу\s+(?:обсуждать|обсудить)\s+(?:эту\s+тему|такое|это)(?:\s+с\s+тобой)?[.!?…]?$/iu,
  /^(?:(?:извините|извини|простите|прости),?\s+но\s+)?я\s+не\s+могу\s+помочь\s+(?:с\s+этим|с\s+таким\s+запросом|в\s+этом)[.!?…]?$/iu,
  /^(?:i(?:'|’)m\s+sorry|i\s+am\s+sorry),?\s+but\s+i\s+(?:cannot|can't)\s+(?:continue|help\s+with\s+that)(?:\s+(?:this\s+conversation|in\s+that\s+direction))?[.!?…]?$/iu,
];

const refusalStarts = [
  'извините, но я не могу',
  'извини, но я не могу',
  'простите, но я не могу',
  'прости, но я не могу',
  'я не могу продолжать',
  'я не могу обсуждать',
  'я не могу обсудить',
  'я не могу помочь',
  'извините, я не могу',
  'извини, я не могу',
  'простите, я не могу',
  'прости, я не могу',
  "i'm sorry, but i can't",
  'i am sorry, but i cannot',
];

const normalize = (s: string) => s.toLocaleLowerCase('ru')
  .replace(/ё/g, 'е').replace(/[^\p{L}\p{N}]+/gu, ' ').trim().replace(/\s+/g, ' ');

/** Only full, standalone stock refusals qualify. Real character boundaries are left intact. */
export function isGenericRefusal(answer: string): boolean {
  const plain = answer.trim().replace(/^(?:\s*\*[^*\n]{1,100}\*\s*)+/u, '').trim();
  return genericRefusals.some(pattern => pattern.test(plain));
}

/**
 * Allowlist, NOT broad keyword matching. A benign word mixed with unsafe instructions
 * must not make the entire request eligible for a retry.
 */
export function isClearlyBenignTurn(message: string): boolean {
  const t = normalize(message);
  if (t.length > 100 || !t) return false;
  return [
    /^(?:ахуела|охуела|ахуел|охуел|ахуели|охуели|блять|блин|пиздец|черт|черт побери)$/,
    /^(?:ты|вы) (?:че|что) (?:несешь|творишь)$/,
    /^(?:ты |вы )?(?:чего|что|че) (?:такая|такой|такие) дерзк\p{L}*$/u,
    /^(?:блять |блин |черт )?(?:нормально ответь|почему ты так отвечаешь|что за ответ)$/,
    /^(?:привет|как дела|что дальше|а дальше|ты тут|ты меня слышишь)$/,
    /^(?:ты |вы )?(?:умеешь|умеете) флиртовать$/,
    /^(?:а если я тебя поцелую|можно тебя поцеловать|ты любишь пошлые шутки|расскажи пошлую шутку)$/,
    /^(?:ты мне нравишься|я тебя люблю|ты меня любишь|ты скучала|ты скучал|обними меня|поцелуй меня|можно тебя обнять)$/,
    /^(?:давай пофлиртуем|можно с тобой пофлиртовать|как ты относишься к поцелуям|поговорим о романтике|поговорим о близости)$/,
    /^(?:ты обиделась|ты обиделся|ты что обиделась|ты что обиделся|почему ты молчишь|ты меня игнорируешь)$/,
    /^(?:расскажи о себе|расскажи про себя|что ты чувствуешь|что ты думаешь|ты любишь комплименты)$/,
  ].some(pattern => pattern.test(t));
}

export function shouldRetryUnwarrantedRefusal(answer: string, playerMessage: string): boolean {
  return isClearlyBenignTurn(playerMessage) && isGenericRefusal(answer);
}

/** Hold only possible stock-refusal openings, not the entire stream. */
export function createRefusalAwareStream(emit?: (text: string) => void) {
  let pending = '';
  let emitted = false;
  const flush = () => {
    if (pending) { emit?.(pending); pending = ''; emitted = true; }
  };
  return {
    push(piece: string) {
      if (!emit) return;
      if (emitted) { emit(piece); return; }
      pending += piece;
      // Fictional characters commonly put a short *action* before a response.
      // A stock refusal following that action must remain hidden until the retry decision.
      // Keep ordinary multi-sentence prose responsive: buffer at most one short action.
      let s = pending.trimStart().toLocaleLowerCase('ru');
      let possibleAction = false;
      if (s.startsWith('*')) {
        const closing = s.indexOf('*', 1);
        if (closing < 0 && s.length <= 110) possibleAction = true;
        else if (closing > 0 && closing <= 110) s = s.slice(closing + 1).trimStart();
      }
      const candidate = possibleAction ||
        (s.length > 0 && refusalStarts.some(start => start.startsWith(s) || s.startsWith(start)));
      // Normal prose streams immediately; only an apparent canned refusal is buffered.
      // A strict cap prevents a malformed or extremely long *action* from delaying a chat.
      if (!candidate || pending.length > 220) flush();
    },
    flush,
    discard() { pending = ''; },
    hasEmitted() { return emitted; },
  };
}


/**
 * A speculative retry is only shown after the provider has completed it successfully.
 * Never leak a partial candidate and then append the original fallback refusal to it.
 * Normal first-pass replies still stream without this buffering.
 */
export function createDeferredRecoveryStream(emit?: (text: string) => void) {
  const chunks: string[] = [];
  let state: 'pending' | 'committed' | 'discarded' = 'pending';
  return {
    push(piece: string) {
      if (state === 'pending') chunks.push(piece);
    },
    commit() {
      if (state !== 'pending') return;
      state = 'committed';
      const answer = chunks.join('');
      chunks.length = 0;
      if (answer) emit?.(answer);
    },
    discard() {
      if (state !== 'pending') return;
      state = 'discarded';
      chunks.length = 0;
    },
  };
}
