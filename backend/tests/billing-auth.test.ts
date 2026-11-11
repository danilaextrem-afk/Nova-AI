import {beforeAll,afterAll,beforeEach,it,expect,vi} from 'vitest';
import {randomUUID,createHash} from 'node:crypto';
import {PrismaClient} from '@prisma/client';
const mocks=vi.hoisted(()=>({get:vi.fn(),consume:vi.fn()}));
vi.mock('googleapis',()=>({google:{auth:{GoogleAuth:class{}},androidpublisher:()=>({purchases:{products:mocks}})}}));
import {config} from '../src/config.js';
import {verifyAndCreditPurchase,handleVoidedPurchase} from '../src/billing.js';
import {balance} from '../src/ledger.js';
const db=new PrismaClient();let userId:string,other:string;
beforeAll(async()=>{
 config.playPackageName='ai.nova.app';config.playServiceAccountJson='{}';
 userId=(await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test'}})).id;
 other=(await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test'}})).id;
 await db.paymentProduct.upsert({where:{sku:'fixture_tokens_100'},create:{sku:'fixture_tokens_100',tokens:100},update:{}});
});
beforeEach(()=>{mocks.get.mockReset();mocks.consume.mockReset();mocks.consume.mockResolvedValue({});mocks.get.mockResolvedValue({data:{purchaseState:0,consumptionState:0,obfuscatedExternalAccountId:createHash('sha256').update(userId).digest('hex')}})});
afterAll(async()=>{await db.user.deleteMany({where:{id:{in:[userId,other]}}});await db.paymentProduct.delete({where:{sku:'fixture_tokens_100'}});await db.$disconnect()});
it('credits one purchase once under concurrency and retries consumption after failure',async()=>{
 const token=randomUUID();const p={userId,sku:'fixture_tokens_100',purchaseToken:token};const before=await balance(db,userId);
 mocks.consume.mockRejectedValueOnce(new Error('network timeout'));
 await expect(verifyAndCreditPurchase(db,p)).rejects.toThrow('network timeout');
 const all=await Promise.all(Array.from({length:8},()=>verifyAndCreditPurchase(db,p)));
 expect(all.every(x=>x.status==='VERIFIED')).toBe(true);expect((await balance(db,userId)).total).toBe(before.total+100);
 expect(await db.walletTransaction.count({where:{externalId:token}})).toBe(1);expect(mocks.consume.mock.calls.length).toBe(9);
});
it('rejects a receipt whose Play account binding belongs to another user',async()=>{
 const r=await verifyAndCreditPurchase(db,{userId:other,sku:'fixture_tokens_100',purchaseToken:randomUUID()});expect(r).toMatchObject({status:'REJECTED',reason:'ACCOUNT_MISMATCH'});
});
it('reverses a verified purchase exactly once under parallel refund callbacks',async()=>{
 const token=randomUUID();await verifyAndCreditPurchase(db,{userId,sku:'fixture_tokens_100',purchaseToken:token});const before=await balance(db,userId);
 await Promise.all(Array.from({length:10},()=>handleVoidedPurchase(db,token)));
 expect((await balance(db,userId)).total).toBe(before.total-100);
 expect(await db.walletTransaction.count({where:{source:'play_refund',externalId:token}})).toBe(1);
 expect(await verifyAndCreditPurchase(db,{userId,sku:'fixture_tokens_100',purchaseToken:token})).toMatchObject({status:'REJECTED',reason:'PURCHASE_REVOKED'});
});
