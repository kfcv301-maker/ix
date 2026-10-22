#!/usr/bin/env bash
# Stable node bootstrap: only the panel origin, node id and revocable node key
# belong in the installation command. All mutable settings come from the panel.
set +x
set -Eeuo pipefail
umask 077
PANEL="" NODE_ID="" INSTALL_KEY=""
fail() { printf '[FAIL] %s\n' "$*" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --panel) PANEL="${2:-}"; shift 2 ;;
    --node-id) NODE_ID="${2:-}"; shift 2 ;;
    --install-key) INSTALL_KEY="${2:-}"; shift 2 ;;
    *) fail "未知安装参数" ;;
  esac
done
[[ "$EUID" -eq 0 ]] || fail "请以 root 执行"
[[ "$NODE_ID" =~ ^[1-9][0-9]*$ && "$INSTALL_KEY" =~ ^[A-Za-z0-9_-]{43}$ ]] || fail "无效节点安装身份"
[[ "$PANEL" == http://* || "$PANEL" == https://* ]] || fail "无效面板地址；请使用完整 http(s) origin"
[[ "$PANEL" != *$'\n'* && "$PANEL" != *$'\r'* && "$PANEL" != *'"'* ]] || fail "无效面板地址"
PANEL="${PANEL%/}"
command -v curl >/dev/null || fail "请先安装 curl"
if ! command -v python3 >/dev/null; then
  if command -v apt-get >/dev/null; then
    apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3
  elif command -v apk >/dev/null; then
    apk add --no-cache python3
  else
    fail "此安装入口需要 python3，请先安装"
  fi
fi
python3 - "$PANEL" <<'PY' || fail "无效面板地址"
import sys
from urllib.parse import urlsplit
try:
    u=urlsplit(sys.argv[1])
    if u.scheme not in ('http','https') or not u.hostname or u.username or u.password or u.query or u.fragment or u.path or any(c.isspace() for c in sys.argv[1]):raise ValueError()
    if u.port is not None and not 1<=u.port<=65535:raise ValueError()
except Exception:sys.exit(1)
PY
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
# A private curl config keeps the long-lived key out of curl's process arguments.
printf 'header = "Authorization: Bearer %s"\n' "$INSTALL_KEY" > "$WORK/request.conf"
unset INSTALL_KEY
curl --fail --silent --show-error --connect-timeout 15 --max-time 60 --retry 3 \
  --config "$WORK/request.conf" "$PANEL/api/v1/node/install-config/$NODE_ID" -o "$WORK/config.json" \
  || fail "无法获取节点配置；检查面板地址、节点或安装密钥"
rm -f "$WORK/request.conf"
RELEASE="$(python3 - "$WORK/config.json" "$PANEL" "$NODE_ID" <<'PY'
import json,re,sys
from urllib.parse import urlsplit
path,panel,node=sys.argv[1:]
try:
    if len(open(path,'rb').read(65537))>65536: raise ValueError()
    data=json.load(open(path))
    parsed=urlsplit(panel)
    if not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path: raise ValueError()
    if data.get('schemaVersion')!=1 or data.get('nodeId')!=int(node): raise ValueError()
    release=data.get('agentRelease','')
    if not re.fullmatch(r'\d+(?:\.\d+){1,3}',release) or tuple(map(int,release.split('.'))) < (1,4,15): raise ValueError()
    if not isinstance(data.get('secret'),str) or not data['secret']: raise ValueError()
    if not isinstance(data.get('ddns'),dict) or not isinstance(data['ddns'].get('enabled'),bool): raise ValueError()
    data['panelUrl']=panel
    with open(path,'w') as file: json.dump(data,file)
    print(release)
except Exception:
    sys.exit('Invalid or unsupported private node configuration')
PY
)" || fail "面板返回了无效配置，安装已停止"
BASE="https://github.com/kfcv301-maker/ix/releases/download/$RELEASE"
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --retry 3 --connect-timeout 15 --max-time 180 "$BASE/install.sh" -o "$WORK/install.sh"
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --retry 3 --connect-timeout 15 --max-time 60 "$BASE/install.sh.sha256" -o "$WORK/install.sh.sha256"
EXPECTED="$(awk 'NR==1 {print $1}' "$WORK/install.sh.sha256")"
[[ "$EXPECTED" =~ ^[a-fA-F0-9]{64}$ && "${EXPECTED,,}" == "$(sha256sum "$WORK/install.sh" | awk '{print $1}')" ]] || fail "安装脚本校验失败"
AGENT_RELEASE="$RELEASE" AGENT_RELEASE_BASE="$BASE" bash "$WORK/install.sh" --config-file "$WORK/config.json"
