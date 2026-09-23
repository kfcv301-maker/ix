#!/usr/bin/env bash
# Lunaris Relay 节点 Agent 安装脚本。
set -Eeuo pipefail

AGENT_RELEASE_BASE="${AGENT_RELEASE_BASE:-https://github.com/kfcv301-maker/ix/releases/latest/download}"
LEGACY_AGENT_RAW_BASE="${LEGACY_AGENT_RAW_BASE:-https://raw.githubusercontent.com/kfcv301-maker/ix/main}"
INSTALL_DIR="${INSTALL_DIR:-/etc/flux-panel-agent}"
SERVICE_NAME="flux-panel-agent"
DDNS_SERVICE_NAME="flux-panel-ddns"
DDNS_TIMER_NAME="flux-panel-ddns.timer"
TCP_GUARD_SERVICE_NAME="flux-panel-tcp-guard"
SYSCTL_FILE="/etc/sysctl.d/99-flux-panel-network.conf"
SYSCTL_BACKUP="/etc/sysctl.d/99-flux-panel-network.conf.before-flux-panel"
SERVER_ADDR=""
NODE_SECRET=""
DDNS_MODE="unchanged"
CF_API_TOKEN=""
CF_RECORD_NAME=""
TUNE_TCP=1
TCP_PROFILE_OVERRIDE=""
TCP_PROFILE_MIN=""
TCP_PROFILE_MAX=""
TCP_AUTO_TUNE=0
HOST_MEMORY_MB=0
HOST_CPU_CORES=1
SELECTED_CONGESTION_CONTROL=""
FQ_SUPPORTED=0
TC_AVAILABLE=0
APPLIED_SYSCTL_KEYS=()
TUNING_PROFILE="balanced"
R_MEM_MAX=12582912
W_MEM_MAX=12582912
TCP_RMEM="4096 131072 12582912"
TCP_WMEM="4096 16384 12582912"
SOMAXCONN=8192
TCP_MAX_SYN_BACKLOG=4096
NETDEV_MAX_BACKLOG=4096
HOST_RECOMMENDED_PROFILE="balanced"
EFFECTIVE_TCP_PROFILE_MIN=""
EFFECTIVE_TCP_PROFILE_MAX=""
INIT_SYSTEM=""

info() { printf '\033[1;34m[INFO]\033[0m %s\n' "$*"; }
ok() { printf '\033[1;32m[ OK ]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Lunaris Relay 节点 Agent 安装脚本

用法：
  install.sh --panel https://panel.example.com --token 节点独立密钥
  install.sh --panel https://panel.example.com --token 节点独立密钥 --skip-tcp-tuning
  install.sh --panel https://panel.example.com --token 节点独立密钥 --tcp-profile balanced
  install.sh --panel https://panel.example.com --token 节点独立密钥 --tcp-auto-tune --tcp-profile-min small --tcp-profile-max standard
  install.sh --panel https://panel.example.com --token 节点独立密钥 --cf-api-token Cloudflare令牌 --cf-record node.example.com
  install.sh --panel https://panel.example.com --token 节点独立密钥 --disable-ddns
  install.sh --uninstall

可选环境变量：
  AGENT_RELEASE_BASE=https://github.com/kfcv301-maker/ix/releases/latest/download
  LEGACY_AGENT_RAW_BASE=https://raw.githubusercontent.com/kfcv301-maker/ix/main
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

detect_init_system() {
  if command -v systemctl >/dev/null 2>&1 && [[ -d /run/systemd/system ]]; then
    INIT_SYSTEM="systemd"
  elif command -v rc-service >/dev/null 2>&1 && command -v rc-update >/dev/null 2>&1; then
    INIT_SYSTEM="openrc"
  else
    fail "未检测到 systemd 或 OpenRC，暂不能创建开机自启服务。"
  fi
  info "检测到服务管理器：$INIT_SYSTEM"
}

detect_host_profile() {
  HOST_CPU_CORES="$(nproc 2>/dev/null || getconf _NPROCESSORS_ONLN 2>/dev/null || printf '1')"
  HOST_MEMORY_MB="$(awk '/MemTotal:/ {print int($2 / 1024)}' /proc/meminfo 2>/dev/null || printf '0')"
  info "节点检测：${HOST_CPU_CORES} 核 / ${HOST_MEMORY_MB} MB 内存 / $(uname -r)"
}

select_tuning_profile() {
  # 16 MB / 16384 / 8192 是硬上限。内存小只向下收缩，绝不放大。
  if (( HOST_MEMORY_MB > 0 && HOST_MEMORY_MB < 512 )); then
    HOST_RECOMMENDED_PROFILE="tiny"
  elif (( HOST_MEMORY_MB > 0 && HOST_MEMORY_MB < 1024 )); then
    HOST_RECOMMENDED_PROFILE="small"
  elif (( HOST_MEMORY_MB == 0 )) || (( HOST_MEMORY_MB < 2048 )) || (( HOST_CPU_CORES < 2 )); then
    HOST_RECOMMENDED_PROFILE="balanced"
  else
    HOST_RECOMMENDED_PROFILE="standard"
  fi

  if (( TCP_AUTO_TUNE == 1 )); then
    is_valid_tuning_profile "$TCP_PROFILE_MIN" || fail "TCP 动态调优下限无效：$TCP_PROFILE_MIN"
    is_valid_tuning_profile "$TCP_PROFILE_MAX" || fail "TCP 动态调优上限无效：$TCP_PROFILE_MAX"
    if (( $(tuning_profile_rank "$TCP_PROFILE_MIN") > $(tuning_profile_rank "$TCP_PROFILE_MAX") )); then
      fail "TCP 动态调优下限不能高于上限"
    fi
    EFFECTIVE_TCP_PROFILE_MIN="$TCP_PROFILE_MIN"
    EFFECTIVE_TCP_PROFILE_MAX="$TCP_PROFILE_MAX"
    # 用户可设置区间，但不能让低内存机器越过本机安全上限。
    if (( $(tuning_profile_rank "$EFFECTIVE_TCP_PROFILE_MAX") > $(tuning_profile_rank "$HOST_RECOMMENDED_PROFILE") )); then
      EFFECTIVE_TCP_PROFILE_MAX="$HOST_RECOMMENDED_PROFILE"
    fi
    if (( $(tuning_profile_rank "$EFFECTIVE_TCP_PROFILE_MIN") > $(tuning_profile_rank "$EFFECTIVE_TCP_PROFILE_MAX") )); then
      EFFECTIVE_TCP_PROFILE_MIN="$EFFECTIVE_TCP_PROFILE_MAX"
    fi
    set_tuning_profile "$EFFECTIVE_TCP_PROFILE_MAX"
    info "启用动态 TCP 调优：${EFFECTIVE_TCP_PROFILE_MIN}–${EFFECTIVE_TCP_PROFILE_MAX}（当前从 $TUNING_PROFILE 开始）"
    return
  fi

  if [[ -n "$TCP_PROFILE_OVERRIDE" ]]; then
    is_valid_tuning_profile "$TCP_PROFILE_OVERRIDE" || fail "TCP 调优档位无效：$TCP_PROFILE_OVERRIDE"
    set_tuning_profile "$TCP_PROFILE_OVERRIDE"
    info "使用面板选择的 TCP 档位：$TUNING_PROFILE（收发缓存上限 $((R_MEM_MAX / 1024 / 1024)) MB，接入队列 $SOMAXCONN）"
    return
  fi

  set_tuning_profile "$HOST_RECOMMENDED_PROFILE"
  info "自动选择 TCP 档位：$TUNING_PROFILE（收发缓存上限 $((R_MEM_MAX / 1024 / 1024)) MB，接入队列 $SOMAXCONN）"
}

tuning_profile_rank() {
  case "$1" in
    tiny) printf '1' ;;
    small) printf '2' ;;
    balanced) printf '3' ;;
    standard) printf '4' ;;
    *) return 1 ;;
  esac
}

