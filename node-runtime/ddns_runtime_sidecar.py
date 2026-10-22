#!/usr/bin/env python3
"""DDNS configuration compatibility helper for legacy Agents.

--check is strictly read only.
--sync is intended for the existing DDNS oneshot timer and never controls services.
"""
import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

CF = 'https://api.cloudflare.com/client/v4'
MAX_DOCUMENT = 1024 * 1024


class Refused(RuntimeError):
    pass


class FetchFailed(RuntimeError):
    def __init__(self, status=None):
        self.status = status
        super().__init__('Configuration request failed')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def request_json(url, headers, method='GET', body=None):
    request = urllib.request.Request(url, headers=headers, method=method,
        data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
            data = response.read(MAX_DOCUMENT + 1)
            if len(data) > MAX_DOCUMENT:
                raise Refused('Response too large')
            return json.loads(data)
    except urllib.error.HTTPError as error:
        raise FetchFailed(error.code) from None
    except (urllib.error.URLError, TimeoutError, OSError, ValueError):
        raise FetchFailed() from None


def read_private(path):
    # Read and check the same opened inode; do not follow credential symlinks.
    fd = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0))
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o600:
            raise Refused('Private file permissions invalid')
        data = stream.read(MAX_DOCUMENT + 1)
        if len(data) > MAX_DOCUMENT:
            raise Refused('Private file too large')
        return json.loads(data)


