import { test } from 'node:test';
import assert from 'node:assert/strict';
import { agentMaintenanceCommand } from '../src/lib/agent-maintenance.ts';

const origin = 'https://panel.example.test';
const fingerprint = 'a'.repeat(64);
const downloadPath = '/api/v1/agent-download/linux-amd64';

test('Agent update command pins the controller and replaces only the installed binary', () => {
  const command = agentMaintenanceCommand('update', origin, fingerprint, downloadPath);
  assert.match(command, /^sudo bash -c /);
  assert.match(command, /agent-controller-cert/);
  assert.match(command, /Controller certificate fingerprint mismatch/);
  assert.match(command, /--cacert/);
  assert.match(command, /nanpanel-agent\.new/);
  assert.match(command, /systemctl restart nanpanel-agent\.service/);
  assert.doesNotMatch(command, /agent-enrollments|Authorization: Bearer/);
  assert.doesNotMatch(command, /systemctl enable/);
});

test('Agent reinstall repairs its systemd unit without replacing enrollment identity', () => {
  const command = agentMaintenanceCommand('reinstall', origin, fingerprint, downloadPath);
  assert.match(command, /EnvironmentFile=\/etc\/nanpanel-agent\/agent\.env/);
  assert.match(command, /systemctl daemon-reload/);
  assert.match(command, /systemctl enable nanpanel-agent\.service/);
  assert.match(command, /agent\.key/);
  assert.doesNotMatch(command, /agent-enrollments|Authorization: Bearer/);
});

test('Agent maintenance command rejects insecure origins, foreign downloads and malformed pins', () => {
  assert.throws(() => agentMaintenanceCommand('update', 'http://panel.example.test', fingerprint, downloadPath));
  assert.throws(() => agentMaintenanceCommand('update', origin, 'not-a-fingerprint', downloadPath));
  assert.throws(() => agentMaintenanceCommand('update', origin, fingerprint, 'https://attacker.test/agent'));
});
