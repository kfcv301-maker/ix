#!/usr/bin/env bash
# Run against an isolated MySQL container; never use a deployment's data volume.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${BACKEND_TEST_IMAGE:-lunaris-backend-check}"
MYSQL_TEST_IMAGE="${MYSQL_TEST_IMAGE:-mysql:5.7}"
TEST_ID="lunaris-check-${RANDOM}-${RANDOM}"
NETWORK="$TEST_ID-network"
MYSQL_CONTAINER="$TEST_ID-mysql"
BACKEND_CONTAINER="$TEST_ID-backend"
CONFIG_VOLUME="$TEST_ID-config"
cleanup() {
  docker rm -fv "$BACKEND_CONTAINER" "$MYSQL_CONTAINER" >/dev/null 2>&1 || true
  docker volume rm "$CONFIG_VOLUME" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network create "$NETWORK" >/dev/null
docker volume create "$CONFIG_VOLUME" >/dev/null
docker run -d --name "$MYSQL_CONTAINER" --network "$NETWORK" --network-alias mysql \
  -e MYSQL_ROOT_PASSWORD=integration-only-root -e MYSQL_DATABASE=flux_panel \
  -e MYSQL_USER=flux_panel -e MYSQL_PASSWORD=integration-only-password "$MYSQL_TEST_IMAGE" >/dev/null
sql() { docker exec -i "$MYSQL_CONTAINER" mysql -uflux_panel -pintegration-only-password -N flux_panel "$@"; }
ready=false
for _ in $(seq 1 90); do
  if sql -e 'SELECT 1' >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[[ "$ready" == true ]] || { docker logs "$MYSQL_CONTAINER"; exit 1; }
sql < "$ROOT_DIR/gost.sql"
# Simulate an old installation: public default admin, duplicate grant IDs,
# and no PANEL_INITIAL_ADMIN_* variables supplied by its old updater.
sql <<'SQL'
ALTER TABLE user_tunnel DROP INDEX uk_user_tunnel_user_tunnel;
INSERT INTO user (id,user,pwd,token_version,role_id,exp_time,flow,in_flow,out_flow,flow_reset_time,num,created_time,updated_time,status)
VALUES (1,'admin_user','3c85cdebade1c51cf64ca9f3c09d182d',0,0,4102444800000,99999,0,0,1,99999,1,1,1);
INSERT INTO user_tunnel (id,user_id,tunnel_id,num,flow,in_flow,out_flow,flow_reset_time,exp_time,status)
VALUES (8,3,42,1,10,100,200,1,4102444800000,1), (9,3,42,2,20,300,400,1,4102444800000,1);
SQL
docker run -d --name "$BACKEND_CONTAINER" --network "$NETWORK" \
  -v "$CONFIG_VOLUME:/app/config" -e DB_HOST=mysql -e DB_NAME=flux_panel \
  -e DB_USER=flux_panel -e DB_PASSWORD=integration-only-password \
  -e JWT_SECRET=integration-only-64-character-jwt-secret-0123456789012345678901234567 \
  "$IMAGE" >/dev/null
ready=false
for _ in $(seq 1 120); do
  if docker exec "$BACKEND_CONTAINER" curl -fsS http://localhost:6365/flow/test >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[[ "$ready" == true ]] || { docker logs "$BACKEND_CONTAINER"; exit 1; }
[[ "$(sql -e 'SELECT CONCAT(id, ":", in_flow, ":", out_flow, ":", flow) FROM user_tunnel WHERE user_id=3 AND tunnel_id=42')" == '9:400:600:20' ]]
[[ "$(sql -e 'SELECT canonical_id FROM user_tunnel_alias WHERE alias_id=8 AND user_id=3 AND tunnel_id=42')" == '9' ]]
[[ "$(sql -e 'SELECT COUNT(*) FROM user_tunnel_duplicate_archive WHERE id IN (8,9)')" == '2' ]]
[[ "$(sql -e 'SELECT user FROM user WHERE id=1')" == 'admin' ]]
[[ "$(sql -e 'SELECT pwd LIKE "$2%" FROM user WHERE id=1')" == '1' ]]
docker exec "$BACKEND_CONTAINER" sh -c 'test "$(stat -c %a /app/config/initial-admin-credentials)" = 600'
# Both idempotence and unchanged credential persistence survive a restart.
digest="$(docker exec "$BACKEND_CONTAINER" sha256sum /app/config/initial-admin-credentials)"
docker restart "$BACKEND_CONTAINER" >/dev/null
ready=false
for _ in $(seq 1 90); do
  if docker exec "$BACKEND_CONTAINER" curl -fsS http://localhost:6365/flow/test >/dev/null 2>&1; then ready=true; break; fi
  sleep 2
done
[[ "$ready" == true ]]
[[ "$(docker exec "$BACKEND_CONTAINER" sha256sum /app/config/initial-admin-credentials)" == "$digest" ]]
[[ "$(sql -e 'SELECT in_flow+out_flow FROM user_tunnel WHERE id=9')" == '1000' ]]
printf 'Backend bootstrap, duplicate migration and restart checks passed with %s\n' "$MYSQL_TEST_IMAGE"
