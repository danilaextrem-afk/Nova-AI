import {beforeAll,afterAll,it,expect} from 'vitest';
import {PrismaClient} from '@prisma/client';
import {randomUUID} from 'node:crypto';
import {buildApp} from '../src/server.js';
const db=new PrismaClient();let app:Awaited<ReturnType<typeof buildApp>>;let userId:string;let tokens:any;
beforeAll(async()=>{app=await buildApp(db);await app.ready();
 const email=randomUUID()+'@test.invalid';const response=await app.inject({method:'POST',url:'/v1/auth/register',payload:{email,password:'safe-test-password-123',adultConfirmed:true}});
 expect(response.statusCode,response.body).toBe(200);tokens=response.json();userId=(await db.user.findUnique({where:{email}}))!.id;
});
afterAll(async()=>{await db.user.deleteMany({where:{id:userId}});await app.close()});
it('rejects registration without explicit adult confirmation',async()=>{
 const result=await app.inject({method:'POST',url:'/v1/auth/register',payload:{email:randomUUID()+'@test.invalid',password:'safe-test-password-123'}});expect(result.statusCode).toBe(400);
});
it('rotates a refresh token once and revokes the family on concurrent reuse',async()=>{
 const results=await Promise.all(Array.from({length:2},()=>app.inject({method:'POST',url:'/v1/auth/refresh',payload:{refreshToken:tokens.refreshToken}})));
 expect(results.map(x=>x.statusCode).sort()).toEqual([200,401]);
 const good=results.find(x=>x.statusCode===200)!.json();
 const reused=await app.inject({method:'POST',url:'/v1/auth/refresh',payload:{refreshToken:good.refreshToken}});expect(reused.statusCode).toBe(401);
});
it('does not expose secrets in data export and rejects a blocked user access token',async()=>{
 const result=await app.inject({url:'/v1/me/export',headers:{authorization:'Bearer '+tokens.accessToken}});
 expect(result.statusCode).toBe(200);expect(result.body).not.toContain('passwordHash');expect(result.body).not.toContain('refreshToken');
 await db.user.update({where:{id:userId},data:{status:'BLOCKED'}});
 const blocked=await app.inject({url:'/v1/wallet',headers:{authorization:'Bearer '+tokens.accessToken}});expect(blocked.statusCode).toBe(401);
 await db.user.update({where:{id:userId},data:{status:'ACTIVE'}});
});
it('keeps real payment verification disabled until explicitly activated',async()=>{
 const result=await app.inject({method:'POST',url:'/v1/billing/verify',headers:{authorization:'Bearer '+tokens.accessToken},payload:{sku:'nova_tokens_100',purchaseToken:'untrusted-client-receipt'}});expect(result.statusCode).toBe(503);expect(result.json().error).toBe('BILLING_DISABLED');
});
it('preserves client-error status without leaking parser errors or request contents',async()=>{
 const malformed=await app.inject({method:'POST',url:'/v1/auth/login',headers:{'content-type':'application/json'},payload:'{private:'});expect(malformed.statusCode).toBe(400);expect(malformed.body).not.toContain('private');
 const large=await app.inject({method:'POST',url:'/v1/auth/login',payload:{email:'adult@test.invalid',password:'x'.repeat(70000)}});expect(large.statusCode).toBe(413);
});