is_valid_tuning_profile() {
  tuning_profile_rank "$1" >/dev/null 2>&1
}

set_tuning_profile() {
  case "$1" in
    tiny)
      TUNING_PROFILE="tiny"; R_MEM_MAX=4194304; W_MEM_MAX=4194304
      TCP_RMEM="4096 65536 4194304"; TCP_WMEM="4096 16384 4194304"
      SOMAXCONN=2048; TCP_MAX_SYN_BACKLOG=1024; NETDEV_MAX_BACKLOG=2048
      ;;
    small)
      TUNING_PROFILE="small"; R_MEM_MAX=8388608; W_MEM_MAX=8388608
      TCP_RMEM="4096 98304 8388608"; TCP_WMEM="4096 16384 8388608"
      SOMAXCONN=4096; TCP_MAX_SYN_BACKLOG=2048; NETDEV_MAX_BACKLOG=4096
      ;;
    balanced)
      TUNING_PROFILE="balanced"; R_MEM_MAX=12582912; W_MEM_MAX=12582912
      TCP_RMEM="4096 131072 12582912"; TCP_WMEM="4096 16384 12582912"
      SOMAXCONN=8192; TCP_MAX_SYN_BACKLOG=4096; NETDEV_MAX_BACKLOG=4096
      ;;
    standard)
      TUNING_PROFILE="standard"; R_MEM_MAX=16777216; W_MEM_MAX=16777216
      TCP_RMEM="4096 131072 16777216"; TCP_WMEM="4096 16384 16777216"
      SOMAXCONN=16384; TCP_MAX_SYN_BACKLOG=8192; NETDEV_MAX_BACKLOG=8192
      ;;
    *) return 1 ;;
  esac
}

select_tcp_capabilities() {
  command -v sysctl >/dev/null 2>&1 || fail "未找到 sysctl，无法应用 TCP 调优。"
  local available current
  available="$(sysctl -n net.ipv4.tcp_available_congestion_control 2>/dev/null || true)"
  if ! grep -qw bbr <<<"$available" && command -v modprobe >/dev/null 2>&1; then
    modprobe tcp_bbr 2>/dev/null || true
    available="$(sysctl -n net.ipv4.tcp_available_congestion_control 2>/dev/null || true)"
  fi
  if grep -qw bbr <<<"$available"; then
    SELECTED_CONGESTION_CONTROL="bbr"
    ok "检测到 BBR，使用 BBR 拥塞控制"
  else
    # 旧内核无法凭脚本安装 BBR 模块，自动保留其现有算法，不能阻塞节点安装。
    current="$(sysctl -n net.ipv4.tcp_congestion_control 2>/dev/null || printf 'cubic')"
    SELECTED_CONGESTION_CONTROL="${current:-cubic}"
    info "内核未提供 BBR，自动保留 $SELECTED_CONGESTION_CONTROL；其余 TCP 参数仍会应用。"
  fi

  if [[ "$(sysctl -n net.core.default_qdisc 2>/dev/null || true)" == "fq" ]]; then
    FQ_SUPPORTED=1
  elif command -v modprobe >/dev/null 2>&1 && modprobe sch_fq 2>/dev/null; then
    FQ_SUPPORTED=1
  fi
  if command -v tc >/dev/null 2>&1; then
    TC_AVAILABLE=1
    (( FQ_SUPPORTED == 1 )) && ok "检测到 FQ 与 tc，尝试对默认出口网卡立即应用 FQ"
  else
    info "未找到 tc；仅持久化默认 FQ 队列算法。"
  fi
  (( FQ_SUPPORTED == 1 )) || info "内核未提供 FQ，自动保留当前队列算法。"
}

append_sysctl_if_supported() {
  local output_file="$1" key="$2" value="$3"
  if sysctl -n "$key" >/dev/null 2>&1; then
    printf '%s = %s\n' "$key" "$value" >> "$output_file"
    APPLIED_SYSCTL_KEYS+=("$key")
  else
    info "内核不支持 $key，自动跳过该项。"
  fi
}

apply_fq_to_default_interfaces() {
  command -v ip >/dev/null 2>&1 || return 0
  (( FQ_SUPPORTED == 1 && TC_AVAILABLE == 1 )) || {
    info "现有网卡不做即时 qdisc 调整；默认队列算法会在网络初始化时应用。"
    return 0
  }

  local iface
  while read -r iface; do
    [[ -n "$iface" ]] || continue
    if tc qdisc replace dev "$iface" root fq 2>/dev/null; then
      ok "网卡 $iface 已切换至 FQ"
    else
      info "网卡 $iface 未能即时切换 FQ；默认队列算法已持久化。"
    fi
  done < <(ip -o route show default 2>/dev/null | awk '{print $5}' | sort -u)
}

