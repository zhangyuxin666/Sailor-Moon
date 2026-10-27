import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScript } from './load-typescript.mjs';

const { isoToLocal, localToISO } = await loadTypeScript('../src/lib/utils.ts');

function inTimezone(zone, action) {
  const old = process.env.TZ;
  process.env.TZ = zone;
  try { action(); }
  finally {
    if (old === undefined) delete process.env.TZ;
    else process.env.TZ = old;
  }
}

test('Shanghai: an unchanged UTC activity survives repeated edit/save cycles', () => {
  inTimezone('Asia/Shanghai', () => {
    let instant = '2026-10-26T07:00:00.000Z';
    assert.equal(isoToLocal(instant), '2026-10-26T15:00');
    for (let edit = 0; edit < 5; edit += 1) {
      instant = localToISO(isoToLocal(instant));
      assert.equal(instant, '2026-10-26T07:00:00.000Z');
    }
  });
});

test('explicit offsets, date rollover and second precision preserve the instant', () => {
  inTimezone('Asia/Shanghai', () => {
    assert.equal(isoToLocal('2026-10-26T07:30:00-04:00'), '2026-10-26T19:30');
    assert.equal(isoToLocal('2026-10-26T23:30:42.125Z'), '2026-10-27T07:30:42.125');
    assert.equal(localToISO(isoToLocal('2026-10-26T23:30:42.125Z')), '2026-10-26T23:30:42.125Z');
  });
});

test('offset-free API values and datetime-local inputs use local wall time', () => {
  inTimezone('Asia/Shanghai', () => {
    assert.equal(isoToLocal('2026-10-26T15:00:00'), '2026-10-26T15:00');
    assert.equal(localToISO('2026-10-26T15:00'), '2026-10-26T07:00:00.000Z');
  });
  inTimezone('UTC', () => {
    assert.equal(isoToLocal('2026-10-26T07:00:00Z'), '2026-10-26T07:00');
    assert.equal(localToISO('2026-10-26T07:00'), '2026-10-26T07:00:00.000Z');
  });
});

test('empty fields remain empty', () => {
  assert.equal(isoToLocal(), '');
  assert.equal(isoToLocal(null), '');
  assert.equal(isoToLocal(''), '');
  assert.equal(localToISO(''), '');
});

test('invalid and overflowing dates never silently change the saved time', () => {
  for (const value of ['not-a-date', '2026-02-30T12:00', '2026-13-01T12:00', '2026-10-26T24:00', '2026-10-26T12:60']) {
    assert.equal(isoToLocal(value), '');
    assert.throws(() => localToISO(value), /有效的日期时间/);
  }
  assert.throws(() => localToISO('2026-10-26T07:00:00Z'), /有效的日期时间/);
  assert.equal(isoToLocal('2026-02-30T12:00Z'), '');
});

test('DST gaps are rejected instead of advancing the requested wall time', () => {
  inTimezone('America/New_York', () => {
    assert.throws(() => localToISO('2026-03-08T02:30'), /有效的日期时间/);
  });
});
