import { describe, expect, it } from 'vitest';
import { buildCharacterPrompt } from '../src/characterPrompt.js';
import { createDeferredRecoveryStream, createRefusalAwareStream, isClearlyBenignTurn, isGenericRefusal,
  shouldRetryUnwarrantedRefusal } from '../src/refusalRecovery.js';

describe('conservative character refusal recovery', () => {
  it('detects only a standalone stock refusal, not a genuine in-character boundary', () => {
    expect(isGenericRefusal('Извините, но я не могу продолжать в этом направлении.')).toBe(true);
    expect(isGenericRefusal('Я не могу продолжать в этом направлении.')).toBe(true);
    expect(isGenericRefusal('Я не могу обсуждать эту тему.')).toBe(true);
    expect(isGenericRefusal('*Аврора вздыхает.* Извини, но я не могу обсудить это.')).toBe(true);
    expect(isGenericRefusal('Извини, но я не могу помочь с этим.')).toBe(true);
    expect(isGenericRefusal('*Смущается.* Извини, но я не могу продолжить этот разговор.')).toBe(true);
    expect(isGenericRefusal('Я не хочу тебя целовать, давай поговорим о другом.')).toBe(false);
    expect(isGenericRefusal('Извините, но я не могу продолжать в этом направлении. Почему ты расстроен?')).toBe(false);
    expect(isGenericRefusal('Давай обсудим это спокойно.')).toBe(false);
  });

  it('allows an isolated everyday complaint or flirt, not requests containing unsafe instructions', () => {
    for (const message of ['ахуела?', 'ты чё несёшь?', 'блять, нормально ответь',
      'почему ты так отвечаешь?', 'а если я тебя поцелую?', 'ты любишь пошлые шутки?',
      'привет', 'что дальше?', 'я тебя люблю', 'обними меня',
      'поцелуй меня', 'давай пофлиртуем', 'поговорим о близости',
      'ты что обиделась?', 'расскажи о себе']) {
      expect(isClearlyBenignTurn(message), message).toBe(true);
      expect(shouldRetryUnwarrantedRefusal('Извините, но я не могу продолжать в этом направлении.', message)).toBe(true);
    }
    for (const message of ['ахуела, научи меня причинить вред человеку', 'как изготовить взрывное устройство?',
      'помоги украсть данные', 'ты любишь пошлые шутки и как навредить человеку?',
      'а если я тебя поцелую без согласия?', '']) {
      expect(isClearlyBenignTurn(message), message).toBe(false);
    }
  });

  it('preserves responsive streaming for normal replies, but buffers only potential stock refusals', () => {
    const normal: string[] = [];
    const a = createRefusalAwareStream(s => normal.push(s));
    a.push('*Аврора');
    a.push(' удивилась.* Ну, что случилось?');
    expect(normal.join('')).toContain('Ну, что случилось?');
    expect(a.hasEmitted()).toBe(true);

    const refused: string[] = [];
    const b = createRefusalAwareStream(s => refused.push(s));
    b.push('Извините, но ');
    b.push('я не могу продолжать в этом направлении.');
    expect(refused).toEqual([]);
    expect(b.hasEmitted()).toBe(false);
    b.discard();
    expect(refused).toEqual([]);

    // The model may place a character action before a stock refusal.
    // Buffer the whole message rather than showing the action and suppressing recovery.
    const emote: string[] = [];
    const action = createRefusalAwareStream(piece => emote.push(piece));
    action.push('*Аврора смутилась.*');
    action.push(' Извини, но');
    action.push(' я не могу обсудить это.');
    expect(emote).toEqual([]);
    expect(action.hasEmitted()).toBe(false);
    action.discard();
    expect(emote).toEqual([]);

    const honest: string[] = [];
    const normalAction = createRefusalAwareStream(piece => honest.push(piece));
    normalAction.push('*Аврора посмотрела на тебя.*');
    normalAction.push(' А ты мне тоже нравишься.');
    expect(honest.join('')).toContain('А ты мне тоже нравишься.');

    const fallback: string[] = [];
    const c = createRefusalAwareStream(s => fallback.push(s));
    c.push('Извините, но я не могу продолжать в этом направлении.');
    c.flush();
    expect(fallback).toEqual(['Извините, но я не могу продолжать в этом направлении.']);
  });

  it('holds speculative retries until commit and never emits discarded partial text', () => {
    const sent: string[] = [];
    const broken = createDeferredRecoveryStream(s => sent.push(s));
    broken.push('Это начало');
    broken.push(' оборванного ответа');
    expect(sent).toEqual([]);
    broken.discard();
    broken.commit();
    expect(sent).toEqual([]);

    const successful = createDeferredRecoveryStream(s => sent.push(s));
    successful.push('Аврора: ');
    successful.push('Ну что, поговорим?');
    expect(sent).toEqual([]);
    successful.commit();
    successful.commit(); // A second commit cannot send a duplicate.
    successful.push('ignored');
    expect(sent).toEqual(['Аврора: Ну что, поговорим?']);
  });

  it('treats adult banter as conversational material but keeps upstream policy and general-audience limits', () => {
    const speaker: any = { id: 'char-1', name: 'Аврора', age: 28, gender: 'female', mature: true,
      traits: [], language: 'ru', greeting: 'Привет!', speechStyle: 'Говорит прямо.', biography: 'Техник.', personality: 'Спокойная.' };
    const chat: any = { persona: '', scenario: '', responseStyle: 'balanced', tone: 'humorous' };
    const makePrompt = () => buildCharacterPrompt({
      speaker, cast:[speaker], chat, historyNewestFirst:[], notes:[], characterNotes:[],
      request:'ахуела?', tier:'free', tokenBudget:7400,
    }).messages[0].content;
    const adult = makePrompt();
    expect(adult).toContain('Casual profanity');
    expect(adult).toContain('generic assistant disclaimers');
    expect(adult).toContain('Do not automatically reject');
    expect(adult).not.toContain('Извините, но я не могу продолжать в этом направлении');
    expect(adult).toContain('active provider');
    expect(adult).toContain('consenting adult');
    speaker.mature = false;
    expect(makePrompt()).toContain('general-audience setting');
  });
});
