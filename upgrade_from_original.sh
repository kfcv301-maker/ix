#!/usr/bin/env bash
# Migrate an upstream bqlpfy/flux-panel Docker installation to this edition.
# It intentionally keeps the original directory and Docker volumes intact.
set -Eeuo pipefail

REPO_URL="${REPO_URL:-https://github.com/kfcv301-maker/ix.git}"
BRANCH="${BRANCH:-main}"
SOURCE_DIR="$PWD"
TARGET_DIR="${TARGET_DIR:-/opt/flux-panel-enhanced}"
DRY_RUN=false
CUTOVER_STARTED=false
UPGRADE_FINISHED=false

info() { printf '\033[1;34m[INFO]\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Flux Panel Enhanced 原版迁移脚本

用法：
  upgrade_from_original.sh [--source-dir 原版目录] [--target-dir 新版目录] [--dry-run]

默认把当前目录视为原版面板目录，并迁移到 /opt/flux-panel-enhanced。
迁移会：导出原数据库、保留原 .env、复用 mysql_data 卷、构建新版并验证后端。
原目录和 Docker 卷不会删除；新版启动失败会自动重新启动原容器。
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-dir) SOURCE_DIR="$2"; shift 2 ;;
    --target-dir) TARGET_DIR="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help|help) usage; exit 0 ;;
    *) fail "未知参数：$1" ;;
  esac
done

require_root() {
  [[ "${EUID}" -eq 0 ]] || fail "请以 root 或 sudo 执行。"
}

detect_compose() {
  if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
  else
    fail "未检测到 Docker Compose。"
  fi
}

legacy_compose() {
  "${COMPOSE[@]}" --env-file "$SOURCE_DIR/.env" -f "$SOURCE_DIR/docker-compose.yml" "$@"
}

target_compose() {
  "${COMPOSE[@]}" --project-name flux-panel-enhanced --env-file "$TARGET_DIR/.env" -f "$TARGET_DIR/docker-compose.yml" "$@"
}

env_value() {
  local key="$1"
  local value
  value="$(grep -m1 -E "^${key}=" "$SOURCE_DIR/.env" | cut -d= -f2- || true)"
  [[ -n "$value" ]] || fail "原 .env 缺少 ${key}，已停止迁移。"
  printf '%s' "$value"
}

rollback() {
  local code=$?
  trap - EXIT
  if [[ "$CUTOVER_STARTED" == true && "$UPGRADE_FINISHED" == false ]]; then
    info "新版未通过检查，正在停止新版并恢复原容器。"
    target_compose down --remove-orphans >/dev/null 2>&1 || true
    legacy_compose up -d >/dev/null 2>&1 || true
  fi
  exit "$code"
}
trap rollback EXIT

validate_source() {
  SOURCE_DIR="$(cd "$SOURCE_DIR" && pwd)"
  [[ -f "$SOURCE_DIR/.env" ]] || fail "未找到原版 .env：$SOURCE_DIR/.env"
  [[ -f "$SOURCE_DIR/docker-compose.yml" ]] || fail "未找到原版 docker-compose.yml。"
  [[ "$SOURCE_DIR" != "$TARGET_DIR" ]] || fail "原版和新版目录不能相同。"
  for key in DB_NAME DB_USER DB_PASSWORD JWT_SECRET FRONTEND_PORT BACKEND_PORT; do
    env_value "$key" >/dev/null
  done
  docker volume inspect mysql_data >/dev/null 2>&1 || fail "未找到原版 mysql_data 卷，已停止迁移。"
}

clone_target() {
  [[ ! -e "$TARGET_DIR" ]] || fail "新版目录已存在：$TARGET_DIR；为避免覆盖未知文件，已停止迁移。"
  mkdir -p "$(dirname "$TARGET_DIR")"
  info "下载新版源码"
  git clone --depth=1 --branch "$BRANCH" "$REPO_URL" "$TARGET_DIR"
}

copy_runtime_env() {
  local db_name db_user db_password jwt_secret frontend_port backend_port
  db_name="$(env_value DB_NAME)"
  db_user="$(env_value DB_USER)"
  db_password="$(env_value DB_PASSWORD)"
  jwt_secret="$(env_value JWT_SECRET)"
  frontend_port="$(env_value FRONTEND_PORT)"
  backend_port="$(env_value BACKEND_PORT)"
  umask 077
  {
    printf 'DB_NAME=%s\n' "$db_name"
    printf 'DB_USER=%s\n' "$db_user"
    printf 'DB_PASSWORD=%s\n' "$db_password"
    printf 'JWT_SECRET=%s\n' "$jwt_secret"
    printf 'FRONTEND_PORT=%s\n' "$frontend_port"
    printf 'BACKEND_PORT=%s\n' "$backend_port"
    # The upstream Compose file names these volumes explicitly. Reusing them
    # makes the new containers operate on the same data rather than an import.
    printf 'MYSQL_DATA_VOLUME=mysql_data\n'
    printf 'BACKEND_LOG_VOLUME=backend_logs\n'
  } > "$TARGET_DIR/.env"
  chmod 600 "$TARGET_DIR/.env"
}

backup_database() {
  local backup_dir mysql_container
  backup_dir="$TARGET_DIR/.upgrade-backups/$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$backup_dir"
  chmod 700 "$TARGET_DIR/.upgrade-backups" "$backup_dir"
  legacy_compose up -d mysql >/dev/null
  mysql_container="$(legacy_compose ps -q mysql)"
  [[ -n "$mysql_container" ]] || fail "无法定位原版 MySQL 容器。"
  info "正在导出原数据库备份"
  docker exec "$mysql_container" sh -c 'exec mysqldump --single-transaction --routines --events -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' > "$backup_dir/mysql.sql"
  [[ -s "$backup_dir/mysql.sql" ]] || fail "数据库备份为空，已停止迁移。"
  ok "数据库备份已保存在新版目录的本机私有备份目录"
}

wait_for_new_backend() {
  local backend_port
  backend_port="$(grep -m1 '^BACKEND_PORT=' "$TARGET_DIR/.env" | cut -d= -f2-)"
  for _ in $(seq 1 90); do
    if curl -fsS --max-time 3 "http://127.0.0.1:${backend_port}/flow/test" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

main() {
  require_root
  command -v docker >/dev/null 2>&1 || fail "未检测到 Docker。"
  command -v git >/dev/null 2>&1 || fail "未检测到 Git。"
  command -v curl >/dev/null 2>&1 || fail "未检测到 curl。"
  detect_compose
  validate_source

  if [[ "$DRY_RUN" == true ]]; then
    ok "检查通过：原版数据卷和运行配置可迁移；未修改任何容器、文件或数据。"
    exit 0
  fi

  clone_target
  copy_runtime_env
  backup_database

  info "停止原面板容器（不删除容器、卷或原目录）"
  CUTOVER_STARTED=true
  legacy_compose stop

  info "构建并启动新版面板，复用原数据库卷"
  target_compose up -d --build --remove-orphans
  wait_for_new_backend || fail "新版后端在 180 秒内未通过检查。"

  UPGRADE_FINISHED=true
  ok "升级完成：节点、用户、隧道、转发、流量和密钥均继续使用原数据库。"
  printf '新版目录：%s\n' "$TARGET_DIR"
  printf '原目录仍保留：%s\n' "$SOURCE_DIR"
  printf '日后更新：curl -fsSL https://raw.githubusercontent.com/kfcv301-maker/ix/main/panel_install.sh | sudo bash -s -- update\n'
}

main
