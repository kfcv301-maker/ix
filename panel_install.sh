#!/usr/bin/env bash
# Lunaris Relay 安装与更新脚本。
set -Eeuo pipefail

REPO_URL="${REPO_URL:-https://github.com/kfcv301-maker/ix.git}"
REPO_REF="${REPO_REF:-1.4.5}"
INSTALL_DIR="${INSTALL_DIR:-/opt/flux-panel-enhanced}"
FRONTEND_PORT="${FRONTEND_PORT:-6366}"
BACKEND_PORT="${BACKEND_PORT:-6365}"
FRONTEND_BIND_ADDRESS="${FRONTEND_BIND_ADDRESS:-127.0.0.1}"
BACKEND_BIND_ADDRESS="${BACKEND_BIND_ADDRESS:-127.0.0.1}"

info() { printf '\033[1;34m[INFO]\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Lunaris Relay 管理脚本

用法：
  panel_install.sh install     安装或更新面板（默认）
  panel_install.sh update      拉取源码并重新构建，不删除数据库卷
  panel_install.sh status      查看容器状态
  panel_install.sh logs        查看容器日志
  panel_install.sh uninstall   停止并删除容器，保留数据库卷与 .env

可选环境变量：
  INSTALL_DIR=/opt/flux-panel-enhanced
  FRONTEND_PORT=6366 BACKEND_PORT=6365
  FRONTEND_BIND_ADDRESS=127.0.0.1  # 使用现有 Nginx 时保持仅本机监听；直连时设为 0.0.0.0
  BACKEND_BIND_ADDRESS=127.0.0.1   # 后端通过前端代理访问，默认只在本机监听
  REPO_URL=https://github.com/kfcv301-maker/ix.git REPO_REF=1.4.5
EOF
}

require_root() {
  [[ "${EUID}" -eq 0 ]] || fail "请以 root 执行，或使用：curl -fsSL <脚本地址> | sudo bash"
}

install_packages() {
  local packages=("$@")
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update -y
    DEBIAN_FRONTEND=noninteractive apt-get install -y "${packages[@]}"
  elif command -v dnf >/dev/null 2>&1; then
    dnf install -y "${packages[@]}"
  elif command -v yum >/dev/null 2>&1; then
    yum install -y "${packages[@]}"
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache "${packages[@]}"
  else
    fail "无法自动安装依赖，请先安装：${packages[*]}"
  fi
}

ensure_prerequisites() {
  command -v python3 >/dev/null 2>&1 || install_packages python3
  command -v curl >/dev/null 2>&1 || install_packages curl ca-certificates
  command -v git >/dev/null 2>&1 || install_packages git

  if ! command -v docker >/dev/null 2>&1; then
    info "未检测到 Docker，正在使用 Docker 官方安装脚本安装。"
    curl -fsSL https://get.docker.com -o /tmp/get-docker.sh
    sh /tmp/get-docker.sh
    rm -f /tmp/get-docker.sh
    systemctl enable --now docker 2>/dev/null || true
  fi

  docker compose version >/dev/null 2>&1 || fail "未检测到 Docker Compose v2，请安装 docker-compose-plugin 后重试。"
}

sync_source() {
  [[ "$REPO_REF" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]] || fail "仅允许经过审核的发布标签（例如 1.4.5）。"
  mkdir -p "$(dirname "$INSTALL_DIR")"
  if [[ -d "$INSTALL_DIR/.git" ]]; then
    info "更新已有源码：$INSTALL_DIR"
    [[ -z "$(git -C "$INSTALL_DIR" status --porcelain)" ]] ||
      fail "安装目录有未合并的本地修改；请先备份并合并修复，避免更新覆盖修改"
    git -C "$INSTALL_DIR" fetch --depth=1 origin "refs/tags/$REPO_REF:refs/tags/$REPO_REF"
    git -C "$INSTALL_DIR" checkout --detach "refs/tags/$REPO_REF"
  elif [[ -e "$INSTALL_DIR" ]]; then
    fail "安装目录已存在但不是 Git 仓库：$INSTALL_DIR"
  else
    info "下载项目源码"
    git clone --depth=1 --branch "$REPO_REF" "$REPO_URL" "$INSTALL_DIR"
  fi
}

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 24
  else
    tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48
  fi
}

validate_port() {
  [[ "$1" =~ ^[0-9]+$ ]] && (( "$1" >= 1 && "$1" <= 65535 )) || fail "端口无效：$1"
}

