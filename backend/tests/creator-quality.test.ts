import { describe, it, expect } from 'vitest';
import { greetingProblems, scenarioProblems, speechStyleProblems, personalityProblems, assistantPhrases, englishWords, cardProblems, fieldProblems } from '../src/creatorQuality.js';

const goodGreeting = '*Вера не отрывает взгляда от треснувшей иконы и машет вам пинцетом.* Закрой дверь, сквозняк портит левкас. Ты принёс то, о чём писал?';
const goodScenario = 'Вы в мастерской реставрации на третьем этаже старого дома в Замоскворечье. Вера как раз вскрыла иконе обратную сторону и нашла там записку. Ты здесь с заказом, и теперь только тебе она может доверить тайну.';

describe('greetingProblems', () => {
  it('accepts an in-scene greeting', () => expect(greetingProblems(goodGreeting, 'Вера')).toEqual([]));
  it('flags self-introduction', () => {
    expect(greetingProblems('Привет, я Вера, реставратор икон. *улыбается* Чем могу помочь?', 'Вера')).toEqual(expect.arrayContaining(['greeting_introduces_self', 'greeting_assistant_phrase']));
    expect(greetingProblems('Меня зовут Вера. *кивает* Ты кто?', 'Вера')).toContain('greeting_introduces_self');
  });
  it('flags a start with the name', () => expect(greetingProblems('Вера поднимает глаза. *кивает* Ты кто?', 'Вера')).toContain('greeting_starts_with_name'));
  it('flags missing action, hook, long text and English', () => {
    expect(greetingProblems('Заходи, не стой в дверях.', 'Вера')).toEqual(expect.arrayContaining(['greeting_no_action', 'greeting_no_hook']));
    expect(greetingProblems('*кивает* ' + 'слово '.repeat(50) + '?', 'Вера')).toContain('greeting_too_long');
    expect(greetingProblems('*waves* Hello there, friend. Ты кто?', 'Вера')).toContain('greeting_english');
  });
  it('flags empty', () => expect(greetingProblems('  ', 'Вера')).toEqual(['greeting_empty']));
});

describe('scenarioProblems', () => {
  it('accepts a concrete scenario', () => expect(scenarioProblems(goodScenario)).toEqual([]));
  it('flags short, long, meta and first person', () => {
    expect(scenarioProblems('Вы в кафе.')).toContain('scenario_too_short');
    expect(scenarioProblems('Один. Два два два два. Три три три три. Четыре четыре четыре. Пять пять пять пять пять пять.')).toContain('scenario_too_long');
    expect(scenarioProblems('Сценарий: ' + goodScenario)).toContain('scenario_meta');
    expect(scenarioProblems('Я жду тебя в мастерской на третьем этаже старого дома. ' + 'Здесь пахнет лаком и пылью, за окном идёт дождь.')).toContain('scenario_first_person');
  });
});

describe('other helpers', () => {
  it('detects assistant phrases', () => {
    expect(assistantPhrases('Чем могу помочь?')).not.toEqual([]);
    expect(assistantPhrases('Как ИИ, я не могу')).not.toEqual([]);
    expect(assistantPhrases('Закрой дверь, дует.')).toEqual([]);
  });
  it('detects English words but allows the name', () => {
    expect(englishWords('Привет, Kai! Это hello world', 'Kai')).toEqual(['hello', 'world']);
    expect(englishWords('Просто русский текст')).toEqual([]);
  });
  it('requires speech examples', () => {
    expect(speechStyleProblems('Говорит медленно, с паузами. «Так-так», «ну-ну», любит слово «голубчик».')).toEqual([]);
    expect(speechStyleProblems('Говорит медленно и спокойно.')).toEqual(['speech_no_examples']);
  });
  it('rejects adjective-only personality', () => {
    expect(personalityProblems('добрая, умная, смелая, весёлая, честная')).toEqual(['personality_generic_list']);
    expect(personalityProblems('Педантична до занудства, но тайком кормит бродячих кошек. Хочет закончить реставрацию храма, боится слепоты.')).toEqual([]);
  });
  it('cardProblems and fieldProblems combine checks', () => {
    const bad = { name: 'Вера', personality: 'добрая, умная, смелая, весёлая', speechStyle: 'мягко', scenario: 'Кафе.', greeting: 'Привет, я Вера.' };
    expect(cardProblems(bad).length).toBeGreaterThan(4);
    expect(fieldProblems('greeting', goodGreeting, 'Вера')).toEqual([]);
    expect(fieldProblems('interests', 'Чем могу помочь', '')).toEqual(['assistant_phrase']);
    expect(fieldProblems('name', 'Вера')).toEqual([]);
  });
});

describe('gender cues', () => {
  it('reads the gender implied by a Russian idea', async () => {
    const { impliedGender } = await import('../src/creatorQuality.js');
    expect(impliedGender('Смотритель маяка на северном острове, бывший штурман')).toBe('male');
    expect(impliedGender('Хозяйка ночного книжного магазина в старом квартале')).toBe('female');
    expect(impliedGender('Уличный музыкант-саксофонист, который чинит велосипеды')).toBe('male');
    expect(impliedGender('Строгая шеф-повар маленького ресторана у вокзала')).toBe('female');
    expect(impliedGender('Шеф-повар у вокзала')).toBeUndefined();
  });
  it('flags greetings and scenarios that mark the player gender', async () => {
    const { marksPlayerGender, greetingProblems, scenarioProblems } = await import('../src/creatorQuality.js');
    for (const t of ['Здесь ты нашёл меня. Что привело?', 'Ты зашла в лавку.', 'Ну что, ты готов?', 'Ты пришел вовремя.']) expect(marksPlayerGender(t)).toBe(true);
    for (const t of ['Что ты ищешь?', 'Ты стоишь у двери.', 'Вы когда-нибудь пробовали розмарин?', 'Ты как раз вовремя.']) expect(marksPlayerGender(t)).toBe(false);
    expect(greetingProblems('*Поднимает взгляд* Ты нашёл меня. Что привело?', 'Виктор')).toContain('greeting_player_gender');
    expect(scenarioProblems('Ты зашёл в ночной магазин. Полки пахнут пылью и чаем. Хозяйка поднимает взгляд от книги.')).toContain('scenario_player_gender');
  });
});

describe('whom the card text is about', () => {
  it('reads the gender of the generated text and stays silent on mixed or missing cues', async () => {
    const { genderOfCardText } = await import('../src/creatorQuality.js');
    expect(genderOfCardText({ biography: 'Она открыла магазин и долго жила одна.', backstory: 'Её отец был моряком.' })).toBe('female');
    expect(genderOfCardText({ biography: 'Он двадцать лет водил суда, потом остался на острове.', personality: 'Его голос тих.' })).toBe('male');
    expect(genderOfCardText({ biography: 'Реставратор старых карт из Луногорска.' })).toBeUndefined();
    expect(genderOfCardText({ biography: 'Она пришла, он ушёл.' })).toBeUndefined();
  });
});
