import test from "node:test";
import assert from "node:assert/strict";
import { IdempotentDeliveryCache } from "./delivery-cache.mjs";

test("collapses repeated delivery keys", async () => {
  const cache = new IdempotentDeliveryCache();
  let calls = 0;
  const action = async () => ({ id: ++calls });
  const first = await cache.run("delivery-key", action);
  const second = await cache.run("delivery-key", action);
  assert.equal(calls, 1);
  assert.equal(first.repeated, false);
  assert.equal(second.repeated, true);
  assert.deepEqual(second.result, first.result);
});

test("failed delivery can be retried", async () => {
  const cache = new IdempotentDeliveryCache();
  let calls = 0;
  await assert.rejects(cache.run("retry-key", async () => {
    calls += 1;
    throw new Error("temporary");
  }));
  const result = await cache.run("retry-key", async () => ({ id: ++calls }));
  assert.equal(calls, 2);
  assert.equal(result.repeated, false);
});
