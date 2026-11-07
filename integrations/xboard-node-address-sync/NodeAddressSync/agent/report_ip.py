#!/usr/bin/env python3
"""Report the current node IPv4 to Xboard. Python standard library only."""

import argparse
import ipaddress
import json
import os
import signal
import sys
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

DEFAULT_INTERVAL = 15

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Redirect refused; configure the final HTTPS panel URL")


def request(url, method="GET", headers=None, body=None, timeout=8):
    # Do not let proxy variables reroute IMDS or reveal reporting credentials.
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request_headers = {"User-Agent": "Xboard-Node-Address-Sync/1.1.1"}
    request_headers.update(headers or {})
    req = Request(url, data=body, headers=request_headers, method=method)
    with opener.open(req, timeout=timeout) as response:
        return response.read(16385)


def public_ipv4(value):
    ip = ipaddress.ip_address(value.strip())
    if ip.version != 4 or not ip.is_global or ip.is_multicast:
        raise ValueError("No valid public IPv4; panel address was not changed")
    return str(ip)


def discover_aws(expected_instance_id=""):
    base = "http://169.254.169.254/latest/"
    token = request(base + "api/token", "PUT", {"X-aws-ec2-metadata-token-ttl-seconds": "60"}).decode().strip()
    if not token:
        raise ValueError("IMDSv2 returned an empty token")
    headers = {"X-aws-ec2-metadata-token": token}
    instance = request(base + "meta-data/instance-id", headers=headers).decode().strip()
    if expected_instance_id and instance != expected_instance_id:
        raise ValueError("Instance ID does not match the configured binding")
    ip = public_ipv4(request(base + "meta-data/public-ipv4", headers=headers).decode())
    return ip, instance


def read_json(path, default=None):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        if default is not None:
            return default
        raise


def write_state(path, state):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = target.with_name(target.name + ".tmp")
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        json.dump(state, output)
    os.replace(temporary, target)


def validate_config(config):
    parsed = urlparse(config["report_url"])
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("report_url must be a final HTTPS URL without credentials or query parameters")
    uuid.UUID(config["binding_id"])
    token = config["token"]
    if len(token) != 64 or any(char not in "0123456789abcdef" for char in token):
        raise ValueError("Invalid reporting token")
    if config.get("mode", "aws-imds") not in ("aws-imds", "manual"):
        raise ValueError("Unsupported discovery mode")
    if not 15 <= int(config.get("interval", DEFAULT_INTERVAL)) <= 3600:
        raise ValueError("interval must be between 15 and 3600 seconds")
    if not 1 <= int(config.get("stable_samples", 2)) <= 10:
        raise ValueError("stable_samples must be between 1 and 10")


def sync_once(config, explicit_ip=None, instance_id=None):
    validate_config(config)
    state_file = config.get("state_file", "/var/lib/xboard-node-address-sync/state.json")
    state = read_json(state_file, {})
    # Changing the binding or rotating its token resets observations from the old config.
    import hashlib
    fingerprint = hashlib.sha256((config["report_url"] + config["binding_id"] + config["token"]).encode()).hexdigest()
    if state.get("config_fingerprint") != fingerprint:
        state = {"config_fingerprint": fingerprint}
    expected = config.get("expected_instance_id", "")
    try:
        if explicit_ip is not None:
            ip, instance = public_ipv4(explicit_ip), instance_id or expected or None
        elif config.get("mode", "aws-imds") == "aws-imds":
            ip, instance = discover_aws(expected)
        else:
            raise ValueError("Manual mode requires --ip supplied by the existing IP replacement system")
        if expected and instance != expected:
            raise ValueError("Instance ID does not match the configured binding")
    except Exception:
        # A discovery failure interrupts consecutive observations.
        state.pop("candidate_ip", None)
        state["candidate_count"] = 0
        write_state(state_file, state)
        raise

    state["candidate_count"] = state.get("candidate_count", 0) + 1 if state.get("candidate_ip") == ip else 1
    state["candidate_ip"] = ip
    write_state(state_file, state)
    if state["candidate_count"] < int(config.get("stable_samples", 2)):
        return {"status": "observing", "updated": 0, "ip": ip}
    body = {"binding_id": config["binding_id"], "ip": ip, "observed_at": int(time.time())}
    if instance:
        body["instance_id"] = instance
    # Heartbeats also resynchronize nodes newly selected in the panel.
    result = json.loads(request(config["report_url"], "POST", {
        "Authorization": "Bearer " + config["token"],
        "Content-Type": "application/json", "Accept": "application/json",
    }, json.dumps(body, separators=(",", ":")).encode()))
    data = result.get("data", {})
    if data.get("status") not in ("updated", "unchanged", "duplicate") or data.get("ip") != ip:
        raise ValueError("Panel response did not acknowledge this IP")
    state["last_reported_ip"] = ip
    state["last_success_at"] = int(time.time())
    write_state(state_file, state)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True)
    parser.add_argument("--daemon", action="store_true")
    parser.add_argument("--ip", help="Explicit public IPv4 from the existing replacement system")
    parser.add_argument("--instance-id")
    args = parser.parse_args()
    running = True

    def stop(_signum, _frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    while running:
        started = time.monotonic()
        interval = DEFAULT_INTERVAL
        try:
            config = read_json(args.config)
            interval = int(config.get("interval", DEFAULT_INTERVAL))
            # Serialize daemon, cron, and explicit callback invocations on Linux.
            state_path = Path(config.get("state_file", "/var/lib/xboard-node-address-sync/state.json"))
            state_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            import fcntl
            with open(str(state_path) + ".lock", "a") as lock:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
                result = sync_once(config, args.ip, args.instance_id)
            if result["status"] in ("updated", "observing"):
                print(f"{result['status']}: {result['updated']} node address(es)", flush=True)
        except HTTPError as error:
            # Do not print response bodies, request headers, credentials, or full URLs.
            print(f"Panel/metadata HTTP {error.code}; will retry, panel addresses preserved", file=sys.stderr, flush=True)
            if not args.daemon:
                return 1
        except Exception as error:
            print(f"Sync failed ({type(error).__name__}); will retry, panel addresses preserved", file=sys.stderr, flush=True)
            if not args.daemon:
                return 1
        if not args.daemon:
            return 0
        deadline = started + max(15, min(interval, 3600))
        while running and time.monotonic() < deadline:
            time.sleep(min(1, max(0, deadline - time.monotonic())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
