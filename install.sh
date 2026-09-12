#!/usr/bin/env bash
# Flux Panel Enhanced 节点 Agent 安装脚本。
set -Eeuo pipefail

REPO_RAW_BASE="${REPO_RAW_BASE:-https://raw.githubusercontent.com/kfcv301-maker/ix/main}"
INSTALL_DIR="${INSTALL_DIR:-/etc/flux-panel-agent}"
SERVICE_NAME="flux-panel-agent"
SERVER_ADDR=""
NODE_SECRET=""

info() { printf '\033[1;34m[INFO]\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Flux Panel Enhanced 节点 Agent 安装脚本

用法：
  install.sh --server 面板地址:6365 --secret 节点密钥
  install.sh --uninstall

可选环境变量：
  REPO_RAW_BASE=https://raw.githubusercontent.com/kfcv301-maker/ix/main
  INSTALL_DIR=/etc/flux-panel-agent
EOF
}

require_root() {
  [[ "${EUID}" -eq 0 ]] || fail "请以 root 执行，或在命令前加 sudo。"
}

architecture() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'amd64' ;;
    aarch64|arm64) printf 'arm64' ;;
    *) fail "不支持的 CPU 架构：$(uname -m)" ;;
  esac
}

require_systemd() {
  command -v systemctl >/dev/null 2>&1 || fail "当前系统不支持 systemd，暂不能自动安装服务。"
}

download_agent() {
  local arch url checksum_url temp_bin temp_checksum expected actual
  arch="$(architecture)"
  url="$REPO_RAW_BASE/artifacts/flux-panel-agent-linux-$arch"
  checksum_url="$url.sha256"
  temp_bin="$(mktemp)"
  temp_checksum="$(mktemp)"
  trap 'rm -f "$temp_bin" "$temp_checksum"' RETURN

  command -v curl >/dev/null 2>&1 || fail "请先安装 curl。"
  info "下载 Linux/$arch 节点 Agent"
  curl --fail --location --retry 3 --connect-timeout 15 "$url" -o "$temp_bin"
  curl --fail --location --retry 3 --connect-timeout 15 "$checksum_url" -o "$temp_checksum"

  expected="$(awk '{print $1}' "$temp_checksum")"
  actual="$(sha256sum "$temp_bin" | awk '{print $1}')"
  [[ -n "$expected" && "$expected" == "$actual" ]] || fail "Agent 校验失败，下载文件未写入系统。"

  install -Dm755 "$temp_bin" "$INSTALL_DIR/gost"
  ok "Agent 下载并校验完成"
}

write_config() {
  umask 077
  cat > "$INSTALL_DIR/config.json" <<EOF
{
  "addr": "$SERVER_ADDR",
  "secret": "$NODE_SECRET",
  "http": 1,
  "tls": 1,
  "socks": 1
}
EOF
  chmod 600 "$INSTALL_DIR/config.json"
  [[ -f "$INSTALL_DIR/gost.json" ]] || printf '{}\n' > "$INSTALL_DIR/gost.json"
  chmod 600 "$INSTALL_DIR/gost.json"
}

write_service() {
  cat > "/etc/systemd/system/$SERVICE_NAME.service" <<EOF
[Unit]
Description=Flux Panel Enhanced Node Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/gost
Restart=always
RestartSec=3
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
}

install_agent() {
  require_root
  require_systemd
  [[ -n "$SERVER_ADDR" ]] || fail "缺少 --server 参数。"
  [[ -n "$NODE_SECRET" ]] || fail "缺少 --secret 参数。"
  [[ "$SERVER_ADDR" != *$'\n'* && "$NODE_SECRET" != *$'\n'* ]] || fail "参数不能包含换行符。"

  mkdir -p "$INSTALL_DIR"
  download_agent
  write_config
  write_service

  systemctl daemon-reload
  systemctl enable "$SERVICE_NAME.service" >/dev/null
  systemctl restart "$SERVICE_NAME.service"
  sleep 1
  systemctl is-active --quiet "$SERVICE_NAME.service" || {
    journalctl -u "$SERVICE_NAME.service" --no-pager -n 50 >&2 || true
    fail "Agent 启动失败，已保留配置以便排查。"
  }
  ok "节点 Agent 已启动并设为开机自启"
  printf '状态查看：systemctl status %s\n' "$SERVICE_NAME"
}

uninstall_agent() {
  require_root
  require_systemd
  read -r -p "卸载节点 Agent 并删除本机配置？(y/N): " confirm
  [[ "$confirm" =~ ^[Yy]$ ]] || { info "已取消"; return; }
  systemctl disable --now "$SERVICE_NAME.service" 2>/dev/null || true
  rm -f "/etc/systemd/system/$SERVICE_NAME.service"
  rm -rf "$INSTALL_DIR"
  systemctl daemon-reload
  ok "节点 Agent 已卸载"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server|-a) SERVER_ADDR="${2:-}"; shift 2 ;;
    --secret|-s) NODE_SECRET="${2:-}"; shift 2 ;;
    --uninstall) uninstall_agent; exit 0 ;;
    --help|-h) usage; exit 0 ;;
    *) fail "未知参数：$1" ;;
  esac
done

install_agent
