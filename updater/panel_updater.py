#!/usr/bin/env python3
"""Internal, authenticated updater for Lunaris Relay.

It is intentionally not exposed on a host port.  The Spring backend is the
only caller and sends a random token from the panel's local .env.  Before any
Git reset or container rebuild, the MySQL database is dumped into an ignored
local backup directory. Named volumes are retained; missing deployment settings
are migrated atomically without replacing database or account credentials.
"""

from __future__ import annotations

import hmac
import json
import os
import re
import subprocess
import threading
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from env_migration import migrate_env


WORKSPACE = Path(os.environ.get("INSTALL_DIR", "/workspace")).resolve()
PROJECT_NAME = os.environ.get("PROJECT_NAME", "flux-panel-enhanced")
REPO_URL = os.environ.get("REPO_URL", "https://github.com/kfcv301-maker/ix.git")
RELEASE_REF = os.environ.get("BRANCH", "1.4.5")
TOKEN = os.environ.get("PANEL_UPDATER_TOKEN", "")
# The updater runs inside this Compose project. Never include it in an
# update-triggered `compose up`: Docker may recreate the updater container
# while this request is still running, which kills the update worker before it
# gets a chance to start the remaining services.
PANEL_SERVICES = ("mysql", "backend", "frontend")
STATE_LOCK = threading.RLock()
STATE: dict[str, Any] = {
    "state": "idle",
    "message": "等待更新请求",
    "currentRevision": None,
    "targetRevision": None,
    "backup": None,
    "updatedAt": None,
}


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def set_state(**values: Any) -> None:
    with STATE_LOCK:
        STATE.update(values)
        STATE["updatedAt"] = now()


def current_state() -> dict[str, Any]:
    with STATE_LOCK:
        snapshot = dict(STATE)
    # An administrator may deploy a reviewed commit on the host without using
    # this long-running worker. Its last task state must not be presented as
    # the currently installed version.
    if snapshot["state"] not in {"queued", "backing_up", "checking", "updating"}:
        installed = git_revision("HEAD")
        if installed:
            snapshot["currentRevision"] = installed
            if snapshot["state"] == "completed":
                snapshot["targetRevision"] = installed
    return snapshot


def run(command: list[str], *, stdout: Any | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=WORKSPACE,
        stdin=subprocess.DEVNULL,
        stdout=stdout if stdout is not None else subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
        timeout=900,
    )


def git_revision(reference: str) -> str | None:
    result = run(["git", "rev-parse", "--verify", reference])
    return result.stdout.strip() if result.returncode == 0 else None


def fetch_release() -> str:
    if not re.fullmatch(r"\d+(?:\.\d+){1,3}", RELEASE_REF):
        raise RuntimeError("更新版本必须是经过审核的发布标签")
    result = run(["git", "fetch", "--depth=1", "origin", f"refs/tags/{RELEASE_REF}:refs/tags/{RELEASE_REF}"])
    if result.returncode != 0:
        raise RuntimeError("无法获取指定发布版本，现有服务与数据未改动")
    target = git_revision(f"refs/tags/{RELEASE_REF}")
    if target is None:
        raise RuntimeError("指定发布版本不存在，现有服务与数据未改动")
    return target


def ensure_clean_workspace() -> None:
    dirty = run(["git", "status", "--porcelain", "--untracked-files=no"])
    if dirty.returncode != 0 or dirty.stdout.strip():
        raise RuntimeError("安装目录存在未提交的代码修改，已取消更新以保护本地修复")


def compose_command(*args: str) -> list[str]:
    return [
        "docker", "compose", "--project-name", PROJECT_NAME,
        "--env-file", str(WORKSPACE / ".env"),
        "-f", str(WORKSPACE / "docker-compose.yml"),
        *args,
    ]