create_env_if_missing() {
  local env_file="$INSTALL_DIR/.env"
  if [[ -f "$env_file" ]]; then
    umask 077
    if ! grep -q '^VPS_CREDENTIAL_KEY=' "$env_file"; then
      printf 'VPS_CREDENTIAL_KEY=%s\n' "$(random_secret)" >> "$env_file"
    fi
    ensure_env_value() {
      local key="$1" value="$2"
      grep -q "^${key}=" "$env_file" || printf '%s=%s\n' "$key" "$value" >> "$env_file"
    }
    # Do not point an existing MySQL 5.7 volume at 8.4 in-place. The image
    # upgrade is explicit and staged; brand new installs use the current LTS.
    if ! grep -q '^MYSQL_IMAGE=' "$env_file"; then
      printf 'MYSQL_IMAGE=mysql:5.7\n' >> "$env_file"
    fi
    if ! grep -q '^PANEL_UPDATER_TOKEN=' "$env_file"; then
      umask 077
      printf '\nPANEL_UPDATER_TOKEN=%s\n' "$(random_secret)" >> "$env_file"
      chmod 600 "$env_file"
      ok "已为在线更新服务补充本机访问密钥"
    fi
    python3 "$INSTALL_DIR/updater/env_migration.py" "$env_file" "$REPO_REF"
    ok "已迁移部署配置并保留现有数据库凭据"
    return
  fi

  validate_port "$FRONTEND_PORT"
  validate_port "$BACKEND_PORT"
  (( FRONTEND_PORT != BACKEND_PORT )) || fail "前端与后端端口不能相同"

  umask 077
  cat > "$env_file" <<EOF
DB_NAME=flux_panel
DB_USER=flux_panel
DB_PASSWORD=$(random_secret)
JWT_SECRET=$(random_secret)
PANEL_UPDATER_TOKEN=$(random_secret)
PANEL_INITIAL_ADMIN_USERNAME=admin
PANEL_INITIAL_ADMIN_PASSWORD=$(random_secret)
VPS_CREDENTIAL_KEY=$(random_secret)
PANEL_RELEASE_REF=$REPO_REF
VPS_BACKEND_INSTALL_RELEASE=$REPO_REF
AGENT_INSTALL_RELEASE=$REPO_REF
MYSQL_IMAGE=mysql:8.4
FRONTEND_PORT=$FRONTEND_PORT
BACKEND_PORT=$BACKEND_PORT
FRONTEND_BIND_ADDRESS=$FRONTEND_BIND_ADDRESS
BACKEND_BIND_ADDRESS=$BACKEND_BIND_ADDRESS
EOF
  chmod 600 "$env_file"
  ok "已生成仅保存在本机的 .env"
}

compose() {
  docker compose --project-name flux-panel-enhanced --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" "$@"
}

install_or_update() {
  require_root
  ensure_prerequisites
  sync_source
  [[ -f "$INSTALL_DIR/docker-compose.yml" ]] || fail "项目缺少 docker-compose.yml"
  create_env_if_missing

  info "构建并启动服务（数据库卷不会被删除）"
  compose up -d --build --remove-orphans --wait --wait-timeout 240
  local ip
  ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
  ok "部署完成"
  printf '面板地址: http://%s:%s\n' "${ip:-服务器IP}" "$(grep '^FRONTEND_PORT=' "$INSTALL_DIR/.env" | cut -d= -f2)"
  printf '查看状态: docker compose --project-name flux-panel-enhanced -f %s/docker-compose.yml ps\n' "$INSTALL_DIR"
  printf '新安装的初始凭据在 %s/.env；旧安装若无此配置，请读取 backend 容器的 /app/config/initial-admin-credentials（仅管理员可读）。\n' "$INSTALL_DIR"
}

uninstall() {
  require_root
  [[ -f "$INSTALL_DIR/docker-compose.yml" ]] || fail "未找到已安装的面板：$INSTALL_DIR"
  read -r -p "停止并删除容器？数据库卷和 .env 会保留 (y/N): " confirm
  [[ "$confirm" =~ ^[Yy]$ ]] || { info "已取消"; return; }
  compose down --remove-orphans
  ok "容器已删除；数据库卷、.env 与源码仍保留在 $INSTALL_DIR"
}

main() {
  case "${1:-install}" in
    install|update) install_or_update ;;
    status) require_root; compose ps ;;
    logs) require_root; compose logs -f --tail=200 ;;
    uninstall) uninstall ;;
    -h|--help|help) usage ;;
    *) usage; exit 1 ;;
  esac
}

main "$@"
