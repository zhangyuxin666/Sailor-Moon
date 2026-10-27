import assert from 'node:assert/strict';
import test from 'node:test';
import { loadTypeScript } from './load-typescript.mjs';

const { passwordChangeRequired, protectedRedirect } = await loadTypeScript('../src/lib/auth-policy.ts');
const participant = { username: 'student', display_name: '同学', role: 'participant', force_password_change: 0 };
const manager = { ...participant, role: 'manager' };

test('initial and reset passwords cannot open any business page', () => {
  for (const account of [participant, manager]) {
    for (const path of ['/portal', '/assignments', '/agent', '/members', '/activity/123']) {
      assert.equal(protectedRedirect({ ...account, force_password_change: 1 }, path), '/settings');
    }
  }
});

test('password change screen is reachable without a redirect loop', () => {
  assert.equal(protectedRedirect({ ...participant, force_password_change: 1 }, '/settings'), null);
  assert.equal(protectedRedirect({ ...manager, force_password_change: 1 }, '/settings'), null);
});

test('password requirement takes precedence over manager-only routing', () => {
  assert.equal(protectedRedirect({ ...participant, force_password_change: 1 }, '/members', true), '/settings');
  assert.equal(protectedRedirect(participant, '/members', true), '/portal');
  assert.equal(protectedRedirect(manager, '/members', true), null);
});

test('cleared or expired sessions return to login even on settings', () => {
  assert.equal(protectedRedirect(null, '/settings'), '/login');
  assert.equal(protectedRedirect(null, '/portal'), '/login');
});

test('ordinary accounts and older responses without a flag remain usable', () => {
  assert.equal(passwordChangeRequired(null), false);
  assert.equal(passwordChangeRequired({ ...participant, force_password_change: undefined }), false);
  assert.equal(protectedRedirect(participant, '/assignments'), null);
  assert.equal(protectedRedirect(manager, '/activity/123', true), null);
});
