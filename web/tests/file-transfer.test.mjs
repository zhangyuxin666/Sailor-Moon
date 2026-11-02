import test from 'node:test';
import assert from 'node:assert/strict';
import { loadTypeScript } from './load-typescript.mjs';
const { ranges, progress, validateRange, pool } = await loadTypeScript('../src/lib/file-transfer.ts');

test('ranges cover exact bytes including small files and uneven tail', () => {
  assert.deepEqual(ranges(1,2),[{start:0,end:0}]);
  assert.deepEqual(ranges(5,2),[{start:0,end:1},{start:2,end:3},{start:4,end:4}]);
  assert.throws(()=>ranges(0,2));
});
test('byte progress includes network data and never exceeds100',()=>{
  assert.equal(progress(2,[1,1],8),50); assert.equal(progress(7,[9],8),100); assert.equal(progress(2,[],0),0);
});
test('resume refuses200 or wrong Content-Range rather than joining corrupt output',()=>{
  validateRange(206,'bytes 2-3/5',2,3,5);
  assert.throws(()=>validateRange(200,null,2,3,5));
  assert.throws(()=>validateRange(206,'bytes 0-3/5',2,3,5));
});
test('worker pool obeys concurrency and handles every part once',async()=>{
  let active=0,max=0;const seen=[];
  await pool(11,3,async i=>{active++;max=Math.max(max,active);await new Promise(r=>setTimeout(r,2));seen.push(i);active--;});
  assert.equal(max,3);assert.deepEqual(seen.sort((a,b)=>a-b),Array.from({length:11},(_,i)=>i));
});
test('permanent errors stop scheduling instead of retrying forever',async()=>{
  let invoked=0;
  await assert.rejects(pool(50,1,async()=>{invoked++;throw new Error('offline');}),/offline/);
  assert.equal(invoked,1);
});
