#!/bin/sh
set -eu

# docker/compose is Alpine based and already includes Docker Compose. Git and
# Python are installed at container start so this image stays small and the
# updater script itself remains versioned with the panel source.
apk add --no-cache git python3 >/dev/null
exec python3 /workspace/updater/panel_updater.py
