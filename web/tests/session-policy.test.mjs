import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScript } from './load-typescript.mjs';
const { sessionRequired } = await loadTypeScript('../src/lib/session-policy.ts');

test('expired protected requests require authentication', () => {
  assert.equal(sessionRequired('/portal/dashboard', 403, 'authentication_required'), true);
  assert.equal(sessionRequired('/tasks/t', 401), true);
  assert.equal(sessionRequired('/runs/r', 403, undefined, '请先登录'), true);
});
test('authorization denials and password enforcement retain their own behavior', () => {
  assert.equal(sessionRequired('/members', 403, undefined, '只能操作自己的班级'), false);
  assert.equal(sessionRequired('/portal', 403, 'password_change_required'), false);
  assert.equal(sessionRequired('/portal', 500), false);
});
test('failed sign-ins and anonymous status checks cannot open the expiration dialog', () => {
  for (const path of ['/auth/login', '/auth/bootstrap', '/auth/status?refresh=1', '/auth/logout']) {
    assert.equal(sessionRequired(path, 401, 'authentication_required'), false);
  }
});
