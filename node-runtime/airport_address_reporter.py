#!/usr/bin/env python3
"""Optional AWS IPv4 observations for Lunaris; never holds airport credentials."""
import argparse
import fcntl
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener
import uuid

MAX_BYTES = 16384
API_PATH = '/api/v1/node/address-observation'
IMDS = 'http://169.254.169.254/latest/'
UNIT_NAME = 'flux-panel-airport-address'
UNIT_DIR = Path('/etc/systemd/system')
RUNTIME_DIR = Path('/run/flux-panel-airport-address')
SYSTEMD_ACTIVE = Path('/run/systemd/system')
BOOT_ID = Path('/proc/sys/kernel/random/boot_id')


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Redirect refused')


def request(url, method='GET', headers=None, body=None, timeout=3):
    # In particular, never send the node secret or IMDS token through a proxy.
    opener = build_opener(ProxyHandler({}), NoRedirect())
    merged = {'User-Agent': 'Lunaris-Airport-Address/1', 'Accept': 'application/json'}
    merged.update(headers or {})
    req = Request(url, data=body, headers=merged, method=method)
    with opener.open(req, timeout=timeout) as response:
        raw = response.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError('Response too large')
    return raw


def require_root():
    if not hasattr(os, 'geteuid') or os.geteuid() != 0:
        raise PermissionError('Root required')


def private_json(path):
    path = Path(path)
    if path.is_symlink():
        raise ValueError('Symlink refused')
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        expected_uid = os.geteuid() if hasattr(os, 'geteuid') else None
        if (not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077
                or (expected_uid is not None and info.st_uid != expected_uid)):
            raise PermissionError('Private configuration required')
        raw = stream.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError('Configuration too large')
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError('Invalid configuration')
    return value


def read_identity(root):
    root = Path(root)
    if root.is_symlink() or not root.is_absolute():
        raise ValueError('Invalid installation root')
    cfg = private_json(root / 'config.json')
    panel = cfg.get('addr') or cfg.get('panelUrl')
    secret = cfg.get('secret')
    if not isinstance(panel, str) or not isinstance(secret, str):
        raise ValueError('Node identity unavailable')
    if (not secret or len(secret) > 4096 or any(c in secret for c in '\r\n\x00')
            or any(c in panel for c in '\r\n\x00\\')):
        raise ValueError('Invalid node identity')
    url = urlsplit(panel)
    if (url.scheme != 'https' or not url.hostname or url.username is not None
            or url.password is not None or url.query or url.fragment
            or url.path not in ('', '/') or url.port not in (None, 443)):
        raise ValueError('A final HTTPS panel origin is required')
    return panel.rstrip('/'), secret


def envelope(raw):
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError('Invalid panel response')
    if 'code' in value and (type(value['code']) is not int or value['code'] != 0):
        raise ValueError('Panel rejected request')
    data = value.get('data', value)
    if not isinstance(data, dict):
        raise ValueError('Invalid panel response')
    return data


def policy(panel, secret, for_install=False):
    try:
        data = envelope(request(panel + API_PATH, headers={'Authorization': 'Bearer ' + secret}))
    except HTTPError as error:
        if error.code == 404:
            return 'unsupported'
        raise
    if type(data.get('enabled')) is not bool:
        raise ValueError('Invalid enabled flag')
    if data.get('sourceMode') not in ('off', 'peer', 'aws-imds'):
        raise ValueError('Invalid source mode')
    install_enabled = data.get('installEnabled', data['enabled'])
    if type(install_enabled) is not bool:
        raise ValueError('Invalid install flag')
    enabled = install_enabled if for_install else data['enabled']
    return 'enabled' if enabled and data['sourceMode'] == 'aws-imds' else 'disabled'


def aws_observation():
    token = request(IMDS + 'api/token', 'PUT',
                    {'X-aws-ec2-metadata-token-ttl-seconds': '60'}, b'', timeout=2).decode().strip()
    if not token or len(token) > 4096 or any(c in token for c in '\r\n\x00'):
        raise ValueError('Invalid metadata token')
    headers = {'X-aws-ec2-metadata-token': token}
    instance = request(IMDS + 'meta-data/instance-id', headers=headers, timeout=2).decode().strip()
    ip_text = request(IMDS + 'meta-data/public-ipv4', headers=headers, timeout=2).decode().strip()
    ip = ipaddress.ip_address(ip_text)
    if (ip.version != 4 or not ip.is_global or ip.is_multicast or ip.is_reserved
            or not re.fullmatch(r'i-(?:[0-9a-f]{8}|[0-9a-f]{17})', instance)):
        raise ValueError('Invalid public IPv4 or instance identity')
    boot = BOOT_ID.read_text(encoding='ascii').strip()
    if str(uuid.UUID(boot)) != boot:
        raise ValueError('Invalid kernel boot identity')
    return {'ip': str(ip), 'instanceId': instance,
            'observedAt': int(time.time()), 'bootId': boot}