apply_tcp_tuning() {
  [[ "$TUNE_TCP" == "1" ]] || {
    info "已按参数跳过 TCP 调优。"
    return
  }

  detect_host_profile
  select_tuning_profile
  select_tcp_capabilities
  local temporary_file
  temporary_file="$(mktemp)"
  APPLIED_SYSCTL_KEYS=()
  cat > "$temporary_file" <<EOF
# Lunaris Relay 节点网络调优。
EOF

  if (( FQ_SUPPORTED == 1 )); then
    append_sysctl_if_supported "$temporary_file" net.core.default_qdisc fq
  fi
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_congestion_control "$SELECTED_CONGESTION_CONTROL"
  append_sysctl_if_supported "$temporary_file" net.core.rmem_max "$R_MEM_MAX"
  append_sysctl_if_supported "$temporary_file" net.core.wmem_max "$W_MEM_MAX"
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_rmem "$TCP_RMEM"
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_wmem "$TCP_WMEM"
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_moderate_rcvbuf 1
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_mtu_probing 1
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_fastopen 3
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_slow_start_after_idle 0
  append_sysctl_if_supported "$temporary_file" net.core.somaxconn "$SOMAXCONN"
  append_sysctl_if_supported "$temporary_file" net.ipv4.tcp_max_syn_backlog "$TCP_MAX_SYN_BACKLOG"
  append_sysctl_if_supported "$temporary_file" net.core.netdev_max_backlog "$NETDEV_MAX_BACKLOG"

  # 先应用临时文件；任一内核参数不被支持时，不覆盖原有持久化配置。
  if ! sysctl -p "$temporary_file"; then
    rm -f "$temporary_file"
    fail "TCP 调优参数未能完整应用，未覆盖原有 sysctl 配置。"
  fi

  if [[ -f "$SYSCTL_FILE" && ! -f "$SYSCTL_BACKUP" ]]; then
    cp -p "$SYSCTL_FILE" "$SYSCTL_BACKUP"
    info "已备份原有同名 sysctl 配置：$SYSCTL_BACKUP"
  fi
  install -Dm644 "$temporary_file" "$SYSCTL_FILE"
  rm -f "$temporary_file"
  apply_fq_to_default_interfaces
  if [[ "$SELECTED_CONGESTION_CONTROL" == "bbr" ]]; then
    ok "TCP BBR/FQ 调优已完成并持久化"
  else
    ok "兼容模式 TCP 调优已完成并持久化（当前内核不支持 BBR）"
  fi
}

write_tcp_tuning_guard() {
  cat > "$INSTALL_DIR/tcp-tuning-guard.sh" <<'EOF'
#!/usr/bin/env bash
# Local memory-pressure guard for Lunaris Relay TCP settings. It only changes
# future socket growth limits; it never restarts GOST or closes connections.
set -Eeuo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
CONFIG_FILE="$BASE_DIR/tcp-tuning.env"
RUNTIME_FILE="$BASE_DIR/tcp-tuning-runtime.env"
STATUS_FILE="$BASE_DIR/tcp-tuning-status.json"

[[ -r "$CONFIG_FILE" ]] || exit 0
# The installer creates these root-owned 0600 files. Do not make them writable
# by non-root users, because this service runs with root privileges.
source "$CONFIG_FILE"
[[ -r "$RUNTIME_FILE" ]] && source "$RUNTIME_FILE"

CURRENT_PROFILE="${CURRENT_PROFILE:-$MAX_PROFILE}"
LOW_SAMPLES="${LOW_SAMPLES:-0}"
HEALTHY_SAMPLES="${HEALTHY_SAMPLES:-0}"
LAST_REASON="${LAST_REASON:-启动后等待首次采样}"

rank() {
  case "$1" in
    tiny) printf '1' ;;
    small) printf '2' ;;
    balanced) printf '3' ;;
    standard) printf '4' ;;
    *) return 1 ;;
  esac
}

profile_from_rank() {
  case "$1" in
    1) printf 'tiny' ;;
    2) printf 'small' ;;
    3) printf 'balanced' ;;
    4) printf 'standard' ;;
    *) return 1 ;;
  esac
}

profile_values() {
  case "$1" in
    tiny)
      R_MEM_MAX=4194304; W_MEM_MAX=4194304; TCP_RMEM="4096 65536 4194304"; TCP_WMEM="4096 16384 4194304"; SOMAXCONN=2048; TCP_MAX_SYN_BACKLOG=1024; NETDEV_MAX_BACKLOG=2048 ;;
    small)
      R_MEM_MAX=8388608; W_MEM_MAX=8388608; TCP_RMEM="4096 98304 8388608"; TCP_WMEM="4096 16384 8388608"; SOMAXCONN=4096; TCP_MAX_SYN_BACKLOG=2048; NETDEV_MAX_BACKLOG=4096 ;;
    balanced)
      R_MEM_MAX=12582912; W_MEM_MAX=12582912; TCP_RMEM="4096 131072 12582912"; TCP_WMEM="4096 16384 12582912"; SOMAXCONN=8192; TCP_MAX_SYN_BACKLOG=4096; NETDEV_MAX_BACKLOG=4096 ;;
    standard)
      R_MEM_MAX=16777216; W_MEM_MAX=16777216; TCP_RMEM="4096 131072 16777216"; TCP_WMEM="4096 16384 16777216"; SOMAXCONN=16384; TCP_MAX_SYN_BACKLOG=8192; NETDEV_MAX_BACKLOG=8192 ;;
    *) return 1 ;;
  esac
}