def create_backup() -> Path:
    backup_dir = WORKSPACE / ".upgrade-backups"
    backup_dir.mkdir(mode=0o700, exist_ok=True)
    backup_path = backup_dir / f"mysql-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.sql"
    with backup_path.open("w", encoding="utf-8") as handle:
        result = run(
            compose_command(
                "exec", "-T", "mysql", "sh", "-c",
                'exec mysqldump --no-tablespaces -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"',
            ),
            stdout=handle,
        )
    if result.returncode != 0 or backup_path.stat().st_size == 0:
        backup_path.unlink(missing_ok=True)
        raise RuntimeError("数据库备份失败，已取消更新，现有服务与数据未改动")
    backup_path.chmod(0o600)
    return backup_path


def update_worker() -> None:
    try:
        if not (WORKSPACE / ".git").is_dir() or not (WORKSPACE / ".env").is_file():
            raise RuntimeError("安装目录不完整，无法安全更新")

        # Avoid an unnecessary database dump when an update cannot proceed.
        ensure_clean_workspace()

        set_state(state="backing_up", message="正在备份数据库", backup=None)
        backup_path = create_backup()
        set_state(state="checking", message="正在检查远程版本", backup=str(backup_path.relative_to(WORKSPACE)))

        before = git_revision("HEAD")
        target = fetch_release()
        set_state(currentRevision=before, targetRevision=target)

        if before == target:
            set_state(state="completed", message="已是当前配置的发布版本；数据库备份已完成")
            return

        # A hard reset would discard fixes made directly in the installed checkout.
        # Refuse the update until those changes are reviewed and committed.
        ensure_clean_workspace()

        set_state(state="updating", message="正在下载新版本并重建服务")
        reset = run(["git", "reset", "--hard", target])
        if reset.returncode != 0:
            raise RuntimeError("切换新版本失败，现有容器仍在运行")

        migrate_env(WORKSPACE / ".env", RELEASE_REF)

        # Only rebuild and start the panel services. `updater` intentionally
        # stays running so this worker survives the deployment it initiated.
        # It will be refreshed by the next host-side deployment if its own
        # image or startup contract changes.
        deploy = run(
            compose_command(
                "up", "-d", "--build", "--remove-orphans", "--wait", "--wait-timeout", "240", *PANEL_SERVICES,
            )
        )
        if deploy.returncode != 0:
            raise RuntimeError("新版本构建或启动失败；可使用本次数据库备份回滚")

        set_state(state="completed", message="更新完成；节点、转发、设置和数据库卷均已保留")
    except Exception as error:  # User-facing state deliberately excludes command output and credentials.
        set_state(state="failed", message=str(error))


class UpdateHandler(BaseHTTPRequestHandler):
    server_version = "LunarisUpdater/1.0"

    def log_message(self, _format: str, *_args: Any) -> None:
        # Do not emit Authorization headers or any request body to container logs.
        return

    def write_json(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status.value)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def authorized(self) -> bool:
        incoming = self.headers.get("Authorization", "")
        expected = f"Bearer {TOKEN}"
        return bool(TOKEN) and hmac.compare_digest(incoming, expected)

    def do_GET(self) -> None:  # noqa: N802
        if not self.authorized():
            self.write_json(HTTPStatus.UNAUTHORIZED, {"message": "unauthorized"})
            return
        if self.path not in ("/status", "/health"):
            self.write_json(HTTPStatus.NOT_FOUND, {"message": "not found"})
            return
        self.write_json(HTTPStatus.OK, current_state())

    def do_POST(self) -> None:  # noqa: N802
        if not self.authorized():
            self.write_json(HTTPStatus.UNAUTHORIZED, {"message": "unauthorized"})
            return
        if self.path != "/update":
            self.write_json(HTTPStatus.NOT_FOUND, {"message": "not found"})
            return
        with STATE_LOCK:
            if STATE["state"] in {"queued", "backing_up", "checking", "updating"}:
                self.write_json(HTTPStatus.CONFLICT, current_state())
                return
            STATE.update({"state": "queued", "message": "更新请求已接收", "updatedAt": now()})
        threading.Thread(target=update_worker, daemon=True, name="panel-update").start()
        self.write_json(HTTPStatus.ACCEPTED, current_state())


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 18080), UpdateHandler).serve_forever()
