import { google } from 'googleapis';
import type { PrismaClient } from '@prisma/client';
import { config } from './config.js';
import { createHash } from 'node:crypto';

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

  const api = publisher();
  const { data } = await api.purchases.products.get({packageName:config.playPackageName,productId:p.sku,token:p.purchaseToken});
  if(data.purchaseState===2)return {status:'PENDING' as const};
  if(data.purchaseState!==0)return {status:'CANCELED' as const};
  // Bind the receipt to the account that launched Play Billing, not the first claimant.
  const accountHash = createHash('sha256').update(p.userId).digest('hex');
  if(data.obfuscatedExternalAccountId!==accountHash)return {status:'REJECTED' as const,reason:'ACCOUNT_MISMATCH'};
  const result=await db.$transaction(async tx=>{
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${p.purchaseToken}, 0))`;
    const existing=await tx.purchaseEvent.findUnique({where:{purchaseToken:p.purchaseToken}});
    if(existing&&(existing.userId!==p.userId||existing.sku!==p.sku))return {status:'REJECTED' as const,reason:'TOKEN_OWNED_BY_OTHER'};
    if(existing?.state==='REFUNDED'||existing?.state==='CANCELED')return {status:'REJECTED' as const,reason:'PURCHASE_REVOKED'};
    if(existing?.state==='VERIFIED')return {status:'VERIFIED' as const,duplicate:true,tokens:existing.tokens};
    await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${p.userId} FOR UPDATE`;
    await tx.walletAccount.createMany({data:{userId:p.userId},skipDuplicates:true});
    await tx.purchaseEvent.create({data:{userId:p.userId,sku:p.sku,purchaseToken:p.purchaseToken,orderId:data.orderId,state:'VERIFIED',tokens:product.tokens}});
    await tx.walletTransaction.create({data:{userId:p.userId,bucket:'PURCHASED',type:'CREDIT_PURCHASE',amount:product.tokens,
      source:'play_billing',externalId:p.purchaseToken,idempotencyKey:'play:'+p.purchaseToken}});
    await tx.walletAccount.update({where:{userId:p.userId},data:{purchased:{increment:product.tokens}}});
    return {status:'VERIFIED' as const,duplicate:false,tokens:product.tokens};
  });
  // Retry consumption even after an earlier successful credit. A crash cannot strand a purchase.
  if(result.status==='VERIFIED'&&data.consumptionState!==1)await api.purchases.products.consume({packageName:config.playPackageName,productId:p.sku,token:p.purchaseToken});
  return result;
}

/**
 * Handles a refund/void notice (from RTDN voided-purchase events).
 * Reverses the tokens that are still unspent in the PURCHASED bucket; the shortfall is clamped at zero.
 */
export async function handleVoidedPurchase(db: PrismaClient, purchaseToken: string) {
  return db.$transaction(async tx=>{
    await tx.$executeRaw`SELECT pg_advisory_xact_lock(hashtextextended(${purchaseToken}, 0))`;
    const ev=await tx.purchaseEvent.findUnique({where:{purchaseToken}});
    if(!ev||ev.state!=='VERIFIED')return {handled:false};
    await tx.$queryRaw`SELECT id FROM "User" WHERE id = ${ev.userId} FOR UPDATE`;
    await tx.$queryRaw`SELECT "userId" FROM "WalletAccount" WHERE "userId" = ${ev.userId} FOR UPDATE`;
    const w=await tx.walletAccount.findUnique({where:{userId:ev.userId}});
    const revoke=Math.min(w?.purchased??0,ev.tokens);
    await tx.purchaseEvent.update({where:{purchaseToken},data:{state:'REFUNDED'}});
    if(revoke>0){
      await tx.walletAccount.update({where:{userId:ev.userId},data:{purchased:{decrement:revoke}}});
      await tx.walletTransaction.create({data:{userId:ev.userId,type:'DEBIT_PURCHASE_REVOKE',bucket:'PURCHASED',source:'play_refund',amount:revoke,externalId:purchaseToken,idempotencyKey:'play-refund:'+purchaseToken}});
    }
    // Spent refunded credit must not become a free repeat-purchase exploit.
    if(revoke<ev.tokens)await tx.user.update({where:{id:ev.userId},data:{status:'PAYMENT_REVIEW'}});
    return {handled:true};
  });
}
