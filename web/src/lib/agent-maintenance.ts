export type AgentMaintenanceMode = 'reinstall' | 'update';

function shellQuote(value: string) {
  return "'" + value.replaceAll("'", "'\\''") + "'";
}

export function agentMaintenanceCommand(
  mode: AgentMaintenanceMode,
  origin: string,
  controllerFingerprint: string,
  agentDownloadPath: string,
  helperDownloadPath: string,
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
  const agentDownload = new URL(agentDownloadPath, controller.origin);
  const helperDownload = new URL(helperDownloadPath, controller.origin);
  for (const download of [agentDownload, helperDownload]) {
    if (download.origin !== controller.origin || download.username || download.password ||
        download.search || download.hash) {
      throw new Error('下载地址必须位于当前控制面板');
    }
  }

  const steps = [
    'set -Eeuo pipefail',
    'umask 077',
    'case "$(uname -m)" in x86_64|amd64) ;; *) printf "当前安装包仅支持 Linux x86_64\\n" >&2; exit 2 ;; esac',
    'for command in curl openssl python3 systemctl install mv mktemp id getent useradd chown chmod; do command -v "$command" >/dev/null 2>&1 || { printf "缺少必要命令: %s\\n" "$command" >&2; exit 2; }; done',
    'test -s /etc/nanpanel-agent/agent.env && test -s /etc/nanpanel-agent/agent.key && test -s /etc/nanpanel-agent/agent.crt || { printf "现有 Agent 身份配置不完整；请从主机资产重新生成接入命令。\\n" >&2; exit 2; }',
    'getent passwd nanpanel-agent >/dev/null || useradd --system --user-group --home-dir /var/lib/nanpanel-agent --create-home --shell /usr/sbin/nologin nanpanel-agent',
    'install -d -o root -g root -m 0755 /etc/nanpanel-agent /etc/nanpanel/managed',
    'install -d -o root -g root -m 0700 /etc/nanpanel/managed/files',
    'install -d -o nanpanel-agent -g nanpanel-agent -m 0750 /var/lib/nanpanel-agent',
    'work=$(mktemp -d /tmp/nanpanel-agent-maintenance.XXXXXX)',
    `trap 'rm -rf -- "$work"; rm -f -- /usr/local/bin/.nanpanel-agent.new /usr/local/bin/.nanpanel-helper.new' EXIT`,
    `curl --insecure --fail --silent --show-error --location --connect-timeout 10 --max-time 30 ${shellQuote(controller.origin + '/api/v1/agent-controller-cert')} -o "$work/controller.pem"`,
    `fingerprint=$(openssl x509 -in "$work/controller.pem" -noout -fingerprint -sha256 | sed 's/.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]')`,
    `test "$fingerprint" = ${shellQuote(fingerprint)} || { printf "Controller certificate fingerprint mismatch\\n" >&2; exit 1; }`,
    `curl --cacert "$work/controller.pem" --fail --silent --show-error --location --connect-timeout 10 --max-time 120 ${shellQuote(agentDownload.href)} -o "$work/nanpanel-agent"`,
    `curl --cacert "$work/controller.pem" --fail --silent --show-error --location --connect-timeout 10 --max-time 120 ${shellQuote(helperDownload.href)} -o "$work/nanpanel-helper"`,
    `python3 -c ${shellQuote('import pathlib, sys; all(pathlib.Path(path).read_bytes()[:4] == bytes.fromhex("7f454c46") for path in sys.argv[1:]) or sys.exit("下载内容不是 Linux ELF 可执行文件")')} "$work/nanpanel-agent" "$work/nanpanel-helper"`,
    'install -o root -g root -m 0755 "$work/nanpanel-agent" /usr/local/bin/.nanpanel-agent.new',
    'install -o root -g root -m 0755 "$work/nanpanel-helper" /usr/local/bin/.nanpanel-helper.new',
    'mv -f /usr/local/bin/.nanpanel-agent.new /usr/local/bin/nanpanel-agent',
    'mv -f /usr/local/bin/.nanpanel-helper.new /usr/local/bin/nanpanel-helper',
    'install -o root -g root -m 0644 "$work/controller.pem" /etc/nanpanel-agent/controller.pem',
    ...(mode === 'reinstall' ? [
      'chown root:nanpanel-agent /etc/nanpanel-agent/agent.env /etc/nanpanel-agent/agent.key && chmod 0640 /etc/nanpanel-agent/agent.env /etc/nanpanel-agent/agent.key && chown root:root /etc/nanpanel-agent/agent.crt /etc/nanpanel-agent/controller.pem && chmod 0644 /etc/nanpanel-agent/agent.crt /etc/nanpanel-agent/controller.pem',
    ] : []),
    `agent_uid=$(id -u nanpanel-agent); agent_gid=$(id -g nanpanel-agent); cat > /etc/systemd/system/nanpanel-helper.service <<UNIT
[Unit]
Description=NaNPanel privileged maintenance Helper
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
Group=nanpanel-agent
RuntimeDirectory=nanpanel
RuntimeDirectoryMode=0750
ExecStart=/usr/local/bin/nanpanel-helper /run/nanpanel/helper.sock $agent_uid $agent_gid /etc/nanpanel/managed ${controller.origin} /etc/nanpanel-agent/controller.pem /usr/local/bin/nanpanel-agent
Restart=on-failure
RestartSec=3
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
chmod 0644 /etc/systemd/system/nanpanel-helper.service`,
    `cat > /etc/systemd/system/nanpanel-agent.service <<'UNIT'
[Unit]
Description=NaNPanel host inventory Agent
Requires=nanpanel-helper.service
After=network-online.target nanpanel-helper.service
Wants=network-online.target

[Service]
Type=simple
User=nanpanel-agent
Group=nanpanel-agent
StateDirectory=nanpanel-agent
StateDirectoryMode=0750
EnvironmentFile=/etc/nanpanel-agent/agent.env
ExecStart=/usr/local/bin/nanpanel-agent
Restart=always
RestartSec=3
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
chmod 0644 /etc/systemd/system/nanpanel-agent.service`,
    'systemctl daemon-reload',
    'systemctl enable nanpanel-helper.service nanpanel-agent.service',
    'systemctl restart nanpanel-helper.service',
    'systemctl restart nanpanel-agent.service',
    'systemctl --no-pager --full status nanpanel-helper.service',
    'systemctl --no-pager --full status nanpanel-agent.service',
  ];
  return `sudo bash -c ${shellQuote(steps.join('; '))}`;
}
