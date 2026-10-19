import { createServer } from 'node:http';
import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import process from 'node:process';

const listenPort = Number(process.env.NANPANEL_P0_API_PORT ?? 4180);
const realRemoteRoute = process.env.NANPANEL_P0_REAL_REMOTE_ROUTE === '1';
const network = realRemoteRoute ? null : required('NANPANEL_P0_FRP_NETWORK');
const frpc = required(realRemoteRoute ? 'NANPANEL_P0_REMOTE_FRPC_CONTAINER' : 'NANPANEL_P0_FRPC_CONTAINER');
const stateDir = required('NANPANEL_P0_FRP_STATE');
const transaction = new URL('./frp-transaction.py', import.meta.url).pathname;
const remotePort = Number(process.env.NANPANEL_P0_FRP_REMOTE_PORT ?? 18081);
const httpImage = process.env.NANPANEL_P0_HTTP_IMAGE ?? 'docker.io/library/busybox:1.37.0';
const probeNetwork = network;
const remoteSshAlias = realRemoteRoute ? required('NANPANEL_P0_REMOTE_SSH_ALIAS') : null;
const remoteFrpcDir = realRemoteRoute ? required('NANPANEL_P0_REMOTE_FRPC_DIR') : null;
const publicUrl = realRemoteRoute ? required('NANPANEL_P0_PUBLIC_URL') : null;
const publicResolve = realRemoteRoute ? required('NANPANEL_P0_PUBLIC_RESOLVE') : null;
const expectedBody = process.env.NANPANEL_P0_EXPECTED_BODY ?? 'nanpanel-p0-http-service';
const serviceLocalIp = realRemoteRoute ? required('NANPANEL_P0_SERVICE_LOCAL_IP') : 'http-service';
const serviceLocalPort = Number(process.env.NANPANEL_P0_SERVICE_LOCAL_PORT ?? (realRemoteRoute ? 31080 : 8080));
const nodes = [
  { id: 'f0000000-0000-4000-8000-000000000001', kind: 'GATEWAY', name: '公网 Gateway', position: { x: 40, y: 100 }, inputCount: 0, outputCount: 1 },
  { id: 'f0000000-0000-4000-8000-000000000002', kind: 'HOST', name: 'NAT Host', position: { x: 370, y: 100 }, inputCount: 1, outputCount: 1 },
  { id: 'f0000000-0000-4000-8000-000000000003', kind: 'SERVICE', name: 'K3s Service', position: { x: 700, y: 100 }, inputCount: 1, outputCount: 0 },
];
let revision = 1;
let draft = { nodes, links: [], updatedAt: new Date().toISOString(), updatedBy: null };
let lastEvidence = null;

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`missing ${name}`);
  return value;
}

