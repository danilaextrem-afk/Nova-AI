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
it('explains which credential field is invalid without relaxing validation',async()=>{
 const post=(url:string,payload:any)=>app.inject({method:'POST',url,payload});
 for(const url of ['/v1/auth/login','/v1/auth/register']){
  const short=await post(url,{email:'adult@test.invalid',password:'short',adultConfirmed:true});expect(short.statusCode).toBe(400);expect(short.json().error).toBe('PASSWORD_TOO_SHORT');
  const long=await post(url,{email:'adult@test.invalid',password:'x'.repeat(129),adultConfirmed:true});expect(long.json().error).toBe('PASSWORD_TOO_LONG');
  const email=await post(url,{email:'not-an-email',password:'safe-test-password-123',adultConfirmed:true});expect(email.statusCode).toBe(400);expect(email.json().error).toBe('EMAIL_INVALID');
 }
 const noAdult=await post('/v1/auth/register',{email:randomUUID()+'@test.invalid',password:'safe-test-password-123'});expect(noAdult.json().error).toBe('ADULT_CONFIRMATION_REQUIRED');
 const email=(await db.user.findUnique({where:{id:userId}}))!.email;
 const spaced=await post('/v1/auth/login',{email:'  '+email.toUpperCase()+' ',password:'safe-test-password-123'});expect(spaced.statusCode,spaced.body).toBe(200);
 const wrong=await post('/v1/auth/login',{email,password:'wrong-password-123'});expect(wrong.statusCode).toBe(401);expect(wrong.json().error).toBe('INVALID_CREDENTIALS');
 const taken=await post('/v1/auth/register',{email,password:'safe-test-password-123',adultConfirmed:true});expect(taken.statusCode).toBe(409);expect(taken.json().error).toBe('EMAIL_TAKEN');
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
it('rejects unsigned and expired JWTs, including tokens with no issued-at claim',async()=>{
 const {createHmac}=await import('node:crypto');const {config}=await import('../src/config.js');
 const b64=(obj:unknown)=>Buffer.from(JSON.stringify(obj)).toString('base64url');
 const expired=b64({alg:'HS256',typ:'JWT'})+'.'+b64({sub:userId,exp:1});
 const signed=expired+'.'+createHmac('sha256',config.jwtSecret).update(expired).digest('base64url');
 const unsigned=b64({alg:'none'})+'.'+b64({sub:userId,exp:9999999999})+'.';
 for(const token of [signed,unsigned])expect((await app.inject({url:'/v1/me',headers:{authorization:'Bearer '+token}})).statusCode).toBe(401);
});
it('locks one account after repeated wrong passwords without affecting the correct owner later, and hides unknown emails',async()=>{
 const email=randomUUID()+'@test.invalid';
 const reg=await app.inject({method:'POST',url:'/v1/auth/register',payload:{email,password:'right-password-123',adultConfirmed:true}});expect(reg.statusCode).toBe(200);
 const created=(await db.user.findUnique({where:{email}}))!.id;
 const unknown=await app.inject({method:'POST',url:'/v1/auth/login',payload:{email:randomUUID()+'@test.invalid',password:'whatever-123456'}});
 expect(unknown.statusCode).toBe(401);expect(unknown.json().error).toBe('INVALID_CREDENTIALS');
 for(let i=0;i<8;i++){const r=await app.inject({method:'POST',url:'/v1/auth/login',payload:{email,password:'wrong-password-'+i},remoteAddress:'198.51.100.'+i});expect(r.statusCode).toBe(401);}
 const locked=await app.inject({method:'POST',url:'/v1/auth/login',payload:{email,password:'right-password-123'},remoteAddress:'203.0.113.9'});
 expect(locked.statusCode).toBe(429);expect(locked.json().error).toBe('TOO_MANY_LOGIN_ATTEMPTS');
 await db.user.delete({where:{id:created}});
});
