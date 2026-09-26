#!/usr/bin/env bash
# Installs only the API backend and its required MySQL dependency.
set -Eeuo pipefail

REPO_URL="${REPO_URL:-https://github.com/kfcv301-maker/ix.git}"
REPO_REF="${REPO_REF:-1.4.5}"
INSTALL_DIR="${INSTALL_DIR:-/opt/flux-panel-backend}"
BACKEND_PORT="${BACKEND_PORT:-6365}"
BACKEND_BIND_ADDRESS="${BACKEND_BIND_ADDRESS:-127.0.0.1}"

info() { printf '\033[1;34m[INFO]\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

require_root() {
  [[ "${EUID}" -eq 0 ]] || fail "请使用 root 或 sudo 运行后端安装。"
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
  command -v curl >/dev/null 2>&1 || install_packages curl ca-certificates
  command -v git >/dev/null 2>&1 || install_packages git
  if ! command -v docker >/dev/null 2>&1; then
    if [[ -r /etc/os-release ]]; then
      . /etc/os-release
      [[ "${ID:-}" = "debian" && "${VERSION_ID:-}" = "11" ]] && fail "Debian 11 不受 Docker 官方安装器支持，请先升级系统。"
    fi
    info "正在安装后端运行所需的 Docker。"
    curl -fsSL https://get.docker.com | sh
    systemctl enable --now docker 2>/dev/null || true
  fi
  docker compose version >/dev/null 2>&1 || fail "未检测到 Docker Compose v2。"
}

sync_source() {
  [[ "$REPO_REF" =~ ^[0-9]+(\.[0-9]+){1,3}$ ]] || fail "仅允许经过审核的发布标签（例如 1.4.5）。"
  mkdir -p "$(dirname "$INSTALL_DIR")"
  if [[ -d "$INSTALL_DIR/.git" ]]; then
    [[ -z "$(git -C "$INSTALL_DIR" status --porcelain)" ]] || fail "后端目录含有本地修改；请先备份后再更新。"
    git -C "$INSTALL_DIR" fetch --depth=1 origin "refs/tags/$REPO_REF:refs/tags/$REPO_REF"
    git -C "$INSTALL_DIR" checkout --detach "refs/tags/$REPO_REF"
  elif [[ -e "$INSTALL_DIR" ]]; then
    fail "安装目录已存在但不是 Git 仓库：$INSTALL_DIR"
  else
    git clone --depth=1 --branch "$REPO_REF" "$REPO_URL" "$INSTALL_DIR"
  fi
}

random_secret() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 24; else tr -dc 'A-Za-z0-9' </dev/urandom | head -c 48; fi
}

ensure_env_value() {
  local key="$1" value="$2"
  grep -q "^${key}=" "$ENV_FILE" || printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
}

prepare_environment() {
  ENV_FILE="$INSTALL_DIR/.env"
  local existing_environment=0
  [[ -f "$ENV_FILE" ]] && existing_environment=1
  umask 077
  touch "$ENV_FILE"
  ensure_env_value DB_NAME flux_panel
  ensure_env_value DB_USER flux_panel
  ensure_env_value DB_PASSWORD "$(random_secret)"
  ensure_env_value JWT_SECRET "$(random_secret)"
  ensure_env_value PANEL_UPDATER_TOKEN "$(random_secret)"
  ensure_env_value PANEL_INITIAL_ADMIN_USERNAME admin
  ensure_env_value PANEL_INITIAL_ADMIN_PASSWORD "$(random_secret)"
  ensure_env_value VPS_CREDENTIAL_KEY "$(random_secret)"
  if [[ "$existing_environment" -eq 1 ]]; then
    ensure_env_value MYSQL_IMAGE mysql:5.7
  else
  ensure_env_value MYSQL_IMAGE mysql:8.4
  fi
  # Compose validates every declared service even when this installer starts
  # only mysql and backend. Keep frontend variables present without exposing
  # or starting the frontend container.
  ensure_env_value FRONTEND_PORT 6366
  ensure_env_value FRONTEND_BIND_ADDRESS 127.0.0.1
  ensure_env_value BACKEND_PORT "$BACKEND_PORT"
  ensure_env_value BACKEND_BIND_ADDRESS "$BACKEND_BIND_ADDRESS"
  chmod 600 "$ENV_FILE"
  local credential_file=/root/flux-panel-initial-admin-credentials
  if [[ ! -e "$credential_file" ]]; then
    printf 'username=%s\npassword=%s\n' \
      "$(sed -n 's/^PANEL_INITIAL_ADMIN_USERNAME=//p' "$ENV_FILE")" \
      "$(sed -n 's/^PANEL_INITIAL_ADMIN_PASSWORD=//p' "$ENV_FILE")" > "$credential_file"
    chmod 600 "$credential_file"
    info "初始管理员凭据已写入 $credential_file（仅 root 可读）。"
  fi
}

install_backend() {
  require_root
  ensure_prerequisites
  sync_source
  [[ -f "$INSTALL_DIR/docker-compose.yml" ]] || fail "项目缺少 docker-compose.yml"
  prepare_environment
  info "构建并启动后端与 MySQL；不会启动前端或更新器。"
  docker compose --project-name flux-panel-backend --env-file "$ENV_FILE" -f "$INSTALL_DIR/docker-compose.yml" up -d --build mysql backend
  ok "后端安装完成"
  docker compose --project-name flux-panel-backend --env-file "$ENV_FILE" -f "$INSTALL_DIR/docker-compose.yml" ps mysql backend
}

case "${1:-install}" in
  install|update) install_backend ;;
  *) fail "仅支持 install 或 update。" ;;
esac