write_runtime() {
  local temporary
  temporary="$(mktemp "${RUNTIME_FILE}.XXXXXX")"
  cat > "$temporary" <<EOF_RUNTIME
CURRENT_PROFILE='$CURRENT_PROFILE'
LOW_SAMPLES=$LOW_SAMPLES
HEALTHY_SAMPLES=$HEALTHY_SAMPLES
LAST_REASON='$LAST_REASON'
EOF_RUNTIME
  chmod 600 "$temporary"
  mv -f "$temporary" "$RUNTIME_FILE"

  temporary="$(mktemp "${STATUS_FILE}.XXXXXX")"
  printf '{"mode":"auto","minimum":"%s","maximum":"%s","current":"%s","reason":"%s"}\n' \
    "$MIN_PROFILE" "$MAX_PROFILE" "$CURRENT_PROFILE" "$LAST_REASON" > "$temporary"
  chmod 644 "$temporary"
  mv -f "$temporary" "$STATUS_FILE"
}

replace_profile_settings() {
  local profile="$1" temporary persisted
  profile_values "$profile" || return 1
  temporary="$(mktemp)"
  : > "$temporary"
  for pair in \
    "net.core.rmem_max=$R_MEM_MAX" \
    "net.core.wmem_max=$W_MEM_MAX" \
    "net.ipv4.tcp_rmem=$TCP_RMEM" \
    "net.ipv4.tcp_wmem=$TCP_WMEM" \
    "net.core.somaxconn=$SOMAXCONN" \
    "net.ipv4.tcp_max_syn_backlog=$TCP_MAX_SYN_BACKLOG" \
    "net.core.netdev_max_backlog=$NETDEV_MAX_BACKLOG"; do
    local key="${pair%%=*}" value="${pair#*=}"
    if sysctl -n "$key" >/dev/null 2>&1; then
      printf '%s = %s\n' "$key" "$value" >> "$temporary"
    fi
  done
  if ! sysctl -p "$temporary" >/dev/null; then
    rm -f "$temporary"
    return 1
  fi
  rm -f "$temporary"

  # The main file was created by this installer. Replace only the seven
  # profile-dependent keys and preserve BBR/FQ and all other safe settings.
  if [[ -r "$SYSCTL_FILE" ]]; then
    persisted="$(mktemp "${SYSCTL_FILE}.XXXXXX")"
    awk -v rmem="$R_MEM_MAX" -v wmem="$W_MEM_MAX" -v trmem="$TCP_RMEM" -v twmem="$TCP_WMEM" \
      -v somaxconn="$SOMAXCONN" -v syn="$TCP_MAX_SYN_BACKLOG" -v netdev="$NETDEV_MAX_BACKLOG" '
      $1 == "net.core.rmem_max" { print "net.core.rmem_max = " rmem; next }
      $1 == "net.core.wmem_max" { print "net.core.wmem_max = " wmem; next }
      $1 == "net.ipv4.tcp_rmem" { print "net.ipv4.tcp_rmem = " trmem; next }
      $1 == "net.ipv4.tcp_wmem" { print "net.ipv4.tcp_wmem = " twmem; next }
      $1 == "net.core.somaxconn" { print "net.core.somaxconn = " somaxconn; next }
      $1 == "net.ipv4.tcp_max_syn_backlog" { print "net.ipv4.tcp_max_syn_backlog = " syn; next }
      $1 == "net.core.netdev_max_backlog" { print "net.core.netdev_max_backlog = " netdev; next }
      { print }
    ' "$SYSCTL_FILE" > "$persisted"
    chmod 644 "$persisted"
    mv -f "$persisted" "$SYSCTL_FILE"
  fi
}

sample_once() {
  local total_kb available_kb total_mb available_mb critical_mb low_mb recovery_mb current_rank min_rank max_rank target_rank target
  total_kb="$(awk '/^MemTotal:/ {print $2}' /proc/meminfo 2>/dev/null || true)"
  available_kb="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo 2>/dev/null || true)"
  [[ "$total_kb" =~ ^[0-9]+$ && "$available_kb" =~ ^[0-9]+$ && "$total_kb" -gt 0 ]] || {
    LAST_REASON="无法读取 MemAvailable，保持当前档位"
    write_runtime
    return 0
  }

  total_mb=$(( total_kb / 1024 ))
  available_mb=$(( available_kb / 1024 ))
  critical_mb=$(( total_mb * 12 / 100 )); (( critical_mb < 96 )) && critical_mb=96
  low_mb=$(( total_mb * 20 / 100 )); (( low_mb < 160 )) && low_mb=160
  recovery_mb=$(( total_mb * 40 / 100 ))
  current_rank="$(rank "$CURRENT_PROFILE")"
  min_rank="$(rank "$MIN_PROFILE")"
  max_rank="$(rank "$MAX_PROFILE")"
  target_rank="$current_rank"

  if (( available_mb <= critical_mb )); then
    LOW_SAMPLES=0
    HEALTHY_SAMPLES=0
    if (( current_rank > min_rank )); then
      target_rank=$(( current_rank - 1 ))
      LAST_REASON="可用内存 ${available_mb} MB，低于紧急阈值 ${critical_mb} MB"
    else
      LAST_REASON="可用内存 ${available_mb} MB，已处于用户允许的最低档"
    fi
  elif (( available_mb <= low_mb )); then
    LOW_SAMPLES=$(( LOW_SAMPLES + 1 ))
    HEALTHY_SAMPLES=0
    LAST_REASON="可用内存 ${available_mb} MB，低于压力阈值 ${low_mb} MB（连续 ${LOW_SAMPLES}/3 次）"
    if (( LOW_SAMPLES >= 3 && current_rank > min_rank )); then
      target_rank=$(( current_rank - 1 ))
      LOW_SAMPLES=0
    fi
  else
    LOW_SAMPLES=0
    if (( available_mb >= recovery_mb )); then
      HEALTHY_SAMPLES=$(( HEALTHY_SAMPLES + 1 ))
      LAST_REASON="可用内存 ${available_mb} MB，恢复观察 ${HEALTHY_SAMPLES}/10 次"
      if (( HEALTHY_SAMPLES >= 10 && current_rank < max_rank )); then
        target_rank=$(( current_rank + 1 ))
        HEALTHY_SAMPLES=0
      fi
    else
      HEALTHY_SAMPLES=0
      LAST_REASON="可用内存 ${available_mb} MB，保持当前档位"
    fi
  fi

  target="$(profile_from_rank "$target_rank")"
  if [[ "$target" != "$CURRENT_PROFILE" ]]; then
    if replace_profile_settings "$target"; then
      CURRENT_PROFILE="$target"
      LAST_REASON="${LAST_REASON}，已切换至 ${CURRENT_PROFILE}"
    else
      LAST_REASON="${LAST_REASON}，切换 ${target} 失败，保持 ${CURRENT_PROFILE}"
    fi
  fi
  write_runtime
}