def write_private(path, value):
    if path.is_symlink():
        raise Refused('Private file is a symlink')
    fd, temporary = tempfile.mkstemp(prefix='.runtime-ddns-', dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, separators=(',', ':'))
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def legacy_agent(root):
    try:
        result = subprocess.run([str(root / 'gost'), '--panel-agent-version'],
            capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        raise Refused('Agent ownership unknown') from None
    if result.returncode:
        if 'flag provided but not defined' in result.stderr and 'panel-agent-version' in result.stderr:
            return True
        raise Refused('Agent ownership unknown')
    version = re.fullmatch(r'(\d+)\.(\d+)\.(\d+)-enhanced', result.stdout.strip())
    if not version:
        raise Refused('Agent ownership unknown')
    return tuple(map(int, version.groups())) < (1, 4, 15)


def public_ip(version):
    endpoint = 'https://api.ipify.org' if version == 4 else 'https://api64.ipify.org'
    try:
        result = subprocess.run(['curl', '-' + str(version), '--fail', '--silent',
            '--connect-timeout', '5', '--max-time', '12', endpoint],
            capture_output=True, text=True, timeout=15)
        if result.returncode:
            return None
        address = ipaddress.ip_address(result.stdout.strip())
        return str(address) if address.version == version and address.is_global else None
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None


def normalize_runtime(document):
    if not isinstance(document, dict) or document.get('schemaVersion') != 1:
        raise Refused('Unsupported runtime schema')
    revision = document.get('revision')
    if not isinstance(revision, str) or not re.fullmatch('[a-f0-9]{64}', revision):
        raise Refused('Invalid runtime revision')
    ddns = document.get('ddns')
    if not isinstance(ddns, dict) or type(ddns.get('enabled')) is not bool:
        raise Refused('Invalid DDNS settings')
    interval = ddns.get('intervalSeconds', 60)
    if interval is None or (type(interval) is int and interval == 0):
        interval = 60
    if type(interval) is not int or not 30 <= interval <= 86400:
        raise Refused('Invalid DDNS interval')
    if ddns['enabled']:
        token, domain = ddns.get('apiToken'), ddns.get('recordName')
        if ddns.get('provider') != 'cloudflare' or not isinstance(token, str) or not 0 < len(token) <= 4096 or any(c in token for c in '\r\n\x00'):
            raise Refused('Invalid DDNS credential')
        if not isinstance(domain, str):
            raise Refused('Invalid DDNS record')
        domain = domain.strip().rstrip('.').lower()
        if len(domain) > 253 or not re.fullmatch(r'([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', domain):
            raise Refused('Invalid DDNS record')
        ddns = {'enabled': True, 'provider': 'cloudflare', 'apiToken': token,
                'recordName': domain, 'intervalSeconds': interval}
    else:
        ddns = {'enabled': False, 'provider': 'cloudflare', 'apiToken': '',
                'recordName': '', 'intervalSeconds': interval}
    return {'schemaVersion': 1, 'revision': revision, 'ddns': ddns}


class Sidecar:
    def __init__(self, root, request=request_json, read=read_private, write=write_private,
                 is_legacy=legacy_agent, detect_ip=public_ip, now=time.time):
        self.root = Path(root)
        self.request, self.read, self.write = request, read, write
        self.is_legacy, self.detect_ip, self.now = is_legacy, detect_ip, now
        self.cache_path = self.root / 'runtime-ddns-sidecar.json'

    def require_legacy(self):
        if not self.is_legacy(self.root):
            raise Refused('Native Agent owns DDNS')

    def identity(self):
        local = self.read(self.root / 'config.json')
        panel, secret = local.get('addr'), local.get('secret')
        if not isinstance(panel, str) or not isinstance(secret, str) or not secret or any(c in secret for c in '\r\n\x00'):
            raise Refused('Invalid node identity')
        parsed = urllib.parse.urlsplit(panel)
        if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
            raise Refused('Invalid panel origin')
        origin = panel.rstrip('/')
        binding = hashlib.sha256((origin + '\0' + secret).encode()).hexdigest()
        return origin, secret, binding

    def configuration(self, sync):
        origin, secret, binding = self.identity()
        cached = None
        try:
            saved = self.read(self.cache_path)
            if saved.get('identityBinding') == binding and not saved.get('revoked'):
                cached = normalize_runtime(saved['configuration'])
        except (OSError, KeyError, TypeError, ValueError, Refused):
            pass
        try:
            desired = normalize_runtime(self.request(origin + '/api/v1/node/runtime-config',
                {'Authorization': 'Bearer ' + secret, 'Accept': 'application/json'}))
        except FetchFailed as error:
            if error.status in (401, 403, 404):
                if sync:
                    self.write(self.cache_path, {'schemaVersion': 1, 'identityBinding': binding, 'revoked': True})
                raise Refused('Node authorization revoked') from None
            if error.status is not None and error.status < 500:
                raise Refused('Panel configuration request rejected') from None
            if cached is None:
                raise Refused('No valid saved configuration') from None
            return cached, True, binding
        if sync and desired != cached:
            self.require_legacy()
            self.write(self.cache_path, {'schemaVersion': 1, 'identityBinding': binding,
                                        'configuration': desired, 'savedAt': int(self.now())})
        return desired, False, binding

    def cloudflare(self, ddns, path, method='GET', body=None):
        if method != 'GET':
            # Recheck immediately before any mutation, including future native handoff.
            self.require_legacy()
        response = self.request(CF + path, {'Authorization': 'Bearer ' + ddns['apiToken'],
            'Content-Type': 'application/json'}, method, body)
        if not isinstance(response, dict) or response.get('success') is not True:
            raise Refused('DNS provider rejected request')
        return response.get('result')

    def records(self, ddns, sync):
        addresses = {version: self.detect_ip(version) for version in (4, 6)}
        if not any(addresses.values()):
            raise Refused('No public address detected')
        labels = ddns['recordName'].split('.')
        zone = None
        for index in range(len(labels) - 1):
            zones = self.cloudflare(ddns, '/zones?' + urllib.parse.urlencode({'name': '.'.join(labels[index:])}))
            if zones:
                if len(zones) != 1 or not re.fullmatch('[a-f0-9]{32}', zones[0].get('id', '')):
                    raise Refused('Ambiguous DNS zone')
                zone = zones[0]['id']
                break
        if not zone:
            raise Refused('DNS zone unavailable')
        checked = changed = pending = 0
        for version, address in addresses.items():
            if not address:
                continue
            parsed = ipaddress.ip_address(address)
            if parsed.version != version or not parsed.is_global:
                raise Refused('Invalid public address')
            family = 'A' if version == 4 else 'AAAA'
            base = '/zones/' + zone + '/dns_records'
            records = self.cloudflare(ddns, base + '?' + urllib.parse.urlencode(
                {'name': ddns['recordName'], 'type': family, 'per_page': 100}))
            if not isinstance(records, list) or len(records) > 1:
                raise Refused('Ambiguous DNS record')
            current = records[0] if records else None
            if current and (current.get('name', '').lower().rstrip('.') != ddns['recordName'] or current.get('type') != family):
                raise Refused('DNS record identity mismatch')
            checked += 1
            if current and ipaddress.ip_address(current['content']) == parsed:
                continue
            pending += 1
            if not sync:
                continue
            payload = {'type': family, 'name': ddns['recordName'], 'content': str(parsed),
                       'ttl': current.get('ttl', 1) if current else 1,
                       'proxied': current.get('proxied', False) if current else False}
            if current:
                record_id = current.get('id', '')
                if not re.fullmatch('[a-f0-9]{32}', record_id):
                    raise Refused('Invalid DNS record identity')
                updated = self.cloudflare(ddns, base + '/' + record_id, 'PATCH', payload)
            else:
                updated = self.cloudflare(ddns, base, 'POST', payload)
            if not isinstance(updated, dict) or updated.get('name', '').lower().rstrip('.') != ddns['recordName'] or updated.get('type') != family or ipaddress.ip_address(updated['content']) != parsed:
                raise Refused('DNS provider did not confirm update')
            changed += 1
        return {'recordsChecked': checked, 'recordsChanged': changed, 'recordsPending': pending}

    def run(self, sync=False, require_no_dns_change=False):
        self.require_legacy()
        desired, cached, binding = self.configuration(sync)
        ddns = desired['ddns']
        result = {'ok': True, 'mode': 'sync' if sync else 'check', 'usedSavedConfiguration': cached,
                  'ddnsEnabled': ddns['enabled'], 'timerMinimumSeconds': 60,
                  'effectiveIntervalSeconds': max(60, ddns['intervalSeconds'])}
        if not ddns['enabled']:
            return {**result, 'recordsChecked': 0, 'recordsChanged': 0, 'recordsPending': 0}
        saved = None
        if sync:
            saved = self.read(self.cache_path)
            if saved.get('identityBinding') != binding or normalize_runtime(saved.get('configuration')) != desired:
                raise Refused('Saved configuration changed during synchronization')
            last = saved.get('lastCheckedAt')
            if not require_no_dns_change and saved.get('lastCheckedRevision') == desired['revision'] and type(last) in (int, float) and 0 <= self.now() - last < result['effectiveIntervalSeconds']:
                return {**result, 'intervalPending': True, 'recordsChecked': 0, 'recordsChanged': 0, 'recordsPending': 0}
        outcome = self.records(ddns, sync and not require_no_dns_change)
        if require_no_dns_change and outcome['recordsPending']:
            raise Refused('DNS changes required; installation check refused')
        if sync:
            self.require_legacy()
            saved['lastCheckedAt'] = self.now()
            saved['lastCheckedRevision'] = desired['revision']
            self.write(self.cache_path, saved)
        return {**result, **outcome}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('/etc/flux-panel-agent'))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true', help='No file or DNS writes (default)')
    mode.add_argument('--sync', action='store_true', help='Persist desired DDNS and reconcile DNS; no service operations')
    parser.add_argument('--require-no-dns-change', action='store_true',
        help='Verify current DNS with GET requests only; fail if any change is needed')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise Refused('Root is required')
    # systemd serializes its oneshot unit; also protect explicit concurrent runs.
    if args.sync:
        import fcntl
        lock_path = args.root / '.runtime-ddns-sidecar.lock'
        descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        with os.fdopen(descriptor, 'a+b') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = Sidecar(args.root).run(True, args.require_no_dns_change)
    else:
        result = Sidecar(args.root).run(False, args.require_no_dns_change)
    print(json.dumps(result))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print exception text, response bodies, URLs, tokens, or config.
        print(json.dumps({'ok': False, 'errorType': type(error).__name__,
                          'httpStatus': getattr(error, 'status', None)}))
        raise SystemExit(1)
