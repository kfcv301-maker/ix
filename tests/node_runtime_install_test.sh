#!/usr/bin/env bash
# Private config file path: execute only the installer parsing/writing helpers.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source <(awk '/^while \[\[ \$# -gt 0 \]\]; do$/ {exit} {print}' "$ROOT_DIR/install.sh")
WORK="$(mktemp -d)"
trap 'rm -rf -- "$WORK"' EXIT
INSTALL_DIR="$WORK/agent"
mkdir -p "$INSTALL_DIR"
NODE_CONFIG_FILE="$WORK/private.json"
cat > "$NODE_CONFIG_FILE" <<'JSON'
{"schemaVersion":1,"revision":"test-revision","panelUrl":"https://panel.example.invalid","secret":"node-private-key","agentRelease":"1.4.15","ddns":{"enabled":true,"provider":"cloudflare","apiToken":"private-provider-key","recordName":"node.example.invalid","intervalSeconds":60},"tcpTuningProfile":"balanced","tcpTuningAutoEnabled":false,"tcpTuningProfileMin":"tiny","tcpTuningProfileMax":"standard"}
JSON
chmod 600 "$NODE_CONFIG_FILE"
printf '{"http":0,"tls":1,"socks":0,"extension":{"keep":true}}\n' > "$INSTALL_DIR/config.json"
load_node_config
[[ "$SERVER_ADDR" == https://panel.example.invalid && "$NODE_SECRET" == node-private-key && "$DDNS_MODE" == managed && "$TCP_PROFILE_OVERRIDE" == balanced ]]
write_config
python3 - "$INSTALL_DIR/config.json" <<'PY'
import json,os,sys
path=sys.argv[1];data=json.load(open(path))
assert data['secret']=='node-private-key'
assert data['runtime']['ddns']['apiToken']=='private-provider-key'
assert data['runtime']['ddns']['recordName']=='node.example.invalid'
assert data['http']==0 and data['tls']==1 and data['socks']==0
assert data['extension']=={'keep':True}
assert os.stat(path).st_mode & 0o777==0o600
assert 'secret' not in data['runtime']
PY
# Publicly readable credentials must be refused before modifying the server.
chmod 644 "$NODE_CONFIG_FILE"
if (load_node_config) >/dev/null 2>&1; then
  printf 'world-readable private configuration accepted\n' >&2; exit 1
fi
printf 'Private runtime config install checks passed\n'
