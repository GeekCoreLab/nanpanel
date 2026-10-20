import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { agentMaintenanceCommand } from '../src/lib/agent-maintenance.ts';

const origin = 'https://panel.example.test';
const fingerprint = 'a'.repeat(64);
const downloadPath = '/api/v1/agent-download/linux-amd64';
const helperDownloadPath = '/api/v1/helper-download/linux-amd64';

function bashBody(command) {
  const prefix = 'sudo bash -c ';
  assert.ok(command.startsWith(prefix));
  const quoted = command.slice(prefix.length);
  assert.ok(quoted.startsWith("'") && quoted.endsWith("'"));
  return quoted.slice(1, -1).replaceAll("'\\''", "'");
}

function assertValidBash(command) {
  const script = bashBody(command);
  const result = spawnSync('bash', ['-n', '-c', script], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
}

test('Agent update command pins the controller and updates Agent plus Helper', () => {
  const command = agentMaintenanceCommand('update', origin, fingerprint, downloadPath, helperDownloadPath);
  assert.match(command, /^sudo bash -c /);
  assert.match(command, /agent-controller-cert/);
  assert.match(command, /Controller certificate fingerprint mismatch/);
  assert.match(command, /--cacert/);
  assert.match(command, /nanpanel-agent\.new/);
  assert.match(command, /nanpanel-helper\.new/);
  assert.match(command, /nanpanel-helper\.service/);
  assert.match(command, /systemctl restart nanpanel-agent\.service/);
  assert.doesNotMatch(command, /agent-enrollments|Authorization: Bearer/);
  assert.match(command, /systemctl enable nanpanel-helper\.service nanpanel-agent\.service/);
  assertValidBash(command);
});

test('Agent reinstall repairs its systemd unit without replacing enrollment identity', () => {
  const command = agentMaintenanceCommand('reinstall', origin, fingerprint, downloadPath, helperDownloadPath);
  assert.match(command, /EnvironmentFile=\/etc\/nanpanel-agent\/agent\.env/);
  assert.match(command, /StateDirectory=nanpanel-agent/);
  assert.match(command, /StateDirectoryMode=0750/);
  assert.match(command, /Requires=nanpanel-helper\.service/);
  assert.match(bashBody(command), /cat > \/etc\/systemd\/system\/nanpanel-helper\.service <<UNIT/);
  assert.match(bashBody(command), /cat > \/etc\/systemd\/system\/nanpanel-agent\.service <<'UNIT'/);
  assert.match(command, /systemctl daemon-reload/);
  assert.match(command, /systemctl enable nanpanel-helper\.service nanpanel-agent\.service/);
  assert.match(command, /agent\.key/);
  assert.ok(command.indexOf('/etc/nanpanel-agent/agent.env') < command.indexOf('/usr/local/bin/.nanpanel-agent.new'));
  assert.doesNotMatch(command, /agent-enrollments|Authorization: Bearer/);
  assertValidBash(command);
});

test('Agent installer grants its journal directory writable inside the strict sandbox', async () => {
  const installer = await readFile(new URL('../public/agent-install.sh', import.meta.url), 'utf8');
  const agentUnit = installer
    .split("cat > /etc/systemd/system/nanpanel-agent.service <<'UNIT'\n")[1]
    ?.split('\nUNIT\n')[0];
  assert.ok(agentUnit, 'installer should write the Agent systemd unit');
  assert.match(agentUnit, /^ProtectSystem=strict$/m);
  assert.match(agentUnit, /^StateDirectory=nanpanel-agent$/m);
  assert.match(agentUnit, /^StateDirectoryMode=0750$/m);
});

test('Agent maintenance command rejects insecure origins, foreign downloads and malformed pins', () => {
  assert.throws(() => agentMaintenanceCommand('update', 'http://panel.example.test', fingerprint, downloadPath, helperDownloadPath));
  assert.throws(() => agentMaintenanceCommand('update', origin, 'not-a-fingerprint', downloadPath, helperDownloadPath));
  assert.throws(() => agentMaintenanceCommand('update', origin, fingerprint, 'https://attacker.test/agent', helperDownloadPath));
  assert.throws(() => agentMaintenanceCommand('update', origin, fingerprint, downloadPath, 'https://attacker.test/helper'));
});