if [[ "${1:-}" == "--watch" ]]; then
  while true; do
    sample_once || true
    sleep 60
  done
else
  sample_once
fi
EOF
  chmod 700 "$INSTALL_DIR/tcp-tuning-guard.sh"
}

configure_tcp_tuning_guard() {
  if (( TCP_AUTO_TUNE == 0 )); then
    if [[ "$INIT_SYSTEM" == "systemd" ]]; then
      systemctl disable --now "${TCP_GUARD_SERVICE_NAME}.timer" 2>/dev/null || true
      rm -f "/etc/systemd/system/${TCP_GUARD_SERVICE_NAME}.service" "/etc/systemd/system/${TCP_GUARD_SERVICE_NAME}.timer"
      systemctl daemon-reload
    else
      rc-service "$TCP_GUARD_SERVICE_NAME" stop 2>/dev/null || true
      rc-update del "$TCP_GUARD_SERVICE_NAME" default 2>/dev/null || true
      rm -f "/etc/init.d/$TCP_GUARD_SERVICE_NAME"
    fi
    rm -f "$INSTALL_DIR/tcp-tuning-guard.sh" "$INSTALL_DIR/tcp-tuning.env" "$INSTALL_DIR/tcp-tuning-runtime.env" "$INSTALL_DIR/tcp-tuning-status.json"
    return
  fi

  cat > "$INSTALL_DIR/tcp-tuning.env" <<EOF
MIN_PROFILE='$EFFECTIVE_TCP_PROFILE_MIN'
MAX_PROFILE='$EFFECTIVE_TCP_PROFILE_MAX'
SYSCTL_FILE='$SYSCTL_FILE'
EOF
  chmod 600 "$INSTALL_DIR/tcp-tuning.env"
  write_tcp_tuning_guard

  if [[ "$INIT_SYSTEM" == "systemd" ]]; then
    cat > "/etc/systemd/system/${TCP_GUARD_SERVICE_NAME}.service" <<EOF
[Unit]
Description=Lunaris Relay adaptive TCP memory guard
After=network-online.target

[Service]
Type=oneshot
ExecStart=$INSTALL_DIR/tcp-tuning-guard.sh
EOF
    cat > "/etc/systemd/system/${TCP_GUARD_SERVICE_NAME}.timer" <<EOF
[Unit]
Description=Run Lunaris Relay adaptive TCP memory guard every minute

[Timer]
OnBootSec=90s
OnUnitActiveSec=1min
AccuracySec=10s
Persistent=true
Unit=${TCP_GUARD_SERVICE_NAME}.service

[Install]
WantedBy=timers.target
EOF
    systemctl daemon-reload
    systemctl enable --now "${TCP_GUARD_SERVICE_NAME}.timer" >/dev/null
    systemctl start "${TCP_GUARD_SERVICE_NAME}.service"
  else
    cat > "/etc/init.d/$TCP_GUARD_SERVICE_NAME" <<EOF
#!/sbin/openrc-run
description="Lunaris Relay adaptive TCP memory guard"
command="$INSTALL_DIR/tcp-tuning-guard.sh"
command_args="--watch"
command_user="root"
supervisor="supervise-daemon"
respawn_delay=3
respawn_max=0
EOF
    chmod 755 "/etc/init.d/$TCP_GUARD_SERVICE_NAME"
    rc-update add "$TCP_GUARD_SERVICE_NAME" default >/dev/null
    rc-service "$TCP_GUARD_SERVICE_NAME" restart >/dev/null 2>&1 || rc-service "$TCP_GUARD_SERVICE_NAME" start >/dev/null
  fi
  ok "动态 TCP 内存保护已启用（每分钟采样；不重启 Agent、不主动断开转发）"
}

download_agent() {
  local arch url checksum_url fallback_url fallback_checksum_url temp_bin temp_checksum expected actual
  arch="$(architecture)"
  # Release assets are immutable. The script path remains stable on main, but
  # a node install must not silently fetch an old tracked binary from Git.
  url="$AGENT_RELEASE_BASE/flux-panel-agent-linux-$arch"
  checksum_url="$url.sha256"
  fallback_url="$LEGACY_AGENT_RAW_BASE/artifacts/flux-panel-agent-linux-$arch"
  fallback_checksum_url="$fallback_url.sha256"
  temp_bin="$(mktemp)"
  temp_checksum="$(mktemp)"
  # RETURN trap 会在局部变量销毁后执行；在注册时展开临时路径，避免 set -u 触发未绑定变量。
  trap "rm -f -- \"${temp_bin}\" \"${temp_checksum}\"" RETURN

  command -v curl >/dev/null 2>&1 || fail "请先安装 curl。"
  info "下载 Linux/$arch 节点 Agent"
  if ! curl --fail --location --retry 3 --connect-timeout 15 "$url" -o "$temp_bin"; then
    # A just-pushed main commit may briefly precede its GitHub Release. Keep
    # historical installs working during that window, still with a checksum.
    info "最新 Release Agent 暂不可用，回退到仓库中的兼容 Agent。"
    url="$fallback_url"
    checksum_url="$fallback_checksum_url"
    curl --fail --location --retry 3 --connect-timeout 15 "$url" -o "$temp_bin"
  fi
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
  if [[ "$INIT_SYSTEM" == "systemd" ]]; then
    cat > "/etc/systemd/system/$SERVICE_NAME.service" <<EOF
[Unit]
Description=Lunaris Relay Node Agent
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
    return
  fi

  cat > "$INSTALL_DIR/run-agent.sh" <<EOF
#!/bin/sh
cd "$INSTALL_DIR"
exec "$INSTALL_DIR/gost"
EOF
  chmod 700 "$INSTALL_DIR/run-agent.sh"

  cat > "/etc/init.d/$SERVICE_NAME" <<EOF
#!/sbin/openrc-run
description="Lunaris Relay Node Agent"
command="$INSTALL_DIR/run-agent.sh"
command_user="root"
supervisor="supervise-daemon"
respawn_delay=3
respawn_max=0
EOF
  chmod 755 "/etc/init.d/$SERVICE_NAME"
}

