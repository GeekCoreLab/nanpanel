import { test } from 'node:test';
import assert from 'node:assert/strict';
import { joinHostPath, normalizeHostPath, parentHostPath } from '../src/lib/host-paths.ts';

test('normalize collapses duplicates, trailing slashes and dot segments', () => {
  assert.equal(normalizeHostPath('/'), '/');
  assert.equal(normalizeHostPath(''), '/');
  assert.equal(normalizeHostPath('///a//b/'), '/a/b');
  assert.equal(normalizeHostPath('/etc/./nginx'), '/etc/nginx');
});

test('normalize rejects relative paths and parent traversal', () => {
  assert.throws(() => normalizeHostPath('etc/nginx'));
  assert.throws(() => normalizeHostPath('/etc/../passwd'));
});

test('parent walks up to the root and stops there', () => {
  assert.equal(parentHostPath('/'), null);
  assert.equal(parentHostPath('/etc'), '/');
  assert.equal(parentHostPath('/etc/nginx/'), '/etc');
  assert.equal(parentHostPath('/var/lib//docker'), '/var/lib');
});

test('join produces single-entry paths from any directory', () => {
  assert.equal(joinHostPath('/', 'etc'), '/etc');
  assert.equal(joinHostPath('/etc', 'nginx.conf'), '/etc/nginx.conf');
  assert.equal(joinHostPath('/etc/', 'nginx.conf'), '/etc/nginx.conf');
  assert.equal(joinHostPath('/var/lib', 'docker'), '/var/lib/docker');
});

test('join rejects nested or traversal entry names', () => {
  assert.throws(() => joinHostPath('/etc', 'a/b'));
  assert.throws(() => joinHostPath('/etc', '..'));
  assert.throws(() => joinHostPath('/etc', '.'));
  assert.throws(() => joinHostPath('/etc', '  '));
});
