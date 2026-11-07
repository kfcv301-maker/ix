#!/usr/bin/env bash
set +xv
set -euo pipefail
umask 077
config_path=/etc/xboard-node-address-sync/agent-config.json
config_stdin=0
config_argument=0
wait_seconds=180
cli_mode=0
panel_url=
binding_id=
report_token=
token_file=
token_argument=0
interval=15
stable_samples=2
fail_usage() { printf '%s\n' "$1" >&2; exit 64; }
usage() { printf '%s\n' '用法：curl -fsSL INSTALL_URL | bash -s -- --panel https://XBOARD_PANEL --binding-id UUID --token REPORT_TOKEN [--interval 15] [--stable-samples 2] [--wait 180]' '      bash bootstrap.sh --config CONFIG_PATH [--wait 180]' '      bash bootstrap.sh --config-stdin [--wait 180] < agent-config.json' '      --token-file PATH 可代替 --token；两者不能同时使用。' '无参数时复用 /etc/xboard-node-address-sync/agent-config.json；全程无交互。' '这里的 panel/token 属于 Xboard IP 同步绑定，与转发面板和 Cloudflare 密钥不同。'; }
while (( $# )); do
    case "$1" in
        --config) [[ $# -ge 2 && $config_argument -eq 0 ]] || { usage >&2; exit 64; }; config_path=$2; config_argument=1; shift 2 ;;
        --config-stdin) [[ $config_argument -eq 0 ]] || { usage >&2; exit 64; }; config_stdin=1; config_argument=1; shift ;;
        --panel) [[ $# -ge 2 ]] || fail_usage '缺少 --panel 的值'; panel_url=$2; cli_mode=1; shift 2 ;;
        --binding-id) [[ $# -ge 2 ]] || fail_usage '缺少 --binding-id 的值'; binding_id=$2; cli_mode=1; shift 2 ;;
        --token) [[ $# -ge 2 && $token_argument -eq 0 ]] || fail_usage '需要一个 --token 或 --token-file'; report_token=$2; token_argument=1; cli_mode=1; shift 2 ;;
        --token-file) [[ $# -ge 2 && $token_argument -eq 0 ]] || fail_usage '需要一个 --token 或 --token-file'; token_file=$2; token_argument=1; cli_mode=1; shift 2 ;;
        --interval) [[ $# -ge 2 ]] || fail_usage '缺少 --interval 的值'; interval=$2; cli_mode=1; shift 2 ;;
        --stable-samples) [[ $# -ge 2 ]] || fail_usage '缺少 --stable-samples 的值'; stable_samples=$2; cli_mode=1; shift 2 ;;
        --wait) [[ $# -ge 2 ]] || { usage >&2; exit 64; }; wait_seconds=$2; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) usage >&2; exit 64 ;;
    esac
done
[[ "$wait_seconds" =~ ^[0-9]{1,3}$ ]] && (( 10#$wait_seconds >= 15 && 10#$wait_seconds <= 900 )) || fail_usage '--wait 须为 15–900 秒'
if [[ $cli_mode -eq 1 ]]; then
    [[ $config_argument -eq 0 ]] || fail_usage '命令参数不能与 --config 或 --config-stdin 混用'
    # Restrict values before writing JSON: no credentials, query, fragment or escapes.
    [[ "$panel_url" =~ ^https://[A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?/?$ ]] || fail_usage '--panel 需要 HTTPS 面板根地址，例如 https://panel.example'
    [[ "$binding_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] || fail_usage '--binding-id 需要原绑定的 UUID'
    if [[ -n "$token_file" ]]; then
        [[ -r "$token_file" && -s "$token_file" ]] || fail_usage '--token-file 文件不可读或为空'
        report_token=$(cat -- "$token_file")
    fi
    [[ "$report_token" =~ ^[0-9a-f]{64}$ ]] || fail_usage '--token 需要此绑定的 64 位上报密钥'
    [[ "$interval" =~ ^[0-9]{1,4}$ ]] && (( 10#$interval >= 15 && 10#$interval <= 3600 )) || fail_usage '--interval 须为 15–3600 秒'
    [[ "$stable_samples" =~ ^[0-9]{1,2}$ ]] && (( 10#$stable_samples >= 1 && 10#$stable_samples <= 10 )) || fail_usage '--stable-samples 须为 1–10'
    interval=$((10#$interval)); stable_samples=$((10#$stable_samples))
fi
[[ ${EUID} -eq 0 ]] || { printf '%s\n' '需要 root 权限' >&2; exit 77; }
if [[ $config_stdin -eq 1 && -t 0 ]]; then printf '%s\n' '--config-stdin 需要管道或文件输入，不会交互询问' >&2; exit 64; fi
if [[ $cli_mode -eq 0 && $config_stdin -eq 0 && ! -s "$config_path" ]]; then printf '%s\n' '新服务器需要传入原绑定配置；不能自动猜测节点和密钥' >&2; exit 64; fi
bootstrap_stage=$(mktemp -d /tmp/xboard-node-bootstrap.XXXXXXXX)
trap 'rm -rf -- "$bootstrap_stage"' EXIT
if [[ $config_stdin -eq 1 ]]; then config_path=$bootstrap_stage/agent-config.json; cat > "$config_path"; chmod 0600 "$config_path"; fi
if [[ $cli_mode -eq 1 ]]; then
    config_path=$bootstrap_stage/agent-config.json
    printf '{"report_url":"%s/api/v1/node-address-sync/report","binding_id":"%s","token":"%s","mode":"aws-imds","expected_instance_id":"","interval":%s,"stable_samples":%s,"state_file":"/var/lib/xboard-node-address-sync/state.json"}\n' "${panel_url%/}" "$binding_id" "$report_token" "$interval" "$stable_samples" > "$config_path"
    chmod 0600 "$config_path"
    unset report_token
fi
cat > "$bootstrap_stage/report_ip.py" <<'XBOARD_REPORTER_PY'
#!/usr/bin/env python3
"""Report the current node IPv4 to Xboard. Python standard library only."""

import argparse
import ipaddress
import json
import os
import signal
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

DEFAULT_INTERVAL = 15

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Redirect refused; configure the final HTTPS panel URL")


def request(url, method="GET", headers=None, body=None, timeout=8):
    # Do not let proxy variables reroute IMDS or reveal reporting credentials.
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request_headers = {"User-Agent": "Xboard-Node-Address-Sync/1.1.1"}
    request_headers.update(headers or {})
    req = Request(url, data=body, headers=request_headers, method=method)
    with opener.open(req, timeout=timeout) as response:
        return response.read(16385)


def public_ipv4(value):
    ip = ipaddress.ip_address(value.strip())
    if ip.version != 4 or not ip.is_global or ip.is_multicast:
        raise ValueError("No valid public IPv4; panel address was not changed")
    return str(ip)


def discover_aws(expected_instance_id=""):
    base = "http://169.254.169.254/latest/"
    token = request(base + "api/token", "PUT", {"X-aws-ec2-metadata-token-ttl-seconds": "60"}).decode().strip()
    if not token:
        raise ValueError("IMDSv2 returned an empty token")
    headers = {"X-aws-ec2-metadata-token": token}
    instance = request(base + "meta-data/instance-id", headers=headers).decode().strip()
    if expected_instance_id and instance != expected_instance_id:
        raise ValueError("Instance ID does not match the configured binding")
    ip = public_ipv4(request(base + "meta-data/public-ipv4", headers=headers).decode())
    return ip, instance


def read_json(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        if default is not None:
            return default
        raise


def write_state(path, state):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = target.with_name(target.name + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(state, output)
    os.replace(temporary, target)


def validate_config(config):
    parsed = urlparse(config["report_url"])
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("report_url must be a final HTTPS URL without credentials or query parameters")
    uuid.UUID(config["binding_id"])
    token = config["token"]
    if len(token) != 64 or any(char not in "0123456789abcdef" for char in token):
        raise ValueError("Invalid reporting token")
    if config.get("mode", "aws-imds") not in ("aws-imds", "manual"):
        raise ValueError("Unsupported discovery mode")
    if not 15 <= int(config.get("interval", DEFAULT_INTERVAL)) <= 3600:
        raise ValueError("interval must be between 15 and 3600 seconds")
    if not 1 <= int(config.get("stable_samples", 2)) <= 10:
        raise ValueError("stable_samples must be between 1 and 10")


def sync_once(config, explicit_ip=None, instance_id=None):
    validate_config(config)
    state_file = config.get("state_file", "/var/lib/xboard-node-address-sync/state.json")
    state = read_json(state_file, {})
    # Changing the binding or rotating its token resets observations from the old config.
    import hashlib
    fingerprint = hashlib.sha256((config["report_url"] + config["binding_id"] + config["token"]).encode()).hexdigest()
    if state.get("config_fingerprint") != fingerprint:
        state = {"config_fingerprint": fingerprint}
    expected = config.get("expected_instance_id", "")
    try:
        if explicit_ip is not None:
            ip, instance = public_ipv4(explicit_ip), instance_id or expected or None
        elif config.get("mode", "aws-imds") == "aws-imds":
            ip, instance = discover_aws(expected)
        else:
            raise ValueError("Manual mode requires --ip supplied by the existing IP replacement system")
        if expected and instance != expected:
            raise ValueError("Instance ID does not match the configured binding")
    except Exception:
        # A discovery failure interrupts consecutive observations.
        state.pop("candidate_ip", None)
        state["candidate_count"] = 0
        write_state(state_file, state)
        raise

    state["candidate_count"] = state.get("candidate_count", 0) + 1 if state.get("candidate_ip") == ip else 1
    state["candidate_ip"] = ip
    write_state(state_file, state)
    if state["candidate_count"] < int(config.get("stable_samples", 2)):
        return {"status": "observing", "updated": 0, "ip": ip}
    body = {"binding_id": config["binding_id"], "ip": ip, "observed_at": int(time.time())}
    if instance:
        body["instance_id"] = instance
    # Heartbeats also resynchronize nodes newly selected in the panel.
    result = json.loads(request(config["report_url"], "POST", {
        "Authorization": "Bearer " + config["token"],
        "Content-Type": "application/json", "Accept": "application/json",
    }, json.dumps(body, separators=(",", ":")).encode()))
    data = result.get("data", {})
    if data.get("status") not in ("updated", "unchanged", "duplicate") or data.get("ip") != ip:
        raise ValueError("Panel response did not acknowledge this IP")
    state["last_reported_ip"] = ip
    state["last_success_at"] = int(time.time())
    write_state(state_file, state)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--daemon", action="store_true")
    parser.add_argument("--ip", help="Explicit public IPv4 from the existing replacement system")
    parser.add_argument("--instance-id")
    args = parser.parse_args()
    running = True

    def stop(_signum, _frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    while running:
        started = time.monotonic()
        interval = DEFAULT_INTERVAL
        try:
            config = read_json(args.config)
            interval = int(config.get("interval", DEFAULT_INTERVAL))
            # Serialize daemon, cron, and explicit callback invocations on Linux.
            state_path = Path(config.get("state_file", "/var/lib/xboard-node-address-sync/state.json"))
            state_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            import fcntl
            with open(str(state_path) + ".lock", "a") as lock:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
                result = sync_once(config, args.ip, args.instance_id)
            if result["status"] in ("updated", "observing"):
                print(f"{result['status']}: {result['updated']} node address(es)", flush=True)
        except HTTPError as error:
            # Do not print response bodies, request headers, credentials, or full URLs.
            print(f"Panel/metadata HTTP {error.code}; will retry, panel addresses preserved", file=sys.stderr, flush=True)
            if not args.daemon:
                return 1
        except Exception as error:
            print(f"Sync failed ({type(error).__name__}); will retry, panel addresses preserved", file=sys.stderr, flush=True)
            if not args.daemon:
                return 1
        if not args.daemon:
            return 0
        deadline = started + max(15, min(interval, 3600))
        while running and time.monotonic() < deadline:
            time.sleep(min(1, max(0, deadline - time.monotonic())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
XBOARD_REPORTER_PY
cat > "$bootstrap_stage/install.sh" <<'XBOARD_INSTALLER_SH'
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
XBOARD_INSTALLER_SH
bash "$bootstrap_stage/install.sh" "$config_path" "$wait_seconds"
