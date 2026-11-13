import {beforeAll,afterAll,it,expect} from 'vitest';
import {PrismaClient} from '@prisma/client';
import {randomUUID} from 'node:crypto';
import {buildApp,clientIpHeaderName} from '../src/server.js';
const db=new PrismaClient();let app:Awaited<ReturnType<typeof buildApp>>;let userId:string;let tokens:any;
beforeAll(async()=>{app=await buildApp(db);await app.ready();
 const email=randomUUID()+'@test.invalid';const response=await app.inject({method:'POST',url:'/v1/auth/register',payload:{email,password:'safe-test-password-123',adultConfirmed:true}});
 expect(response.statusCode,response.body).toBe(200);tokens=response.json();userId=(await db.user.findUnique({where:{email}}))!.id;
});
afterAll(async()=>{await db.user.deleteMany({where:{id:userId}});await app.close()});
it('rejects registration without explicit adult confirmation',async()=>{
 const result=await app.inject({method:'POST',url:'/v1/auth/register',payload:{email:randomUUID()+'@test.invalid',password:'safe-test-password-123'}});expect(result.statusCode).toBe(400);
});
it('returns wallet transaction history without internal purchase and deduplication identifiers', async () => {
 const purchaseToken = 'fixture-purchase-' + randomUUID();
 const transaction = await db.walletTransaction.create({data:{
  userId, type:'CREDIT_PURCHASE', bucket:'PURCHASED', source:'play_billing', amount:100,
  externalId:purchaseToken, idempotencyKey:'play:'+purchaseToken,
 }});
 try {
  const response = await app.inject({url:'/v1/wallet/transactions',headers:{authorization:'Bearer '+tokens.accessToken}});
  expect(response.statusCode).toBe(200);
  expect(response.json()).toMatchObject({items:[{id:transaction.id,amount:100,source:'play_billing',
   type:'CREDIT_PURCHASE',bucket:'PURCHASED',status:'COMPLETED',createdAt:transaction.createdAt.toISOString()}]});
  expect(response.json().items[0]).not.toHaveProperty('externalId');
  expect(response.json().items[0]).not.toHaveProperty('idempotencyKey');
  expect(response.body).not.toContain(purchaseToken);
 } finally {
  await db.walletTransaction.delete({where:{id:transaction.id}});
 }
});
it('accepts only the account owner’s wallet cursor and validates page size',async()=>{
 const foreign = await db.user.create({data:{email:randomUUID()+'@test.invalid',passwordHash:'test'}});
 const first = randomUUID(), second = randomUUID(), third = randomUUID(), foreignId = randomUUID();
 const createdAt = new Date('2026-11-01T00:00:00.000Z');
 const ids = [first, second, third, foreignId];
 try {
  await db.walletTransaction.createMany({data:ids.map(id=>({
   id,userId:id===foreignId?foreign.id:userId,type:'CREDIT_BONUS',bucket:'BONUS',source:'fixture',
   amount:1,idempotencyKey:'fixture:'+id,createdAt,
  }))});
  const history = (query:string) => app.inject({url:'/v1/wallet/transactions?'+query,
   headers:{authorization:'Bearer '+tokens.accessToken}});
  expect((await history('limit=999')).statusCode).toBe(400);
  expect((await history('cursor=bad')).statusCode).toBe(400);
  expect((await history('cursor='+foreignId)).statusCode).toBe(404);
  expect((await history('cursor='+randomUUID())).statusCode).toBe(404);
  const page = (await history('limit=2')).json();
  const ordered = [first, second, third].sort().reverse();
  expect(page.items.map((item:{id:string})=>item.id)).toEqual(ordered.slice(0,2));
  expect(page.nextCursor).toBe(ordered[1]);
  const last = (await history('limit=2&cursor='+page.nextCursor)).json();
  expect(last.items.map((item:{id:string})=>item.id)).toEqual(ordered.slice(2));
  expect(last.nextCursor).toBeNull();
 } finally {
  await db.walletTransaction.deleteMany({where:{id:{in:ids}}});
  await db.user.delete({where:{id:foreign.id}});
 }
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
// A real session row plus its access token, as sign-in issues them (no /login call: that route is rate limited).
async function newSession(){
 const {createHash,randomBytes}=await import('node:crypto');
 const refreshToken=randomBytes(48).toString('base64url'),familyId=randomUUID();
 await db.session.create({data:{userId,familyId,refreshTokenHash:createHash('sha256').update(refreshToken).digest('hex'),expiresAt:new Date(Date.now()+86400000)}});
 return {accessToken:app.jwt.sign({sub:userId,fam:familyId}),refreshToken};
}
it('revokes the access token of a family as soon as its refresh token is reused',async()=>{
 // The rotation test above reused a refresh token, which revoked that whole sign-in family.
 expect((await app.inject({url:'/v1/me',headers:{authorization:'Bearer '+tokens.accessToken}})).statusCode).toBe(401);
 tokens=await newSession();
 expect((await app.inject({url:'/v1/me',headers:{authorization:'Bearer '+tokens.accessToken}})).statusCode).toBe(200);
});
it('logout ends every session at once: access tokens and refresh tokens stop working',async()=>{
 const phone=await newSession(),tablet=await newSession();
 const refreshed=(await app.inject({method:'POST',url:'/v1/auth/refresh',payload:{refreshToken:phone.refreshToken}})).json();
 expect((await app.inject({url:'/v1/me',headers:{authorization:'Bearer '+refreshed.accessToken}})).statusCode).toBe(200);
 expect((await app.inject({method:'POST',url:'/v1/auth/logout',headers:{authorization:'Bearer '+refreshed.accessToken}})).statusCode).toBe(200);
 for(const token of [refreshed.accessToken,tablet.accessToken,tokens.accessToken])
  expect((await app.inject({url:'/v1/wallet',headers:{authorization:'Bearer '+token}})).statusCode).toBe(401);
 for(const refreshToken of [refreshed.refreshToken,tablet.refreshToken])
  expect((await app.inject({method:'POST',url:'/v1/auth/refresh',payload:{refreshToken}})).statusCode).toBe(401);
 tokens=await newSession();
 expect((await app.inject({url:'/v1/wallet',headers:{authorization:'Bearer '+tokens.accessToken}})).statusCode).toBe(200);
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

it('keys rate limits on the edge client IP on Render even when the variable is not set',()=>{
 expect(clientIpHeaderName({RENDER:'true'})).toBe('true-client-ip');
 expect(clientIpHeaderName({})).toBe('');
 expect(clientIpHeaderName({RENDER:'true',NOVA_CLIENT_IP_HEADER:'none'})).toBe('');
 expect(clientIpHeaderName({NOVA_CLIENT_IP_HEADER:' CF-Connecting-IP '})).toBe('cf-connecting-ip');
});
