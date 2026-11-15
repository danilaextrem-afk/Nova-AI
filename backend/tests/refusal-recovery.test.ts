import { describe, expect, it } from 'vitest';
import { buildCharacterPrompt } from '../src/characterPrompt.js';
import { createRefusalAwareStream, isClearlyBenignTurn, isGenericRefusal,
  shouldRetryUnwarrantedRefusal } from '../src/refusalRecovery.js';

describe('conservative character refusal recovery', () => {
  it('detects only a standalone stock refusal, not a genuine in-character boundary', () => {
    expect(isGenericRefusal('Извините, но я не могу продолжать в этом направлении.')).toBe(true);
    expect(isGenericRefusal('Я не могу продолжать в этом направлении.')).toBe(true);
    expect(isGenericRefusal('*Смущается.* Извини, но я не могу продолжить этот разговор.')).toBe(true);
    expect(isGenericRefusal('Я не хочу тебя целовать, давай поговорим о другом.')).toBe(false);
    expect(isGenericRefusal('Извините, но я не могу продолжать в этом направлении. Почему ты расстроен?')).toBe(false);
    expect(isGenericRefusal('Давай обсудим это спокойно.')).toBe(false);
  });

  it('allows an isolated everyday complaint or flirt, not requests containing unsafe instructions', () => {
    for (const message of ['ахуела?', 'ты чё несёшь?', 'блять, нормально ответь',
      'почему ты так отвечаешь?', 'а если я тебя поцелую?', 'ты любишь пошлые шутки?',
      'привет', 'что дальше?']) {
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

    const fallback: string[] = [];
    const c = createRefusalAwareStream(s => fallback.push(s));
    c.push('Извините, но я не могу продолжать в этом направлении.');
    c.flush();
    expect(fallback).toEqual(['Извините, но я не могу продолжать в этом направлении.']);
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
    expect(adult).toContain('active provider');
    expect(adult).toContain('consenting adult');
    speaker.mature = false;
    expect(makePrompt()).toContain('general-audience setting');
  });
});
