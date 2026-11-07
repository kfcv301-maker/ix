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
@@REPORTER@@
XBOARD_REPORTER_PY
cat > "$bootstrap_stage/install.sh" <<'XBOARD_INSTALLER_SH'
@@INSTALLER@@
XBOARD_INSTALLER_SH
bash "$bootstrap_stage/install.sh" "$config_path" "$wait_seconds"
