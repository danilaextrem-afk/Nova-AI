import { google } from 'googleapis';
import type { PrismaClient } from '@prisma/client';
import { config } from './config.js';
import { credit } from './ledger.js';

function publisher() {
  if (!config.playServiceAccountJson || !config.playPackageName) {
    throw new Error('PLAY_BILLING_NOT_CONFIGURED');
  }
  const auth = new google.auth.GoogleAuth({
    credentials: JSON.parse(config.playServiceAccountJson),
    scopes: ['https://www.googleapis.com/auth/androidpublisher'],
  });
  return google.androidpublisher({ version: 'v3', auth });
}

/**
 * Verifies a consumable purchase with Google Play (never trusts the client),
 * credits tokens exactly once, then consumes the purchase.
 * purchaseState: 0 = purchased, 1 = canceled, 2 = pending.
 */
export async function verifyAndCreditPurchase(
  db: PrismaClient,
  p: { userId: string; sku: string; purchaseToken: string },
) {
  const product = await db.paymentProduct.findUnique({ where: { sku: p.sku } });
  if (!product || !product.active) return { status: 'REJECTED' as const, reason: 'UNKNOWN_SKU' };

  // A purchase token may belong to only one user.
  const existing = await db.purchaseEvent.findUnique({ where: { purchaseToken: p.purchaseToken } });
  if (existing && existing.userId !== p.userId) return { status: 'REJECTED' as const, reason: 'TOKEN_OWNED_BY_OTHER' };
  if (existing?.state === 'VERIFIED') return { status: 'VERIFIED' as const, duplicate: true, tokens: existing.tokens };

  const api = publisher();
  const { data } = await api.purchases.products.get({
    packageName: config.playPackageName,
    productId: p.sku,
    token: p.purchaseToken,
  });

  if (data.purchaseState === 2) return { status: 'PENDING' as const };
  if (data.purchaseState !== 0) {
    await db.purchaseEvent.upsert({
      where: { purchaseToken: p.purchaseToken },
      create: { userId: p.userId, sku: p.sku, purchaseToken: p.purchaseToken, state: 'CANCELED', tokens: 0 },
      update: { state: 'CANCELED' },
    });
    return { status: 'CANCELED' as const };
  }

  await db.purchaseEvent.upsert({
    where: { purchaseToken: p.purchaseToken },
    create: {
      userId: p.userId,
      sku: p.sku,
      purchaseToken: p.purchaseToken,
      orderId: data.orderId ?? null,
      state: 'VERIFIED',
      tokens: product.tokens,
    },
    update: { state: 'VERIFIED', orderId: data.orderId ?? null, tokens: product.tokens },
  });

  const { duplicate } = await credit(db, {
    userId: p.userId,
    bucket: 'PURCHASED',
    amount: product.tokens,
    source: 'play_billing',
    externalId: p.purchaseToken,
    idempotencyKey: `play:${p.purchaseToken}`,
  });

  // Consume only after the credit is committed, so the item can be bought again.
  if (data.consumptionState !== 1) {
    await api.purchases.products.consume({
      packageName: config.playPackageName,
      productId: p.sku,
      token: p.purchaseToken,
    });
  }
  return { status: 'VERIFIED' as const, duplicate, tokens: product.tokens };
}

/**
 * Handles a refund/void notice (from RTDN voided-purchase events).
 * Reverses the tokens that are still unspent in the PURCHASED bucket; the shortfall is clamped at zero.
 */
export async function handleVoidedPurchase(db: PrismaClient, purchaseToken: string) {
  const ev = await db.purchaseEvent.findUnique({ where: { purchaseToken } });
  if (!ev || ev.state === 'REFUNDED') return { handled: false };
  await db.$transaction(async (tx) => {
    await tx.purchaseEvent.update({ where: { purchaseToken }, data: { state: 'REFUNDED' } });
    const w = await tx.walletAccount.findUnique({ where: { userId: ev.userId } });
    const revoke = Math.min(w?.purchased ?? 0, ev.tokens);
    if (revoke > 0) {
      await tx.walletAccount.update({ where: { userId: ev.userId }, data: { purchased: { decrement: revoke } } });
      await tx.walletTransaction.create({
        data: {
          userId: ev.userId,
          type: 'DEBIT_PURCHASE_REVOKE',
          bucket: 'PURCHASED',
          source: 'play_refund',
          amount: revoke,
          externalId: purchaseToken,
          idempotencyKey: `play-refund:${purchaseToken}`,
        },
      });
    }
  });
  return { handled: true };
}