start_agent_service() {
  if [[ "$INIT_SYSTEM" == "systemd" ]]; then
    systemctl daemon-reload
    systemctl enable "$SERVICE_NAME.service" >/dev/null
    systemctl restart "$SERVICE_NAME.service"
    systemctl is-active --quiet "$SERVICE_NAME.service"
  else
    rc-update add "$SERVICE_NAME" default >/dev/null
    rc-service "$SERVICE_NAME" restart >/dev/null 2>&1 || rc-service "$SERVICE_NAME" start >/dev/null
    rc-service "$SERVICE_NAME" status >/dev/null
  fi
}

stop_agent_service() {
  if [[ "$INIT_SYSTEM" == "systemd" ]]; then
    systemctl disable --now "$SERVICE_NAME.service" 2>/dev/null || true
    rm -f "/etc/systemd/system/$SERVICE_NAME.service"
    systemctl daemon-reload
  else
    rc-service "$SERVICE_NAME" stop 2>/dev/null || true
    rc-update del "$SERVICE_NAME" default 2>/dev/null || true
    rm -f "/etc/init.d/$SERVICE_NAME"
  fi
}

write_ddns_updater() {
  cat > "$INSTALL_DIR/cloudflare-ddns.sh" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail

CONFIG_FILE="$(dirname "$0")/ddns.env"
STATE_FILE="$(dirname "$0")/ddns.state"
CLOUDFLARE_API="https://api.cloudflare.com/client/v4"

[[ -r "$CONFIG_FILE" ]] || { echo "DDNS 配置不存在" >&2; exit 1; }
# 配置文件由安装脚本以 root/600 权限生成，避免令牌被其他用户读取。
source "$CONFIG_FILE"
LAST_IPV4=""
LAST_IPV6=""
LAST_BOOT_ID=""
if [[ -r "$STATE_FILE" ]]; then
  source "$STATE_FILE"
fi

fail() { echo "[DDNS] $*" >&2; exit 1; }

json_first_id() {
  tr -d '\n' | sed -n 's/.*"result"[[:space:]]*:[[:space:]]*\[[[:space:]]*{[^}]*"id"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
}

json_number() {
  local key="$1"
  tr -d '\n' | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p"
}

json_boolean() {
  local key="$1"
  tr -d '\n' | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\\(true\\|false\\).*/\\1/p"
}

json_string() {
  local key="$1"
  tr -d '\n' | sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p"
}

json_record_count() {
  grep -oE '"id"[[:space:]]*:[[:space:]]*"[^"]+"' | wc -l | tr -d '[:space:]'
}

is_valid_ipv4() {
  local address="$1" octet
  local -a octets
  IFS='.' read -r -a octets <<< "$address"
  [[ "${#octets[@]}" -eq 4 ]] || return 1
  for octet in "${octets[@]}"; do
    [[ "$octet" =~ ^[0-9]{1,3}$ ]] || return 1
    (( 10#$octet <= 255 )) || return 1
  done
}

is_valid_ipv6() {
  local address="$1" without_colons remainder component
  local -a components
  [[ "$address" == *:* && "$address" != *[^0-9a-fA-F:]* && "$address" != *":::"* ]] || return 1
  without_colons="${address//:/}"
  if [[ "$address" == *"::"* ]]; then
    remainder="${address#*::}"
    [[ "$remainder" != *"::"* ]] || return 1
    (( ${#address} - ${#without_colons} <= 8 )) || return 1
  else
    (( ${#address} - ${#without_colons} == 7 )) || return 1
  fi
  IFS=':' read -r -a components <<< "${address//::/:}"
  for component in "${components[@]}"; do
    [[ -z "$component" || "$component" =~ ^[0-9a-fA-F]{1,4}$ ]] || return 1
  done
}

cf_request() {
  local response
  response="$(curl --fail --silent --show-error --retry 4 --retry-all-errors --retry-delay 2 \
    --connect-timeout 10 --max-time 30 \
    -H "Authorization: Bearer ${CF_API_TOKEN}" \
    -H 'Content-Type: application/json' "$@")" || return 1
  grep -Eq '"success"[[:space:]]*:[[:space:]]*true' <<< "$response" || return 1
  printf '%s' "$response"
}

find_zone_id() {
  local candidate="$CF_RECORD_NAME" response zone_id
  while [[ "$candidate" == *.* ]]; do
    response="$(cf_request --get --data-urlencode "name=${candidate}" "${CLOUDFLARE_API}/zones")" || fail "无法查询 Cloudflare Zone"
    zone_id="$(printf '%s' "$response" | json_first_id)"
    if [[ -n "$zone_id" ]]; then
      printf '%s' "$zone_id"
      return 0
    fi
    candidate="${candidate#*.}"
  done
  fail "未找到 ${CF_RECORD_NAME} 对应的 Cloudflare Zone"
}

sync_record() {
  local record_type="$1" address="$2" zone_id="$3" response record_count record_id current_address ttl proxied payload
  response="$(cf_request --get \
    --data-urlencode "type=${record_type}" \
    --data-urlencode "name=${CF_RECORD_NAME}" \
    "${CLOUDFLARE_API}/zones/${zone_id}/dns_records?per_page=100")" || fail "无法查询 ${record_type} 记录"
  record_count="$(printf '%s' "$response" | json_record_count)"
  case "$record_count" in
    0)
      ttl=1
      proxied=false
      payload="{\"type\":\"${record_type}\",\"name\":\"${CF_RECORD_NAME}\",\"content\":\"${address}\",\"ttl\":${ttl},\"proxied\":${proxied}}"
      response="$(cf_request -X POST --data "$payload" "${CLOUDFLARE_API}/zones/${zone_id}/dns_records")" || fail "创建 ${record_type} 记录失败"
      ;;
    1)
      record_id="$(printf '%s' "$response" | json_first_id)"
      current_address="$(printf '%s' "$response" | json_string content)"
      if [[ "$current_address" == "$address" ]]; then
        echo "[DDNS] ${record_type} ${CF_RECORD_NAME} 已是 ${address}，无需更新"
        return
      fi
      ttl="$(printf '%s' "$response" | json_number ttl)"
      proxied="$(printf '%s' "$response" | json_boolean proxied)"
      ttl="${ttl:-1}"
      proxied="${proxied:-false}"
      payload="{\"type\":\"${record_type}\",\"name\":\"${CF_RECORD_NAME}\",\"content\":\"${address}\",\"ttl\":${ttl},\"proxied\":${proxied}}"
      response="$(cf_request -X PATCH --data "$payload" "${CLOUDFLARE_API}/zones/${zone_id}/dns_records/${record_id}")" || fail "更新 ${record_type} 记录失败"
      ;;
    *)
      fail "${CF_RECORD_NAME} 存在 ${record_count} 条 ${record_type} 记录；节点 DDNS 只管理单条记录，已拒绝修改"
      ;;
  esac
  if ! grep -Eq '"success"[[:space:]]*:[[:space:]]*true' <<<"$response"; then
    fail "Cloudflare 未接受 ${record_type} 记录更新"
  fi
  echo "[DDNS] ${record_type} ${CF_RECORD_NAME} -> ${address}"
}

main() {
  [[ -n "${CF_API_TOKEN:-}" && -n "${CF_RECORD_NAME:-}" ]] || fail "DDNS 配置不完整"
  local zone_id ipv4 ipv6 next_ipv4 next_ipv6 current_boot_id should_sync=0 force_boot_sync=0
  current_boot_id="$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || true)"
  # The previous machine may have moved this record while this node was off.
  # Synchronize once after every boot even when this node receives its old IP.
  if [[ -n "$current_boot_id" && "$current_boot_id" != "$LAST_BOOT_ID" ]]; then
    force_boot_sync=1
    should_sync=1
  fi
  ipv4="$(curl -4 --fail --silent --show-error --retry 4 --retry-all-errors --retry-delay 2 --connect-timeout 10 --max-time 30 https://api.ipify.org 2>/dev/null || true)"
  ipv6="$(curl -6 --fail --silent --show-error --retry 4 --retry-all-errors --retry-delay 2 --connect-timeout 10 --max-time 30 https://api64.ipify.org 2>/dev/null || true)"
  next_ipv4="$LAST_IPV4"
  next_ipv6="$LAST_IPV6"

  if [[ -n "$ipv4" ]] && ! is_valid_ipv4 "$ipv4"; then
    echo "[DDNS] 忽略无效的 IPv4 检测结果" >&2
    ipv4=""
  fi
  if [[ -n "$ipv6" ]] && ! is_valid_ipv6 "$ipv6"; then
    echo "[DDNS] 忽略无效的 IPv6 检测结果" >&2
    ipv6=""
  fi
  if [[ -n "$ipv4" ]]; then
    [[ "$ipv4" != "$LAST_IPV4" ]] && should_sync=1
    next_ipv4="$ipv4"
  fi
  if [[ -n "$ipv6" ]]; then
    [[ "$ipv6" != "$LAST_IPV6" ]] && should_sync=1
    next_ipv6="$ipv6"
  fi
  if (( force_boot_sync == 1 )) && [[ -z "$ipv4" && -z "$ipv6" ]]; then
    fail "开机同步时未检测到可用公网 IP，将在下次定时任务重试"
  fi
  [[ -n "$next_ipv4" || -n "$next_ipv6" ]] || fail "未检测到可公开访问的 IPv4 或 IPv6 地址"
  if (( should_sync == 0 )); then
    echo "[DDNS] 公网地址未变化，无需请求 Cloudflare"
    return
  fi

  zone_id="$(find_zone_id)"
  if [[ -n "$ipv4" && ( "$force_boot_sync" == "1" || "$ipv4" != "$LAST_IPV4" ) ]]; then
    sync_record A "$ipv4" "$zone_id"
  fi
  if [[ -n "$ipv6" && ( "$force_boot_sync" == "1" || "$ipv6" != "$LAST_IPV6" ) ]]; then
    sync_record AAAA "$ipv6" "$zone_id"
  fi
  umask 077
  printf 'LAST_IPV4=%q\nLAST_IPV6=%q\nLAST_BOOT_ID=%q\n' "$next_ipv4" "$next_ipv6" "$current_boot_id" > "$STATE_FILE"
  chmod 600 "$STATE_FILE"
}

main "$@"
EOF
  chmod 700 "$INSTALL_DIR/cloudflare-ddns.sh"
}

configure_ddns() {
  if [[ "$DDNS_MODE" == "disabled" ]]; then
    if [[ "$INIT_SYSTEM" == "systemd" ]]; then
      systemctl disable --now "$DDNS_TIMER_NAME" 2>/dev/null || true
      rm -f "/etc/systemd/system/${DDNS_SERVICE_NAME}.service" "/etc/systemd/system/${DDNS_TIMER_NAME}"
      systemctl daemon-reload
    fi
    rm -f "$INSTALL_DIR/ddns.env" "$INSTALL_DIR/ddns.state" "$INSTALL_DIR/cloudflare-ddns.sh"
    info "已按安装命令关闭本机 Cloudflare DDNS"
    return
  fi

  # “unchanged” 表示保留现有 DDNS 设置；它是正常路径，不能让安装脚本以失败退出。
  [[ "$DDNS_MODE" == "enabled" ]] || return 0
  [[ "$INIT_SYSTEM" == "systemd" ]] || fail "Cloudflare DDNS 定时任务目前需要 systemd；可使用 --disable-ddns 安装 Agent。"
  umask 077
  # 重新执行安装命令可能代表换机或更换记录，强制首次任务立即同步。
  rm -f "$INSTALL_DIR/ddns.state"
  printf 'CF_API_TOKEN=%q\nCF_RECORD_NAME=%q\n' "$CF_API_TOKEN" "$CF_RECORD_NAME" > "$INSTALL_DIR/ddns.env"
  chmod 600 "$INSTALL_DIR/ddns.env"
  write_ddns_updater

  cat > "/etc/systemd/system/${DDNS_SERVICE_NAME}.service" <<EOF
[Unit]
Description=Lunaris Relay Cloudflare DDNS
Wants=network-online.target
After=network-online.target

[Service]
Type=oneshot
ExecStart=$INSTALL_DIR/cloudflare-ddns.sh
EOF

  cat > "/etc/systemd/system/${DDNS_TIMER_NAME}" <<EOF
[Unit]
Description=Run Lunaris Relay Cloudflare DDNS every minute

[Timer]
OnBootSec=90s
OnUnitActiveSec=1min
AccuracySec=10s
Persistent=true
Unit=${DDNS_SERVICE_NAME}.service

[Install]
WantedBy=timers.target
EOF

  systemctl daemon-reload
  systemctl enable --now "$DDNS_TIMER_NAME" >/dev/null
  systemctl start "$DDNS_SERVICE_NAME"
  ok "Cloudflare DDNS 已立即同步，并设为每 1 分钟自动更新"
}

install_agent() {
  require_root
  detect_init_system
  [[ -n "$SERVER_ADDR" ]] || fail "缺少 --panel 参数。"
  [[ -n "$NODE_SECRET" ]] || fail "缺少 --token 参数。"
  [[ "$SERVER_ADDR" != *$'\n'* && "$NODE_SECRET" != *$'\n'* ]] || fail "参数不能包含换行符。"
  if [[ "$DDNS_MODE" == "enabled" ]]; then
    [[ -n "$CF_API_TOKEN" && -n "$CF_RECORD_NAME" ]] || fail "启用 DDNS 时必须同时提供 --cf-api-token 和 --cf-record。"
    [[ "$CF_API_TOKEN" != *$'\n'* && "$CF_RECORD_NAME" != *$'\n'* ]] || fail "DDNS 参数不能包含换行符。"
  fi
  if (( TCP_AUTO_TUNE == 1 )); then
    (( TUNE_TCP == 1 )) || fail "动态 TCP 调优不能与 --skip-tcp-tuning 同时使用。"
    [[ -z "$TCP_PROFILE_OVERRIDE" ]] || fail "动态 TCP 调优请使用 --tcp-profile-min/--tcp-profile-max，不要同时传 --tcp-profile。"
    [[ -n "$TCP_PROFILE_MIN" && -n "$TCP_PROFILE_MAX" ]] || fail "动态 TCP 调优必须同时指定 --tcp-profile-min 和 --tcp-profile-max。"
  elif [[ -n "$TCP_PROFILE_MIN$TCP_PROFILE_MAX" ]]; then
    fail "指定 TCP 调优上下限时还需要 --tcp-auto-tune。"
  fi

  apply_tcp_tuning
  mkdir -p "$INSTALL_DIR"
  download_agent
  write_config
  configure_tcp_tuning_guard
  write_service

  sleep 1
  start_agent_service || {
    if [[ "$INIT_SYSTEM" == "systemd" ]]; then
      journalctl -u "$SERVICE_NAME.service" --no-pager -n 50 >&2 || true
    else
      rc-service "$SERVICE_NAME" status >&2 || true
    fi
    fail "Agent 启动失败，已保留配置以便排查。"
  }
  ok "节点 Agent 已启动并设为开机自启"
  configure_ddns
  if [[ "$INIT_SYSTEM" == "systemd" ]]; then
    printf '状态查看：systemctl status %s\n' "$SERVICE_NAME"
  else
    printf '状态查看：rc-service %s status\n' "$SERVICE_NAME"
  fi
}

uninstall_agent() {
  require_root
  detect_init_system
  read -r -p "卸载节点 Agent 并删除本机配置？(y/N): " confirm
  [[ "$confirm" =~ ^[Yy]$ ]] || { info "已取消"; return; }
  stop_agent_service
  if [[ "$INIT_SYSTEM" == "systemd" ]]; then
    systemctl disable --now "$DDNS_TIMER_NAME" 2>/dev/null || true
    systemctl disable --now "${TCP_GUARD_SERVICE_NAME}.timer" 2>/dev/null || true
    rm -f "/etc/systemd/system/${DDNS_SERVICE_NAME}.service" "/etc/systemd/system/${DDNS_TIMER_NAME}" \
      "/etc/systemd/system/${TCP_GUARD_SERVICE_NAME}.service" "/etc/systemd/system/${TCP_GUARD_SERVICE_NAME}.timer"
  else
    rc-service "$TCP_GUARD_SERVICE_NAME" stop 2>/dev/null || true
    rc-update del "$TCP_GUARD_SERVICE_NAME" default 2>/dev/null || true
    rm -f "/etc/init.d/$TCP_GUARD_SERVICE_NAME"
  fi
  rm -rf "$INSTALL_DIR"
  if [[ -f "$SYSCTL_BACKUP" ]]; then
    mv "$SYSCTL_BACKUP" "$SYSCTL_FILE"
    sysctl -p "$SYSCTL_FILE" || true
    ok "已恢复安装前的同名 TCP 配置"
  elif [[ -f "$SYSCTL_FILE" ]]; then
    rm -f "$SYSCTL_FILE"
    info "已移除 Lunaris Relay 的持久化 TCP 配置；当前内核运行参数可在重启后恢复为系统配置。"
  fi
  [[ "$INIT_SYSTEM" != "systemd" ]] || systemctl daemon-reload
  ok "节点 Agent 已卸载"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --panel|-p|--server|-a) SERVER_ADDR="${2:-}"; shift 2 ;;
    --token|-t|--secret|-s) NODE_SECRET="${2:-}"; shift 2 ;;
    --cf-api-token) CF_API_TOKEN="${2:-}"; DDNS_MODE="enabled"; shift 2 ;;
    --cf-record) CF_RECORD_NAME="${2:-}"; DDNS_MODE="enabled"; shift 2 ;;
    --disable-ddns) DDNS_MODE="disabled"; shift ;;
    --tcp-profile) TCP_PROFILE_OVERRIDE="${2:-}"; shift 2 ;;
    --tcp-profile-min) TCP_PROFILE_MIN="${2:-}"; shift 2 ;;
    --tcp-profile-max) TCP_PROFILE_MAX="${2:-}"; shift 2 ;;
    --tcp-auto-tune) TCP_AUTO_TUNE=1; shift ;;
    --skip-tcp-tuning) TUNE_TCP=0; shift ;;
    --uninstall) uninstall_agent; exit 0 ;;
    --help|-h) usage; exit 0 ;;
    *) fail "未知参数：$1" ;;
  esac
done

install_agent
