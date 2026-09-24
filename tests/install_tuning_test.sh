#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# The installer runs only after argument parsing at the end of the file.
source <(awk '/^while \[\[ \$# -gt 0 \]\]; do$/ {exit} {print}' "$ROOT_DIR/install.sh")

HOST_MEMORY_MB=973
HOST_CPU_CORES=1
TCP_AUTO_TUNE=1
TCP_PROFILE_MIN=tiny
TCP_PROFILE_MAX=standard
TCP_PROFILE_OVERRIDE=""
select_tuning_profile
[[ "$EFFECTIVE_TCP_PROFILE_MIN" == tiny && "$EFFECTIVE_TCP_PROFILE_MAX" == small ]]
[[ "$TUNING_PROFILE" == small ]]

TCP_AUTO_TUNE=0
TCP_PROFILE_OVERRIDE=standard
select_tuning_profile
[[ "$TUNING_PROFILE" == small && "$R_MEM_MAX" == 8388608 ]]

HOST_MEMORY_MB=4096
HOST_CPU_CORES=2
TCP_AUTO_TUNE=1
TCP_PROFILE_MIN=small
TCP_PROFILE_MAX=standard
TCP_PROFILE_OVERRIDE=""
select_tuning_profile
[[ "$EFFECTIVE_TCP_PROFILE_MIN" == small && "$EFFECTIVE_TCP_PROFILE_MAX" == standard ]]

INSTALL_DIR="$(mktemp -d)"
trap 'rm -rf "$INSTALL_DIR"' EXIT
write_tcp_tuning_guard
bash -n "$INSTALL_DIR/tcp-tuning-guard.sh"
printf '{"http":0,"tls":0,"socks":0}\n' > "$INSTALL_DIR/config.json"
SERVER_ADDR='https://panel.example.com'
NODE_SECRET='test-only-token'
write_config
grep -q '"http": 0' "$INSTALL_DIR/config.json"
grep -q '"tls": 0' "$INSTALL_DIR/config.json"
grep -q '"socks": 0' "$INSTALL_DIR/config.json"
printf 'TCP tuning profile and generated guard checks passed\n'
