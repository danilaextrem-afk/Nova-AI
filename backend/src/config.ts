function req(name: string): string {
  const v = process.env[name];
  if (name==='JWT_SECRET'&&v&&v.length<32&&process.env.NODE_ENV==='production')throw new Error('JWT_SECRET must contain at least 32 characters');
  if (!v) throw new Error(`Missing required env var ${name}`);
  return v;
}

export const config = {
  port: Number(process.env.PORT ?? 8080),
  jwtSecret: req('JWT_SECRET'),
  accessTtlSec: 15 * 60,
  refreshTtlSec: 30 * 24 * 3600,
  playPackageName: process.env.PLAY_PACKAGE_NAME ?? '',
  // Service-account JSON (raw JSON string). Kept on the server only.
  playServiceAccountJson: process.env.PLAY_SERVICE_ACCOUNT_JSON ?? '',
  admob: {
    adUnit: process.env.ADMOB_REWARDED_AD_UNIT ?? '',
    rewardAmount: Number(process.env.ADMOB_REWARD_AMOUNT ?? 5),
    maxAgeMs: 10 * 60_000,
    dailyLimit: Number(process.env.ADMOB_DAILY_LIMIT ?? 10),
    minIntervalSec: Number(process.env.ADMOB_MIN_INTERVAL_SEC ?? 60),
  },
};

/** SKU -> tokens. Authoritative copy is the PaymentProduct table; this seeds it. */
export const PRODUCT_SEED: Record<string, number> = {
  nova_tokens_100: 100,
  nova_tokens_300: 300,
  nova_tokens_700: 700,
  nova_tokens_1500: 1500,
  nova_tokens_3500: 3500,
};
