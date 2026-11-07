#!/usr/bin/env bash
set +xv
set -euo pipefail
umask 077
fail() { printf '%s\n' "$1" >&2; exit "${2:-1}"; }
[[ ${EUID} -eq 0 ]] || fail '需要 root：bash install.sh /路径/agent-config.json' 77
[[ $# -ge 1 && $# -le 2 ]] || fail '用法：bash install.sh CONFIG_PATH [等待秒数，默认 180]' 64
config_path=$1
wait_seconds=${2:-180}
[[ -r "$config_path" && -s "$config_path" ]] || fail '配置文件不存在、不可读或为空' 64
[[ "$wait_seconds" =~ ^[0-9]{1,3}$ ]] && (( 10#$wait_seconds >= 15 && 10#$wait_seconds <= 900 )) || fail '等待秒数须为 15–900' 64
wait_seconds=$((10#$wait_seconds))
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
command -v systemctl >/dev/null && [[ -d /run/systemd/system ]] || fail '需要运行 systemd 的 Linux 系统'
[[ -f "$script_dir/report_ip.py" ]] || fail '缺少 report_ip.py；请使用完整安装包或 bootstrap.sh'

# Cloud images can be missing Python and a CA store. Do not upgrade the system.
if ! command -v python3 >/dev/null || ! python3 -c 'import sys,ssl; assert sys.version_info >= (3,8); p=ssl.get_default_verify_paths(); assert p.cafile or p.capath' >/dev/null 2>&1; then
    command -v timeout >/dev/null || fail '系统缺少 timeout 命令'
    if command -v apt-get >/dev/null; then
        export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l APT_LISTCHANGES_FRONTEND=none
        timeout 300 apt-get -q -o DPkg::Lock::Timeout=180 -o Acquire::Retries=3 update
        timeout 300 apt-get -q -o DPkg::Lock::Timeout=180 -o Acquire::Retries=3 -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install -y --no-install-recommends python3 ca-certificates
    elif command -v dnf >/dev/null; then
        timeout 600 dnf -y install python3 ca-certificates
    elif command -v yum >/dev/null; then
        timeout 600 yum -y install python3 ca-certificates
    else
        fail '无法自动安装依赖；需要 apt-get、dnf 或 yum'
    fi
fi
python3 -c 'import sys,ssl; assert sys.version_info >= (3,8)' >/dev/null 2>&1 || fail '需要支持 SSL 的 Python 3.8 或更新版本'
command -v flock >/dev/null || fail '系统缺少 flock（util-linux）'
mkdir -p /run/lock
exec 9>/run/lock/xboard-node-address-sync-install.lock
flock -w 180 9 || fail '另一个安装任务尚未完成'
python3 - "$config_path" "$script_dir" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location('report_ip', sys.argv[2] + '/report_ip.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
try:
    config = module.read_json(sys.argv[1])
    module.validate_config(config)
    if config.get('mode', 'aws-imds') != 'aws-imds' or config.get('state_file', '/var/lib/xboard-node-address-sync/state.json') != '/var/lib/xboard-node-address-sync/state.json':
        raise ValueError('Unsupported installer configuration')
except Exception as error:
    print('配置校验失败（' + type(error).__name__ + '）；未修改已有服务', file=sys.stderr)
    sys.exit(64)
PY

target_config=/etc/xboard-node-address-sync/agent-config.json
backup_dir=/var/backups/xboard-node-address-sync/$(date -u +%Y%m%dT%H%M%SZ)-$$
if [[ -f "$target_config" || -f /opt/xboard-node-address-sync/report_ip.py || -f /etc/systemd/system/xboard-node-address-sync.service ]]; then
    install -d -m 0700 "$backup_dir"
    for previous_file in "$target_config" /opt/xboard-node-address-sync/report_ip.py /etc/systemd/system/xboard-node-address-sync.service; do
        if [[ -f "$previous_file" ]]; then install -m 0600 "$previous_file" "$backup_dir/$(basename -- "$previous_file")"; fi
    done
fi
install -d -m 0755 /opt/xboard-node-address-sync
install -d -m 0700 /etc/xboard-node-address-sync /var/lib/xboard-node-address-sync
install -m 0755 "$script_dir/report_ip.py" /opt/xboard-node-address-sync/report_ip.py.new
mv -f -- /opt/xboard-node-address-sync/report_ip.py.new /opt/xboard-node-address-sync/report_ip.py
if [[ $(readlink -f -- "$config_path") != "$target_config" ]]; then
    install -m 0600 "$config_path" "$target_config.new"
    mv -f -- "$target_config.new" "$target_config"
fi
chmod 0600 "$target_config"
unit_file=$(mktemp)
trap 'rm -f -- "$unit_file"' EXIT
cat > "$unit_file" <<'UNIT'
[Unit]
Description=Xboard node public IPv4 reporter
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/env python3 /opt/xboard-node-address-sync/report_ip.py --config /etc/xboard-node-address-sync/agent-config.json --daemon
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/xboard-node-address-sync
UMask=0077

[Install]
WantedBy=multi-user.target
UNIT
install -m 0644 "$unit_file" /etc/systemd/system/xboard-node-address-sync.service
systemctl daemon-reload
systemctl enable xboard-node-address-sync.service
restart_started=$(date +%s)
systemctl restart xboard-node-address-sync.service
systemctl is-active --quiet xboard-node-address-sync.service || fail '上报服务启动失败'
printf '服务已启动，等待面板确认（最多 %s 秒）…\n' "$wait_seconds"

# A zero exit means the daemon received a fresh panel acknowledgement.
# Never count an old state file from a copied image as a successful new install.
python3 - "$target_config" "$restart_started" "$wait_seconds" <<'PY'
import hashlib, json, sys, time
from pathlib import Path
config = json.loads(Path(sys.argv[1]).read_text())
fingerprint = hashlib.sha256((config['report_url'] + config['binding_id'] + config['token']).encode()).hexdigest()
deadline = time.monotonic() + int(sys.argv[3])
state_path = config.get('state_file', '/var/lib/xboard-node-address-sync/state.json')
while time.monotonic() < deadline:
    try:
        state = json.loads(Path(state_path).read_text())
        if state.get('config_fingerprint') == fingerprint and state.get('last_success_at', 0) > int(sys.argv[2]) and state.get('last_reported_ip') == state.get('candidate_ip'):
            print('安装完成，面板已确认公网 IP：' + state['last_reported_ip'])
            print('检测间隔：' + str(config.get('interval', 15)) + ' 秒；连续确认：' + str(config.get('stable_samples', 2)) + ' 次')
            sys.exit(0)
    except (OSError, ValueError):
        pass
    time.sleep(1)
print('服务已安装并运行，但未在时限内获得面板确认；服务将继续重试。查看：journalctl -u xboard-node-address-sync -n 20', file=sys.stderr)
sys.exit(2)
PY
