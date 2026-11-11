import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { createSign, generateKeyPairSync } from 'node:crypto';
import { verifySsv } from '../src/admobSsv.js';

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const publicDer = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const NOW = Date.now();

function signedCallback(options: { amount?: number; timestamp?: number; unit?: string } = {}) {
  const signed = new URLSearchParams({
    ad_unit: options.unit ?? 'ca-app-pub-test/unit-one',
    reward_amount: String(options.amount ?? 5),
    timestamp: String(options.timestamp ?? NOW),
    transaction_id: 'adtx-123',
    user_id: 'test-user',
  }).toString();
  const signer = createSign('SHA256');
  signer.update(signed);
  signer.end();
  const signature = signer.sign(privateKey).toString('base64url');
  return signed + '&signature=' + signature + '&key_id=999';
}
const opts = { expectedAdUnit: 'ca-app-pub-test/unit-one', expectedAmount: 5, maxAgeMs: 600000, now: NOW };

beforeAll(() => {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: true,
    json: async () => ({ keys: [{ keyId: 999, base64: publicDer }] }),
  })));
});

afterAll(() => vi.unstubAllGlobals());

describe('AdMob server-side signature verification', () => {
  it('accepts a matching signed callback', async () => {
    const result = await verifySsv(signedCallback(), opts);
    expect(result).toMatchObject({
      ok: true, transactionId: 'adtx-123', userId: 'test-user',
      amount: 5, adUnit: 'ca-app-pub-test/unit-one',
    });
  });

  it('rejects a modified rewarded amount after signing', async () => {
    const tampered = signedCallback().replace('reward_amount=5', 'reward_amount=500');
    expect(await verifySsv(tampered, opts)).toEqual({ ok: false, reason: 'BAD_SIGNATURE' });
  });

  it('rejects a valid signature for the wrong advertising unit', async () => {
    expect(await verifySsv(signedCallback({ unit: 'another-app' }), opts)).toEqual({
      ok: false, reason: 'AD_UNIT_MISMATCH',
    });
  });

  it('rejects a valid signature for the wrong reward amount', async () => {
    expect(await verifySsv(signedCallback({ amount: 500 }), opts)).toEqual({
      ok: false, reason: 'AMOUNT_MISMATCH',
    });
  });

  it('rejects expired but correctly signed callbacks', async () => {
    expect(await verifySsv(signedCallback({ timestamp: NOW - 700000 }), opts)).toEqual({
      ok: false, reason: 'EXPIRED',
    });
  });

  it('rejects missing signatures without fetching keys', async () => {
    expect(await verifySsv('reward_amount=5&user_id=test-user', opts)).toEqual({
      ok: false, reason: 'NO_SIGNATURE',
    });
  });
});
