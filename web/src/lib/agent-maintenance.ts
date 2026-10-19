export type AgentMaintenanceMode = 'reinstall' | 'update';

function shellQuote(value: string) {
  return "'" + value.replaceAll("'", "'\\''") + "'";
}

export function agentMaintenanceCommand(
  mode: AgentMaintenanceMode,
  origin: string,
  controllerFingerprint: string,
  agentDownloadPath: string,
) {
  if (mode !== 'reinstall' && mode !== 'update') {
    throw new Error('Agent 维护模式无效');
  }
  const controller = new URL(origin);
  if (controller.protocol !== 'https:' || controller.pathname !== '/' ||
      controller.search || controller.hash || controller.username || controller.password) {
    throw new Error('控制面板地址必须是 HTTPS Origin');
  }
  const fingerprint = controllerFingerprint.toLowerCase();
  if (!/^[a-f0-9]{64}$/.test(fingerprint)) {
    throw new Error('控制面板证书指纹无效');
  }
  const download = new URL(agentDownloadPath, controller.origin);
  if (download.origin !== controller.origin || download.username || download.password ||
      download.search || download.hash) {
    throw new Error('Agent 下载地址必须位于当前控制面板');
  }

  const steps = [
    'set -Eeuo pipefail',
    'umask 077',
    'case "$(uname -m)" in x86_64|amd64) ;; *) printf "当前安装包仅支持 Linux x86_64\n" >&2; exit 2 ;; esac',
    `for command in curl openssl python3 systemctl install mv mktemp${mode === 'reinstall' ? ' getent useradd chown chmod cat' : ''}; do command -v "$command" >/dev/null 2>&1 || { printf "缺少必要命令: %s\\n" "$command" >&2; exit 2; }; done`,
    'test -s /etc/nanpanel-agent/agent.env && test -s /etc/nanpanel-agent/agent.key && test -s /etc/nanpanel-agent/agent.crt || { printf "现有 Agent 身份配置不完整；请从主机资产重新生成接入命令。\\n" >&2; exit 2; }',
    'work=$(mktemp -d /tmp/nanpanel-agent-maintenance.XXXXXX)',
    `trap 'rm -rf -- "$work"; rm -f -- /usr/local/bin/.nanpanel-agent.new' EXIT`,
    `curl --insecure --fail --silent --show-error --location --connect-timeout 10 --max-time 30 ${shellQuote(controller.origin + '/api/v1/agent-controller-cert')} -o "$work/controller.pem"`,
    `fingerprint=$(openssl x509 -in "$work/controller.pem" -noout -fingerprint -sha256 | sed 's/.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]')`,
    `test "$fingerprint" = ${shellQuote(fingerprint)} || { printf "Controller certificate fingerprint mismatch\\n" >&2; exit 1; }`,
    `curl --cacert "$work/controller.pem" --fail --silent --show-error --location --connect-timeout 10 --max-time 120 ${shellQuote(download.href)} -o "$work/nanpanel-agent"`,
    `python3 -c ${shellQuote('import pathlib, sys; pathlib.Path(sys.argv[1]).read_bytes()[:4] == bytes.fromhex("7f454c46") or sys.exit("Agent download is not a Linux executable")')} "$work/nanpanel-agent"`,
    'install -o root -g root -m 0755 "$work/nanpanel-agent" /usr/local/bin/.nanpanel-agent.new',
    'mv -f /usr/local/bin/.nanpanel-agent.new /usr/local/bin/nanpanel-agent',
  ];

  if (mode === 'reinstall') {
    steps.push(
      'getent passwd nanpanel-agent >/dev/null || useradd --system --user-group --home-dir /var/lib/nanpanel-agent --create-home --shell /usr/sbin/nologin nanpanel-agent',
      'install -d -o root -g root -m 0755 /etc/nanpanel-agent',
      'install -d -o nanpanel-agent -g nanpanel-agent -m 0750 /var/lib/nanpanel-agent',
      'install -o root -g root -m 0644 "$work/controller.pem" /etc/nanpanel-agent/controller.pem',
      'chown root:nanpanel-agent /etc/nanpanel-agent/agent.env /etc/nanpanel-agent/agent.key && chmod 0640 /etc/nanpanel-agent/agent.env /etc/nanpanel-agent/agent.key && chown root:root /etc/nanpanel-agent/agent.crt /etc/nanpanel-agent/controller.pem && chmod 0644 /etc/nanpanel-agent/agent.crt /etc/nanpanel-agent/controller.pem',
      `cat > /etc/systemd/system/nanpanel-agent.service <<'UNIT'
[Unit]
Description=NaNPanel host inventory Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=nanpanel-agent
Group=nanpanel-agent
EnvironmentFile=/etc/nanpanel-agent/agent.env
ExecStart=/usr/local/bin/nanpanel-agent
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
LockPersonality=true
CapabilityBoundingSet=
AmbientCapabilities=
ReadOnlyPaths=/etc/nanpanel-agent
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
chmod 0644 /etc/systemd/system/nanpanel-agent.service`,
      'systemctl daemon-reload',
      'systemctl enable nanpanel-agent.service',
    );
  }

  steps.push('systemctl restart nanpanel-agent.service', 'systemctl --no-pager --full status nanpanel-agent.service');
  return `sudo bash -c ${shellQuote(steps.join('; '))}`;
}
