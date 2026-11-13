import { describe, expect, it } from 'vitest';
import { parseExtraction } from '../src/memoryEngine.js';

describe('Memory extraction JSON framing', () => {
  const result = {
    add: [{ kind: 'EVENT', content: 'Карта найдена у маяка.' }],
    summary: 'Сцена продолжается.',
  };
  const valid = JSON.stringify(result);

  it('accepts raw JSON and fenced JSON with surrounding prose', () => {
    const expected = { ...result, replace: [], character: [] };
    expect(parseExtraction(valid)).toEqual(expected);
    expect(parseExtraction('Result:\n```json\n' + valid + '\n```\nDone {not JSON}')).toEqual(expected);
  });

  it('preserves escaped quotes, backslashes, and braces inside strings', () => {
    const content = String.raw`Ключ {север}: "тихий берег" на диске C:\\NOVA\.`;
    const payload = JSON.stringify({ add: [{ kind: 'FACT', content }] });
    expect(parseExtraction(payload)?.add[0].content).toBe(content);
  });

  it('skips invalid objects before the valid extraction', () => {
    expect(parseExtraction('debug: {"ignored":true}\n' + valid)?.summary).toBe('Сцена продолжается.');
  });

  it.each([
    '{}',
    '{"ignored":true}',
    '{"summary":"Текст","ignored":true}',
    '{"add":[{"kind":"EVENT","content":"Нашли карту","ignored":true}]}',
    '{"add":[{"kind":"SECRET","content":"Нашли карту"}]}',
    '{"replace":[{"n":0,"content":"Нашли карту"}]}',
    '{"character":["  "]}',
    '{"summary":null}',
    '{"add":null}',
  ])('rejects objects outside the extraction schema: %s', (candidate) => {
    expect(parseExtraction(candidate)).toBeNull();
    expect(parseExtraction(candidate + '\n' + valid)?.summary).toBe(result.summary);
  });

  it('skips nested data in unrelated objects without treating it as an extraction', () => {
    const wrapper = JSON.stringify({ debug: { payload: result, entries: [{ text: '}' }] } });
    expect(parseExtraction(wrapper)).toBeNull();
    expect(parseExtraction(wrapper + '\n' + valid)?.add).toEqual(result.add);
  });

  it('accepts a summary alone and explicit empty results', () => {
    expect(parseExtraction('{"summary":"  Сцена продолжается.  "}')).toEqual({
      add: [], replace: [], character: [], summary: result.summary,
    });
    expect(parseExtraction('{"add":[],"replace":[],"character":[]}')).toEqual({
      add: [], replace: [], character: [], summary: undefined,
    });
  });

  it('rejects incomplete, malformed and oversized responses', () => {
    expect(parseExtraction('{"add":[{"kind":"FACT"')).toBeNull();
    expect(parseExtraction('not JSON {oops}')).toBeNull();
    expect(parseExtraction('{"add":,}\n' + valid)?.summary).toBe(result.summary);
    expect(parseExtraction(' '.repeat(20001) + valid)).toBeNull();
  });
});
