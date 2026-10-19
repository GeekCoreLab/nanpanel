#!/bin/sh
set -eu

mkdir -p /run/nanpanel /managed
printf 'root-only fixture\n' > /managed/secret.txt
chmod 0400 /managed/secret.txt
ln -s /etc/passwd /managed/outside.txt
/usr/local/bin/nanpanel-helper /run/nanpanel/helper.sock 10001 20000 /managed >/tmp/helper.log 2>&1 &
helper_pid=$!
trap 'kill "$helper_pid" 2>/dev/null || true; wait "$helper_pid" 2>/dev/null || true' EXIT

attempt=0
until [ -S /run/nanpanel/helper.sock ]; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 50 ] || { cat /tmp/helper.log; exit 1; }
  sleep 0.1
done

runuser -u agent -- /usr/local/bin/nanpanel-helper-client /run/nanpanel/helper.sock file.read.allowed secret.txt >/tmp/authorized.json
grep -q 'root-only fixture' /tmp/authorized.json
if runuser -u agent -- /usr/local/bin/nanpanel-helper-client /run/nanpanel/helper.sock systemd.restart sshd >/tmp/denied-capability.json 2>&1; then
  echo 'FAIL: an unallowlisted capability was accepted'
  exit 1
fi
if runuser -u agent -- /usr/local/bin/nanpanel-helper-client /run/nanpanel/helper.sock file.read.allowed ../etc/passwd >/tmp/denied-path.json 2>&1; then
  echo 'FAIL: a parent traversal was accepted'
  exit 1
fi
if runuser -u agent -- /usr/local/bin/nanpanel-helper-client /run/nanpanel/helper.sock file.read.allowed outside.txt >/tmp/denied-symlink.json 2>&1; then
  echo 'FAIL: a symlink escape was accepted'
  exit 1
fi
if runuser -u untrusted -- /usr/local/bin/nanpanel-helper-client /run/nanpanel/helper.sock file.read.allowed secret.txt >/tmp/denied-caller.json 2>&1; then
  echo 'FAIL: an untrusted UID was accepted'
  exit 1
fi

grep -q '"caller_uid":10001' /tmp/helper.log
grep -q '"caller_uid":10002' /tmp/helper.log
grep -q '"outcome":"succeeded"' /tmp/helper.log
grep -q '"outcome":"rejected"' /tmp/helper.log
echo 'PASS: root-only read through helper; rejected unallowlisted capability, traversal, symlink escape, and caller UID 10002; audit records caller and outcome'
