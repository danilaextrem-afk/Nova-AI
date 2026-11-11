import { createPublicKey, createVerify } from 'node:crypto';

const KEYS_URL = 'https://www.gstatic.com/admob/reward/verifier-keys.json';
const KEYS_TTL_MS = 6 * 3600_000;

let cache: { at: number; keys: Map<string, string> } | null = null;

async function loadKeys(force = false): Promise<Map<string, string>> {
  if (!force && cache && Date.now() - cache.at < KEYS_TTL_MS) return cache.keys;
  const res = await fetch(KEYS_URL);
  if (!res.ok) throw new Error(`SSV_KEYS_FETCH_${res.status}`);
  const json = (await res.json()) as { keys: { keyId: number; base64: string }[] };
  const keys = new Map(json.keys.map((k) => [String(k.keyId), k.base64]));
  cache = { at: Date.now(), keys };
  return keys;
}

function b64urlToBuf(s: string) {
  return Buffer.from(s.replace(/-/g, '+').replace(/_/g, '/'), 'base64');
}

export type SsvResult =
  | { ok: true; transactionId: string; userId: string; amount: number; adUnit: string; timestampMs: number }
  | { ok: false; reason: string };

/**
 * Verifies an AdMob server-side-verification callback.
 * `rawQuery` is the raw query string exactly as received (no leading "?").
 * The signed content is everything before "&signature=".
 */
export async function verifySsv(
  rawQuery: string,
  opts: { expectedAdUnit?: string; expectedAmount: number; maxAgeMs: number; now?: number },
): Promise<SsvResult> {
  const sigIdx = rawQuery.indexOf('&signature=');
  if (sigIdx < 0) return { ok: false, reason: 'NO_SIGNATURE' };
  const signed = rawQuery.slice(0, sigIdx);
  const params = new URLSearchParams(rawQuery);
  const signature = params.get('signature');
  const keyId = params.get('key_id');
  if (!signature || !keyId) return { ok: false, reason: 'NO_SIGNATURE' };

  let keys = await loadKeys();
  if (!keys.has(keyId)) keys = await loadKeys(true); // Google may have rotated keys
  const der = keys.get(keyId);
  if (!der) return { ok: false, reason: 'UNKNOWN_KEY' };

  const verifier = createVerify('SHA256');
  verifier.update(signed);
  const valid = verifier.verify(
    createPublicKey({ key: Buffer.from(der, 'base64'), format: 'der', type: 'spki' }),
    b64urlToBuf(signature),
  );
  if (!valid) return { ok: false, reason: 'BAD_SIGNATURE' };

  const transactionId = params.get('transaction_id');
  const userId = params.get('user_id');
  const adUnit = params.get('ad_unit') ?? '';
  const amount = Number(params.get('reward_amount'));
  const timestampMs = Number(params.get('timestamp'));
  if (!transactionId || !userId) return { ok: false, reason: 'MISSING_FIELDS' };
  if (!Number.isFinite(timestampMs)) return { ok: false, reason: 'BAD_TIMESTAMP' };
  if (Math.abs((opts.now ?? Date.now()) - timestampMs) > opts.maxAgeMs) return { ok: false, reason: 'EXPIRED' };
  if (opts.expectedAdUnit && adUnit !== opts.expectedAdUnit) return { ok: false, reason: 'AD_UNIT_MISMATCH' };
  if (amount !== opts.expectedAmount) return { ok: false, reason: 'AMOUNT_MISMATCH' };

  return { ok: true, transactionId, userId, amount, adUnit, timestampMs };
}