def once(root):
    panel, secret = read_identity(root)
    mode = policy(panel, secret)
    if mode != 'enabled':
        return {'status': mode, 'reported': False}
    observed = aws_observation()
    # Fetching metadata may overlap a settings change. The receiver rechecks opt-in.
    reply = envelope(request(panel + API_PATH, 'POST', {
        'Authorization': 'Bearer ' + secret, 'Content-Type': 'application/json',
    }, json.dumps(observed, separators=(',', ':')).encode()))
    if reply.get('accepted') is not True or reply.get('ip') != observed['ip']:
        raise ValueError('Panel did not acknowledge this address')
    return {'status': 'reported', 'reported': True}


def atomic(path, contents, mode):
    path = Path(path)
    if path.is_symlink():
        raise ValueError('Symlink target refused')
    fd, name = tempfile.mkstemp(prefix='.airport-address-', dir=path.parent)
    try:
        os.fchmod(fd, mode)
        with os.fdopen(fd, 'wb') as stream:
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def install(root):
    panel, secret = read_identity(root)
    status = policy(panel, secret, for_install=True)
    if status != 'enabled':
        # An unsupported/disabled panel must not leave behind a new component.
        return {'status': status, 'installed': False}
    root = Path(root).resolve()
    if not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(root)) or not SYSTEMD_ACTIVE.is_dir():
        raise ValueError('Unsupported root or init system')
    if UNIT_DIR.is_symlink() or not UNIT_DIR.is_dir():
        raise ValueError('Invalid systemd unit directory')
    destination = root / 'airport_address_reporter.py'
    contents = Path(__file__).read_bytes()
    service = (
        '[Unit]\nDescription=Optional Lunaris AWS IPv4 observation\n'
        'Wants=network-online.target\nAfter=network-online.target\n\n'
        '[Service]\nType=oneshot\nUser=root\nUMask=0077\n'
        'ExecStart=/usr/bin/env python3 ' + str(destination) + ' --once --root ' + str(root) + '\n'
        'TimeoutStartSec=30\nNoNewPrivileges=true\nPrivateTmp=true\nProtectHome=true\n'
        'ProtectSystem=strict\nRuntimeDirectory=flux-panel-airport-address\n'
        'RuntimeDirectoryMode=0700\nRuntimeDirectoryPreserve=yes\n'
    )
    timer = (
        '[Unit]\nDescription=Refresh optional Lunaris AWS IPv4 observation\n\n'
        '[Timer]\nOnBootSec=2s\nOnUnitInactiveSec=10s\nAccuracySec=1s\n'
        'Unit=' + UNIT_NAME + '.service\n\n[Install]\nWantedBy=timers.target\n'
    )
    atomic(destination, contents, 0o700)
    atomic(UNIT_DIR / (UNIT_NAME + '.service'), service.encode(), 0o644)
    atomic(UNIT_DIR / (UNIT_NAME + '.timer'), timer.encode(), 0o644)
    for command in (['systemctl', 'daemon-reload'],
                    ['systemctl', 'enable', '--now', UNIT_NAME + '.timer'],
                    ['systemctl', 'start', UNIT_NAME + '.service']):
        subprocess.run(command, check=True, capture_output=True, timeout=35)
    return {'status': 'installed', 'installed': True, 'intervalSeconds': 10,
            'agentRestarted': False}


def locked_once(root):
    if RUNTIME_DIR.is_symlink():
        raise ValueError('Symlink runtime directory refused')
    RUNTIME_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = RUNTIME_DIR.stat()
    if info.st_uid != os.geteuid() or info.st_mode & 0o077:
        raise PermissionError('Private runtime directory required')
    fd = os.open(RUNTIME_DIR / 'once.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as stream:
        info = os.fstat(stream.fileno())
        if info.st_uid != os.geteuid() or not stat.S_ISREG(info.st_mode) or info.st_mode & 0o077:
            raise PermissionError('Private runtime lock required')
        try:
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return {'status': 'busy', 'reported': False}
        return once(root)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='/etc/flux-panel-agent')
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument('--once', action='store_true')
    modes.add_argument('--install', action='store_true')
    args = parser.parse_args(argv)
    try:
        require_root()
        result = install(args.root) if args.install else locked_once(args.root)
        print(json.dumps(result, separators=(',', ':')))
        return 0
    except Exception as error:
        safe = {'status': 'error', 'errorType': type(error).__name__}
        if isinstance(error, HTTPError):
            safe['httpStatus'] = error.code
        # Never print exceptions, response bodies, URLs, headers, or config data.
        print(json.dumps(safe, separators=(',', ':')), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
