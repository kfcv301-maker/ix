#!/bin/sh
set -eu

# The official Docker CLI image is Alpine based. Install Git, Python and the
# Compose v2 plugin at startup so the updater uses the same `docker compose`
# command as the regular panel installer.
apk add --no-cache git python3 docker-cli-compose >/dev/null
exec python3 /workspace/updater/panel_updater.py