function etag() { return `"p0-${revision}"`; }
function hash(value) { return createHash('sha256').update(value).digest('hex'); }
function respond(response, status, value, extraHeaders = {}) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...extraHeaders });
  response.end(JSON.stringify(value));
}
async function bodyJson(request) {
  let raw = '';
  for await (const chunk of request) {
    raw += chunk;
    if (raw.length > 1_000_000) throw new Error('request body exceeds 1 MiB');
  }
  return JSON.parse(raw);
}
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 30_000 });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${command} ${args.join(' ')} failed: ${(result.stderr || result.stdout).trim()}`);
  return result.stdout.trim();
}
function runRemote(command, input) {
  const result = spawnSync('ssh', ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', remoteSshAlias, command], {
    encoding: 'utf8', input, timeout: 30_000,
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`remote command failed: ${(result.stderr || result.stdout).trim()}`);
  return result.stdout.trim();
}
function validateRemoteTarget() {
  if (!/^[A-Za-z0-9_.-]+$/.test(remoteSshAlias)) throw new Error('invalid remote SSH alias');
  if (!/^[A-Za-z0-9_.-]+$/.test(frpc)) throw new Error('invalid remote FRPC container name');
  if (!/^\/tmp\/nanpanel-p0-[A-Za-z0-9_.-]+$/.test(remoteFrpcDir)) throw new Error('remote FRPC directory must be an isolated /tmp/nanpanel-p0-* path');
  const url = new URL(publicUrl);
  if (url.protocol !== 'https:' || !/^[a-z0-9.-]+$/.test(url.hostname) || url.pathname !== '/') throw new Error('public route must be a single HTTPS hostname');
  if (!/^[a-z0-9.-]+:443:(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(publicResolve)) throw new Error('invalid curl --resolve target');
  if (!Number.isInteger(serviceLocalPort) || serviceLocalPort < 1 || serviceLocalPort > 65535) throw new Error('P0 service port is invalid');
  if (!Number.isInteger(remotePort) || remotePort < 1024 || remotePort > 65535) throw new Error('P0 FRP remote port is invalid');
}
function validateRoute(value) {
  if (!value || !Array.isArray(value.nodes) || !Array.isArray(value.links)) throw new Error('expected a topology graph');
  if (value.nodes.length !== 3 || value.links.length !== 2) throw new Error('P0 lab accepts exactly Gateway → NAT Host → Service');
  const byKind = new Map(value.nodes.map(node => [node.kind, node]));
  if (byKind.size !== 3 || !byKind.has('GATEWAY') || !byKind.has('HOST') || !byKind.has('SERVICE')) {
    throw new Error('P0 lab accepts one Gateway, one NAT Host, and one Service');
  }
  const [gateway, host, service] = ['GATEWAY', 'HOST', 'SERVICE'].map(kind => byKind.get(kind));
  const links = value.links;
  const has = (source, target) => links.some(link => link.sourceNodeId === source.id && link.targetNodeId === target.id
    && link.sourcePort === 'out-1' && link.targetPort === 'in-1');
  if (!has(gateway, host) || !has(host, service)) throw new Error('connect Gateway output to NAT Host input, then NAT Host output to Service input');
  return { gateway, host, service };
}
function httpProbe() {
  const url = `http://frps:${remotePort}/`;
  let last = '';
  for (let attempt = 0; attempt < 20; attempt++) {
    const result = spawnSync('podman', [
      'run', '--rm', '--http-proxy=false', '--network', probeNetwork,
      '-e', 'HTTP_PROXY=', '-e', 'HTTPS_PROXY=', '-e', 'ALL_PROXY=',
      '-e', 'http_proxy=', '-e', 'https_proxy=', '-e', 'all_proxy=',
      '--entrypoint', 'wget', httpImage, '-T', '2', '-qO-', url,
    ], { encoding: 'utf8', timeout: 10_000 });
    if (result.status === 0 && result.stdout.trim() === expectedBody) return result.stdout.trim();
    last = (result.stderr || result.stdout || result.error?.message || '').trim();
  }
  throw new Error(`HTTP probe failed for ${url}: ${last}`);
}
function publicHttpProbe() {
  validateRemoteTarget();
  let last = '';
  for (let attempt = 0; attempt < 30; attempt++) {
    const result = spawnSync('curl', [
      '--noproxy', '*', '--silent', '--show-error', '--max-time', '8',
      '--resolve', publicResolve, '--write-out', '\n%{http_code}', publicUrl,
    ], { encoding: 'utf8', timeout: 10_000 });
    if (result.status === 0) {
      const marker = result.stdout.lastIndexOf('\n');
      const body = marker < 0 ? result.stdout : result.stdout.slice(0, marker);
      const status = Number(marker < 0 ? 0 : result.stdout.slice(marker + 1).trim());
      if (status === 200 && body === expectedBody) return { status, body };
      last = `HTTP ${status}, body=${body}`;
    } else {
      last = (result.stderr || result.stdout || result.error?.message || '').trim();
    }
    Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 500);
  }
  throw new Error(`public HTTP probe failed for ${publicUrl}: ${last}`);
}
async function applyRemoteRoute(diff) {
  validateRemoteTarget();
  const candidate = await readFile(`${stateDir}/candidate.toml`);
  const configSha256 = hash(candidate);
  const quote = value => `'${value.replaceAll("'", "'\\''")}'`;
  const candidateRemote = `${remoteFrpcDir}/candidate.toml`;
  const activeRemote = `${remoteFrpcDir}/frpc.toml`;
  const originalRemote = `${remoteFrpcDir}/original.toml`;
  runRemote(`umask 077; cat > ${quote(candidateRemote)}`, candidate);
  runRemote(`docker exec ${frpc} frpc verify -c /p0/candidate.toml`);
  let applied = false;
  try {
    applied = true;
    runRemote(`cp ${quote(candidateRemote)} ${quote(activeRemote)} && chmod 600 ${quote(activeRemote)} && docker restart ${frpc} >/dev/null`);
    const probe = publicHttpProbe();
    const manifestPath = `${stateDir}/manifest.json`;
    const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
    manifest.active_sha256 = configSha256;
    manifest.applied_sha256 = configSha256;
    manifest.staged_sha256 = configSha256;
    await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
    await writeFile(`${stateDir}/active.toml`, candidate, { mode: 0o600 });
    lastEvidence = {
      operationId: randomUUID(),
      status: 'VERIFIED',
      route: publicUrl,
      httpStatus: probe.status,
      responseBody: probe.body,
      configSha256,
      diff,
      applyOutput: 'remote FRPC config verified and restarted; public request returned HTTP 200',
      verifiedAt: new Date().toISOString(),
    };
    return lastEvidence;
  } catch (error) {
    if (applied) {
      try {
        runRemote(`cp ${quote(originalRemote)} ${quote(activeRemote)} && chmod 600 ${quote(activeRemote)} && docker restart ${frpc} >/dev/null`);
      } catch (compensationError) {
        throw new Error(`${error.message}; automatic compensation also failed: ${compensationError.message}`);
      }
      throw new Error(`${error.message}; the temporary remote FRPC config was restored`);
    }
    throw error;
  }
}
async function applyRoute(value) {
  validateRoute(value);
  if (!Number.isInteger(remotePort) || remotePort < 1024 || remotePort > 65535) throw new Error('P0 FRP remote port is invalid');
  lastEvidence = null;
  run('python3', [transaction, 'stage', '--state-dir', stateDir, '--proxy', 'p0-http', '--remote-port', String(remotePort), '--local-ip', serviceLocalIp, '--local-port', String(serviceLocalPort)]);
  const diff = run('python3', [transaction, 'diff', '--state-dir', stateDir]);
  if (realRemoteRoute) return applyRemoteRoute(diff);
  const operationId = randomUUID();
  let applied = false;
  try {
    const applyOutput = run('python3', [transaction, 'apply', '--state-dir', stateDir, '--container', frpc]);
    applied = true;
    run('podman', ['restart', frpc]);
    const body = httpProbe();
    lastEvidence = {
      operationId,
      status: 'VERIFIED',
      route: `http://frps:${remotePort}/`,
      httpStatus: 200,
      responseBody: body,
      configSha256: hash(await readFile(`${stateDir}/active.toml`)),
      diff,
      applyOutput,
      verifiedAt: new Date().toISOString(),
    };
    return lastEvidence;
  } catch (error) {
    if (applied) {
      try {
        run('python3', [transaction, 'compensate', '--state-dir', stateDir, '--container', frpc]);
        run('podman', ['restart', frpc]);
      } catch (compensationError) {
        throw new Error(`${error.message}; automatic compensation also failed: ${compensationError.message}`);
      }
      throw new Error(`${error.message}; the imported FRP configuration was restored`);
    }
    throw error;
  }
}

