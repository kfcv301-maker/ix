#!/usr/bin/env python3
"""Reserve forwarding ports without restarting, closing or reconfiguring GOST.

System reservations are additive: never remove another application's reservation.
The panel supplies paused/pending claims too; local configuration/cache is used
when the panel is older or unavailable. No provider credentials are persisted.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

MAX_BYTES = 8 * 1024 * 1024
MIN_EPHEMERAL_FREE = 4096


def read_json(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Untrusted configuration permissions')
        data = stream.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            raise ValueError('Configuration too large')
        return json.loads(data)


def atomic(path, data, mode=0o600):
    if path.is_symlink():
        raise ValueError('Symlink refused')
    fd, name = tempfile.mkstemp(prefix='.port-guard-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'w') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def port_list(value):
    if not isinstance(value, list) or len(value) > 65535:
        raise ValueError('Invalid port list')
    if any(type(p) is not int or not 1 <= p <= 65535 for p in value):
        raise ValueError('Invalid port')
    return set(value)


def parse_ranges(value):
    ports = set()
    for entry in value.strip().split(','):
        if not entry:
            continue
        if not re.fullmatch(r'\d+(?:-\d+)?', entry):
            raise ValueError('Invalid reserved range')
        values = list(map(int, entry.split('-')))
        lo, hi = values[0], values[-1]
        if not 1 <= lo <= hi <= 65535:
            raise ValueError('Invalid reserved range')
        ports.update(range(lo, hi + 1))
    return ports


def format_ranges(ports):
    groups = []
    for p in sorted(ports):
        if groups and groups[-1][1] + 1 == p:
            groups[-1][1] = p
        else:
            groups.append([p, p])
    return ','.join(str(lo) if lo == hi else f'{lo}-{hi}' for lo, hi in groups)


def merge_reservations(current, wanted, low, high):
    merged = current | wanted
    remaining = sum(p not in merged for p in range(low, high + 1))
    # Do not refuse an idempotent run on a host with a pre-existing small pool.
    if merged != current and remaining < min(MIN_EPHEMERAL_FREE, (high - low + 1) // 2):
        raise ValueError('Insufficient outbound port capacity')
    return merged


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def fetch_ports(identity):
    origin, secret = identity.get('addr', identity.get('panelUrl')), identity.get('secret')
    if not isinstance(origin, str) or not isinstance(secret, str) or not secret or any(c in secret for c in '\r\n\x00'):
        raise ValueError('Invalid node identity')
    u = urllib.parse.urlsplit(origin)
    if u.scheme not in ('http', 'https') or not u.hostname or u.username or u.password or u.query or u.fragment or u.path not in ('', '/'):
        raise ValueError('Invalid panel origin')
    request = urllib.request.Request(origin.rstrip('/') + '/api/v1/node/port-reservations',
        headers={'Authorization': 'Bearer ' + secret, 'Accept': 'application/json'})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=8) as response:
        raw = response.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise ValueError('Response too large')
        data = json.loads(raw)
    if data.get('schemaVersion') != 1:
        raise ValueError('Unsupported port schema')
    return port_list(data.get('reservedPorts'))


def local_ports(document):
    ports = set()
    for service in document.get('services', []):
        # Paused listeners need protection just as much as active listeners.
        address = service.get('addr', '')
        if not isinstance(address, str):
            raise ValueError('Invalid listener address')
        suffix = address.rsplit(':', 1)[-1]
        if suffix.isdecimal() and 1 <= int(suffix) <= 65535:
            ports.add(int(suffix))
    return ports


def sync(root, online=True, bootstrap=None, proc=Path('/proc/sys/net/ipv4')):
    root = Path(root)
    lockfd = os.open(root / 'port-guard.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(lockfd, 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        wanted = set()
        cache = root / 'port-guard-cache.json'
        for path, extract in ((cache, lambda d: port_list(d['reservedPorts'])),
                              (root / 'gost.json', local_ports)):
            if path.exists():
                wanted |= extract(read_json(path))
        panel_synced = False
        if bootstrap:
            wanted |= port_list(read_json(bootstrap).get('reservedPorts', []))
        if online and (root / 'config.json').exists():
            try:
                wanted |= fetch_ports(read_json(root / 'config.json'))
                panel_synced = True
            except Exception:
                # Never print request exceptions: they may contain auth material.
                # Keep protecting saved/local ports on old or unavailable panels.
                pass
        current = parse_ranges((proc / 'ip_local_reserved_ports').read_text())
        low, high = map(int, (proc / 'ip_local_port_range').read_text().split())
        if not 1 <= low <= high <= 65535:
            raise ValueError('Invalid ephemeral range')
        merged = merge_reservations(current, wanted, low, high)
        # Persist desired ports before changing the kernel, so boot preflight can
        # recover after power loss. Preserve every prior system reservation too.
        atomic(cache, json.dumps({'schemaVersion': 1, 'reservedPorts': sorted(merged)}))
        if merged != current:
            (proc / 'ip_local_reserved_ports').write_text(format_ranges(merged) + '\n')
        actual = parse_ranges((proc / 'ip_local_reserved_ports').read_text())
        if not merged <= actual:
            raise ValueError('Kernel reservation verification failed')
        result = {'protectedPorts': len(wanted), 'systemReservedPorts': len(actual),
                  'panelSynced': panel_synced, 'ephemeralRange': [low, high],
                  'updatedAt': int(time.time()), 'agentRestarted': False}
        atomic(root / 'port-guard-status.json', json.dumps(result))
        return result


def install(root):
    root = Path(root).resolve()
    if not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(root)):
        raise ValueError('Unsupported installation directory')
    destination = root / 'port-reservation-guard.py'
    atomic(destination, Path(__file__).read_text(), 0o700)
    if Path('/run/systemd/system').is_dir():
        units = Path('/etc/systemd/system')
        atomic(units / 'flux-panel-port-guard.service', '[Unit]\nDescription=Reserve forwarding ports\nAfter=network-online.target\n\n[Service]\nType=oneshot\nUMask=0077\nExecStart=/usr/bin/python3 ' + str(destination) + ' --root ' + str(root) + ' --sync\nTimeoutStartSec=30\n', 0o644)
        atomic(units / 'flux-panel-port-guard.timer', '[Unit]\nDescription=Refresh forwarding port reservations\n\n[Timer]\nOnBootSec=10s\nOnUnitInactiveSec=10s\nAccuracySec=1s\nUnit=flux-panel-port-guard.service\n\n[Install]\nWantedBy=timers.target\n', 0o644)
        dropin = units / 'flux-panel-agent.service.d'
        dropin.mkdir(exist_ok=True)
        # Adding a drop-in and reloading systemd never restarts the running Agent.
        atomic(dropin / '20-port-reservation.conf', '[Service]\nExecStartPre=-/usr/bin/python3 ' + str(destination) + ' --root ' + str(root) + ' --local-sync\n', 0o644)
        subprocess.run(['systemctl', 'daemon-reload'], check=True, capture_output=True)
        subprocess.run(['systemctl', 'enable', '--now', 'flux-panel-port-guard.timer'], check=True, capture_output=True)
    elif Path('/sbin/openrc-run').exists():
        unit = Path('/etc/init.d/flux-panel-port-guard')
        atomic(unit, '#!/sbin/openrc-run\ncommand="/usr/bin/python3"\ncommand_args="' + str(destination) + ' --root ' + str(root) + ' --watch"\nsupervisor="supervise-daemon"\nrespawn_delay=5\ndepend() { after net; before flux-panel-agent; }\n', 0o755)
        subprocess.run(['rc-update', 'add', 'flux-panel-port-guard', 'default'], check=True, capture_output=True)
        subprocess.run(['rc-service', 'flux-panel-port-guard', 'start'], check=True, capture_output=True)
    else:
        raise ValueError('Unsupported init system')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', default='/etc/flux-panel-agent')
    parser.add_argument('--bootstrap-config')
    mode = parser.add_mutually_exclusive_group(required=True)
    for arg in ('install', 'sync', 'local-sync', 'watch'):
        mode.add_argument('--' + arg, action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('Root required')
    root = Path(args.root)
    if root.is_symlink():
        raise ValueError('Symlink installation directory refused')
    root.mkdir(parents=True, exist_ok=True)
    if args.watch:
        while True:
            try:
                sync(root)
            except Exception:
                print('Port reservation refresh failed; existing reservations retained', file=sys.stderr, flush=True)
            time.sleep(10)
    else:
        result = sync(root, not args.local_sync, args.bootstrap_config)
        if args.install:
            install(root)
        print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Port reservation guard failed: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
