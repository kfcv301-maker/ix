"""Idempotent deployment settings shared by the host installers and updater."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import secrets
import tempfile


def migrate_env(path: Path, release: str) -> None:
    if not re.fullmatch(r"\d+(?:\.\d+){1,3}", release):
        raise ValueError("Invalid release tag")
    original = path.read_text(encoding="utf-8")
    lines = original.splitlines()
    values = {}
    for line in lines:
        match = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line)
        if match:
            values[match[1]] = match[2]
    # An unspecified image belongs to the old 5.7 deployment contract.
    additions = {
        "MYSQL_IMAGE": values.get("MYSQL_IMAGE") or "mysql:5.7",
        "VPS_CREDENTIAL_KEY": values.get("VPS_CREDENTIAL_KEY") or secrets.token_hex(24),
        "PANEL_UPDATER_TOKEN": values.get("PANEL_UPDATER_TOKEN") or secrets.token_hex(24),
        "PANEL_RELEASE_REF": release,
        "VPS_BACKEND_INSTALL_RELEASE": release,
        "AGENT_INSTALL_RELEASE": release,
    }
    # Do not invent a different initial password for an already upgraded DB.
    # With no initial-admin variables Java creates/reuses the private volume file.
    rewritten = []
    seen = set()
    for line in lines:
        match = re.match(r"^([A-Z][A-Z0-9_]*)=", line)
        key = match[1] if match else None
        if key in additions:
            if key not in seen:
                rewritten.append(f"{key}={additions[key]}")
                seen.add(key)
        else:
            rewritten.append(line)
    rewritten.extend(f"{key}={value}" for key, value in additions.items() if key not in seen)
    data = "\n".join(rewritten) + "\n"
    if data == original:
        path.chmod(0o600)
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("env_file", type=Path)
    parser.add_argument("release")
    args = parser.parse_args()
    migrate_env(args.env_file, args.release)