const server = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://127.0.0.1').pathname;
    if (request.method === 'GET' && path === '/api/v1/topology/draft') {
      respond(response, 200, draft, { ETag: etag() });
      return;
    }
    if (request.method === 'PUT' && path === '/api/v1/topology/draft') {
      if (request.headers['if-match'] !== etag()) {
        respond(response, 412, { code: 'PRECONDITION_FAILED', detail: 'P0 lab graph changed; reload it before saving' });
        return;
      }
      const next = await bodyJson(request);
      if (!Array.isArray(next.nodes) || !Array.isArray(next.links) || next.nodes.length > 100 || next.links.length > 800) {
        respond(response, 422, { code: 'INVALID_TOPOLOGY', detail: 'invalid topology draft' });
        return;
      }
      draft = { ...next, updatedAt: new Date().toISOString(), updatedBy: 'p0-lab' };
      revision++;
      respond(response, 200, draft, { ETag: etag() });
      return;
    }
    if (request.method === 'POST' && path === '/api/v1/topology/apply') {
      const desired = await bodyJson(request);
      const evidence = await applyRoute(desired);
      respond(response, 200, evidence);
      return;
    }
    if (request.method === 'GET' && path === '/api/v1/topology/evidence') {
      respond(response, lastEvidence ? 200 : 404, lastEvidence ?? { code: 'NO_ROUTE_EVIDENCE', detail: 'no route has been applied' });
      return;
    }
    respond(response, 404, { code: 'NOT_FOUND', detail: 'P0 lab endpoint not found' });
  } catch (error) {
    respond(response, 502, { code: 'P0_ROUTE_FAILED', detail: error.message });
  }
});

server.listen(listenPort, '127.0.0.1', () => {
  console.log(`P0 topology API listening on http://127.0.0.1:${listenPort}`);
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
