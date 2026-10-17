"""Run generated DDNS scripts against a fake Cloudflare API and fake IP probes.

All configuration, DNS names, keys and addresses below are synthetic. The
tests exercise first installation and machine replacement without changing
real DNS, services, Agent binaries, or application traffic.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile

MOCK_CURL = r'''#!/usr/bin/python3
import json,os,sys,urllib.parse
from pathlib import Path
args=sys.argv[1:]
state_path=Path(os.environ['DDNS_TEST_API_STATE'])
state=json.loads(state_path.read_text())
url=next((a for a in args if a.startswith('https://')), '')
if url=='https://api.ipify.org':
    print('198.51.100.42',end='');sys.exit(0)
if url=='https://api64.ipify.org':
    print('2001:db8::42',end='');sys.exit(0)
params={}
for index,arg in enumerate(args[:-1]):
    if arg=='--data-urlencode':
        key,value=args[index+1].split('=',1);params[key]=value
method=args[args.index('-X')+1] if '-X' in args else 'GET'
state['requests'].append({'method':method,'path':urllib.parse.urlparse(url).path,'query':params})
if state.get('api_failure'):
    state_path.write_text(json.dumps(state));print(json.dumps({'success':False,'errors':[{'code':10000,'message':'Synthetic authorization failure'}]}));sys.exit(0)
if '/zones' in url and '/dns_records' not in url:
    rows=[{'id':'test-zone','name':'example.invalid'}] if params.get('name')=='example.invalid' else []
elif method=='GET':
    rows=[r for r in state['records'] if r['type']==params.get('type') and r['name']==params.get('name')]
elif method=='POST':
    row=json.loads(args[args.index('--data')+1]);row['id']='created-'+row['type'];state['records'].append(row);rows=row
elif method=='PATCH':
    row=json.loads(args[args.index('--data')+1]);record_id=urllib.parse.urlparse(url).path.rsplit('/',1)[-1]
    old=next(r for r in state['records'] if r['id']==record_id);old.update(row);rows=old
else:
    raise RuntimeError('Unexpected test method')
state_path.write_text(json.dumps(state))
print(json.dumps({'success':True,'result':rows}))
'''


def record(kind, address, suffix=""):
    return {"id": "test-" + kind + suffix, "type": kind, "name": "node.example.invalid",
            "content": address, "ttl": 300, "proxied": False}


def exercise(script):
    current = [record("A", "198.51.100.42"), record("AAAA", "2001:db8::42")]
    old = [record("A", "192.0.2.10"), record("AAAA", "2001:db8::10")]
    boot = Path("/proc/sys/kernel/random/boot_id").read_text().strip()
    cases = [
        ("fresh-domain-no-records", [], None, False, 0),
        ("existing-A-missing-AAAA", [old[0]], None, False, 0),
        ("replacement-both-addresses-changed", old, None, False, 0),
        ("new-boot-same-local-IP-restores-changed-DNS", old, "previous-boot", False, 0),
        ("fresh-install-records-already-correct", current, None, False, 0),
        ("same-boot-addresses-unchanged", current, boot, False, 0),
        ("refuse-duplicate-A-records", [old[0], record("A", "192.0.2.11", "-duplicate"), old[1]], None, False, 1),
        ("API-failure-stops-without-DNS-write", old, None, True, 1),
    ]
    results = []
    for name, records, cached_boot, api_failure, expected_code in cases:
        with tempfile.TemporaryDirectory(prefix="lunaris-ddns-install-regression-") as directory:
            root = Path(directory)
            mock = root / "bin"
            mock.mkdir()
            curl = mock / "curl"
            curl.write_text(MOCK_CURL)
            curl.chmod(0o700)
            target = root / "cloudflare-ddns.sh"
            target.write_text(script)
            (root / "ddns.env").write_text("CF_API_TOKEN=synthetic-test-token\nCF_RECORD_NAME=node.example.invalid\n")
            if cached_boot:
                (root / "ddns.state").write_text("LAST_IPV4=198.51.100.42\nLAST_IPV6=2001:db8::42\nLAST_BOOT_ID=" + cached_boot + "\n")
            state_path = root / "api-state.json"
            state_path.write_text(json.dumps({"records": records, "requests": [], "api_failure": api_failure}))
            environment = dict(os.environ, PATH=str(mock) + ":/usr/bin:/bin", DDNS_TEST_API_STATE=str(state_path))
            process = subprocess.run(["bash", str(target)], env=environment, capture_output=True, text=True, timeout=20)
            state = json.loads(state_path.read_text())
            writes = [r["method"] for r in state["requests"] if r["method"] in ("POST", "PATCH", "DELETE")]
            final = {r["type"]: r["content"] for r in state["records"]}
            expected_addresses = final.get("A") == "198.51.100.42" and final.get("AAAA") == "2001:db8::42"
            passed = process.returncode == expected_code
            if expected_code == 0:
                passed = passed and expected_addresses and (root / "ddns.state").exists()
                passed = passed and all(r["ttl"] == 300 and not r["proxied"] for r in state["records"] if not r["id"].startswith("created-"))
            else:
                passed = passed and not writes
            if name == "same-boot-addresses-unchanged":
                passed = passed and not state["requests"]
            results.append({"case": name, "exit_code": process.returncode, "dns_writes": writes,
                            "state_saved": (root / "ddns.state").exists(), "passed": passed})
    return results


if __name__ == "__main__":
    import sys
    installer = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "install.sh"
    content = installer.read_text()
    if "write_ddns_updater() {" in content:
        import re
        match = re.search(r"cat > \"\$INSTALL_DIR/cloudflare-ddns\.sh\" <<'EOF'\n(.*?)\nEOF", content, re.S)
        if not match:
            raise SystemExit("DDNS installer template missing")
        content = match.group(1) + "\n"
    results = exercise(content)
    print(json.dumps(results))
    if not all(result["passed"] for result in results):
        raise SystemExit(1)
