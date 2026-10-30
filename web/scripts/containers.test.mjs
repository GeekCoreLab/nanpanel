import { test } from 'node:test';
import assert from 'node:assert/strict';
import { containerState, parseContainerList } from '../src/lib/containers.ts';

test('the docker ps output is split into name and status', () => {
  const entries = parseContainerList([
    'nanpanel-web Up 3 hours',
    'redis Up 2 days (healthy)',
    'legacy-job Exited (0) 5 days ago',
  ].join('\n'));
  assert.equal(entries.length, 3);
  assert.deepEqual(entries[0], { name: 'nanpanel-web', status: 'Up 3 hours', running: true, paused: false });
  assert.equal(entries[2].name, 'legacy-job');
  assert.equal(entries[2].running, false);
});

test('blank lines, CRLF and a missing status never produce phantom rows', () => {
  assert.deepEqual(parseContainerList(null), []);
  assert.deepEqual(parseContainerList(''), []);
  assert.deepEqual(parseContainerList('\n\n  \n'), []);
  const entries = parseContainerList('solo\r\n\r\nother Up 1 minute\r\n');
  assert.deepEqual(entries.map(entry => entry.name), ['solo', 'other']);
  assert.equal(entries[0].status, '');
  assert.equal(entries[0].running, false);
});

test('every container lifecycle state is classified from the status text', () => {
  const state = status => containerState(parseContainerList(`c ${status}`)[0]);
  assert.equal(state('Up 12 seconds'), 'RUNNING');
  assert.equal(state('Up 12 seconds (Paused)'), 'PAUSED');
  assert.equal(state('Exited (1) 2 hours ago'), 'STOPPED');
  assert.equal(state('Dead'), 'STOPPED');
  assert.equal(state('Created'), 'CREATED');
  assert.equal(state('Restarting (1) 3 seconds ago'), 'RESTARTING');
  assert.equal(state(''), 'UNKNOWN');
});

test('a paused container is not reported as healthy running', () => {
  const [entry] = parseContainerList('cache Up 4 minutes (Paused)');
  assert.equal(entry.running, true);
  assert.equal(containerState(entry), 'PAUSED');
});
