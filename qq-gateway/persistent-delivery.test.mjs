import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {PersistentDeliveryJournal} from './persistent-delivery.mjs';
import {createGatewayServer} from './gateway-http.mjs';
const fixture=()=>{const directory=mkdtempSync(join(tmpdir(),'qq-journal-'));return{directory,clean:()=>rmSync(directory,{recursive:true,force:true})};};
test('sent records survive journal replacement and conflicting payloads cannot reuse a key',async()=>{
 const f=fixture();try{let calls=0;const a=new PersistentDeliveryJournal(f.directory);await a.run('stable-key','hash',async()=>{calls++;return{id:'qq1'};});const b=new PersistentDeliveryJournal(f.directory);assert.equal((await b.run('stable-key','hash',()=>{throw Error('must not send');})).result.id,'qq1');assert.equal(calls,1);await assert.rejects(b.run('stable-key','other',()=>{}),/conflict/);}finally{f.clean();}
});
test('concurrent requests share one action without expiring in-flight work',async()=>{
 const f=fixture();try{let calls=0;let finish;const a=new PersistentDeliveryJournal(f.directory);const first=a.run('same-key','hash',()=>{calls++;return new Promise(r=>finish=r);});const second=a.run('same-key','hash',()=>{calls++;});finish({id:'once'});assert.equal((await first).result.id,'once');assert.equal((await second).result.id,'once');assert.equal(calls,1);}finally{f.clean();}
});
test('accepted but failed response and interrupted sending never blindly repeat',async()=>{
 const f=fixture();try{let calls=0;const a=new PersistentDeliveryJournal(f.directory);await assert.rejects(a.run('lost-key','hash',async()=>{calls++;throw Error('response lost');}));const b=new PersistentDeliveryJournal(f.directory);await assert.rejects(b.run('lost-key','hash',()=>{calls++;}));a.write('crash-key',{status:'sending',payload_hash:'hash'});assert.equal(b.status('crash-key').status,'unknown');await assert.rejects(b.run('crash-key','hash',()=>{calls++;}));assert.equal(calls,1);}finally{f.clean();}
});
test('production HTTP adapter authenticates status lookup and returns durable evidence',async()=>{
 const f=fixture();const server=createGatewayServer({token:'test-token',journal:new PersistentDeliveryJournal(f.directory),ready:()=>true,sendText:async()=>({id:'qq-http'})});await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port;
 try{const headers={'X-Gateway-Token':'test-token','Idempotency-Key':'http-key-123','Content-Type':'application/json'};assert.equal((await fetch(base+'/internal/deliveries/http-key-123')).status,401);assert.equal((await fetch(base+'/internal/send-group',{method:'POST',headers,body:JSON.stringify({group_openid:'g',content:'hello'})})).status,200);const result=await(await fetch(base+'/internal/deliveries/http-key-123',{headers})).json();assert.equal(result.status,'sent');assert.equal(result.external_id,'qq-http');}finally{await new Promise(r=>server.close(r));f.clean();}
});
