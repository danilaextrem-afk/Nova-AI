import { describe, expect, it } from 'vitest';
import { parseExtraction } from '../src/memoryEngine.js';

describe('Memory extraction JSON framing', () => {
  const valid = '{"add":[{"kind":"EVENT","content":"Карта найдена у маяка."}],"summary":"Сцена продолжается."}';

  it('accepts raw JSON and fenced JSON with surrounding prose', () => {
    expect(parseExtraction(valid)?.add[0].content).toBe('Карта найдена у маяка.');
    const wrapped = 'Result:\n' + '```json\n' + valid + '\n```\n' + 'Done {not JSON}';
    expect(parseExtraction(wrapped)?.summary).toBe('Сцена продолжается.');
  });

  it('ignores braces inside strings and escaped quotes', () => {
    const payload = JSON.stringify({add:[{kind:'FACT',content:'Пароль {север} и фраза "тихий берег".'}],summary:'Новая глава.'});
    expect(parseExtraction(payload)?.add[0].content).toContain('{север}');
  });

  it('skips invalid objects before the valid extraction', () => {
    expect(parseExtraction('debug: {"ignored":true}\n' + valid)?.summary).toBe('Сцена продолжается.');
  });

  it('rejects incomplete, malformed and oversized responses', () => {
    expect(parseExtraction('{"add":[{"kind":"FACT"')).toBeNull();
    expect(parseExtraction('not JSON {oops}')).toBeNull();
    expect(parseExtraction(' '.repeat(20001) + valid)).toBeNull();
  });
});
