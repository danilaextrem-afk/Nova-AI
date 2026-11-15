import { test } from 'node:test';
import assert from 'node:assert/strict';
import { inspectPreviewBackend } from './check-preview-deployment.mjs';

const ok = commit => async url => ({status:200, json:async()=>url.endsWith('/health')?{ok:true,commit}:{ok:true}});
test('matches exact deployed SHA when health and readiness succeed',async()=>{
  const r=await inspectPreviewBackend('https://preview.example.com/','a230ee779e17f7e2622372a07a173fae505707b4',ok('a230ee7'));
  assert.deepEqual(r,{status:'COMMIT_MATCHED',expected:'a230ee7',deployed:'a230ee7',healthHttp:200,readyHttp:200});
});
test('marks successful uptime with old commit as stale, never deployed',async()=>{
  const r=await inspectPreviewBackend('https://preview.example.com','a230ee779e17f7e2622372a07a173fae505707b4',ok('4605f3d'));
  assert.equal(r.status,'STALE_BACKEND');assert.equal(r.deployed,'4605f3d');
});
test('503 is unavailable even if a cached body says the correct commit',async()=>{
  const r=await inspectPreviewBackend('https://preview.example.com','a230ee7',
    async url=>({status:url.endsWith('/ready')?503:200,json:async()=>({ok:true,commit:'a230ee7'})}));
  assert.equal(r.status,'UNAVAILABLE');assert.equal(r.readyHttp,503);
});
test('missing or malformed commit is not considered deployed',async()=>{
  const r=await inspectPreviewBackend('https://preview.example.com','a230ee7',ok('unknown'));
  assert.equal(r.status,'UNVERIFIED');
});
test('errors are reported without throwing or disclosing details',async()=>{
  const r=await inspectPreviewBackend('https://preview.example.com','a230ee7',async()=>{throw new Error('private secret');});
  assert.equal(r.status,'UNAVAILABLE');assert.equal(r.healthHttp,0);assert.equal(r.readyHttp,0);
  assert.doesNotMatch(JSON.stringify(r),/private secret/);
});
test('rejects non-HTTPS, credentials, custom paths, and empty URLs',async()=>{
  let calls=0;const fetcher=async()=>{calls++;throw Error('Should not fetch');};
  for(const url of ['','http://preview.example.com','https://user:pw@preview.example.com',
    'https://preview.example.com/admin','https://preview.example.com/?key=secret']) {
    assert.equal((await inspectPreviewBackend(url,'a230ee7',fetcher)).status,'NOT_CONFIGURED');
  }
  assert.equal(calls,0);
});
