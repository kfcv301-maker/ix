"""Exercise the real stable entry with fake HTTP and a harmless release script.

No service, DNS record or real installation is touched. Synthetic credentials
must only travel in the private panel request / JSON file, never release args.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASH = shutil.which('bash') or (r'C:\Program Files\Git\bin\bash.exe' if os.name == 'nt' else None)
KEY = 'A' * 43
MOCK_RELEASE = '''#!/usr/bin/env bash
set -eu
[[ "$#" == 2 && "$1" == --config-file ]]
python3 - "$2" <<'PY'
import json,os,sys
from pathlib import Path
data=json.load(open(sys.argv[1]))
Path(os.environ['BOOTSTRAP_RESULT']).write_text(json.dumps({'secret':data['secret'],'record':data['ddns']['recordName'],'panel':data['panelUrl'],'release':os.environ['AGENT_RELEASE'],'base':os.environ['AGENT_RELEASE_BASE']}))
PY
'''


@unittest.skipUnless(BASH, 'bash required')
class NodeBootstrapTest(unittest.TestCase):
    def exercise(self, *, record='one.example.invalid', reject=False, corrupt=False, panel='https://panel.example.invalid'):
        with tempfile.TemporaryDirectory(prefix='node-bootstrap-') as folder:
            work = Path(folder)
            # Root is required by the real entry. The isolated copy only runs
            # a mock installer, so permit the unprivileged CI test account.
            entry = (ROOT / 'node_install.sh').read_text(encoding='utf-8')
            entry = entry.replace('[[ "$EUID" -eq 0 ]] || fail "请以 root 执行"', ':')
            (work / 'entry.sh').write_text(entry, encoding='utf-8', newline='\n')
            bindir = work / 'bin'; bindir.mkdir()
            bashenv = work / 'bash-env.sh'
            bashenv.write_text('export PATH="' + ('$(cygpath -u "$BOOTSTRAP_BIN")' if os.name == 'nt' else '$BOOTSTRAP_BIN') + ':$PATH"\n', newline='\n')
            python_sh = '#!/usr/bin/env bash\nexec "' + sys.executable.replace('\\', '/') + '" "$@"\n'
            (bindir / 'python3').write_text(python_sh, encoding='utf-8', newline='\n')
            (bindir / 'python3').chmod(0o755)
            response = {'schemaVersion': 1, 'nodeId': 27, 'secret': 'private-node-secret', 'agentRelease': '1.4.15',
                        'ddns': {'enabled': True, 'provider': 'cloudflare', 'apiToken': 'private-cloudflare-token', 'recordName': record, 'intervalSeconds': 60}}
            (work / 'response.json').write_text(json.dumps(response))
            (work / 'release.sh').write_text(MOCK_RELEASE, newline='\n')
            digest = '0' * 64 if corrupt else hashlib.sha256((work / 'release.sh').read_bytes()).hexdigest()
            (work / 'release.sha').write_text(digest + '  install.sh\n')
            mock = '''#!/usr/bin/env python3
import os,sys,shutil,json
from pathlib import Path
args=sys.argv[1:]; root=Path(os.environ['BOOTSTRAP_FIXTURES'])
url=next(a for a in args if a.startswith('https://') or a.startswith('http://'))
out=Path(args[args.index('-o')+1])
with (root/'requests.jsonl').open('a') as file:file.write(json.dumps(args)+'\\n')
if '/api/v1/node/install-config/' in url:
    assert '--config' in args and not '--location' in args
    auth=Path(args[args.index('--config')+1]).read_text()
    assert 'Bearer ' + 'A'*43 in auth
    if os.environ.get('BOOTSTRAP_REJECT')=='1':sys.exit(22)
    source=root/'response.json'
elif url.endswith('/install.sh.sha256'):source=root/'release.sha'
elif url.endswith('/install.sh'):source=root/'release.sh'
else:raise AssertionError('unexpected request')
shutil.copyfile(source,out)
'''
            (bindir / 'curl').write_text(mock, encoding='utf-8', newline='\n'); (bindir / 'curl').chmod(0o755)
            result = work / 'result.json'
            env = dict(os.environ, PATH=str(bindir) + os.pathsep + os.environ.get('PATH', ''), BOOTSTRAP_BIN=str(bindir), BASH_ENV=str(bashenv), BOOTSTRAP_FIXTURES=str(work), BOOTSTRAP_RESULT=str(result), BOOTSTRAP_REJECT='1' if reject else '0')
            process = subprocess.run([BASH, str(work / 'entry.sh'), '--panel', panel, '--node-id', '27', '--install-key', KEY], env=env, capture_output=True, text=True, encoding='utf-8', timeout=20)
            log = process.stdout + process.stderr
            for secret in (KEY, 'private-cloudflare-token', 'private-node-secret'):
                self.assertNotIn(secret, log)
            requests = [json.loads(line) for line in (work / 'requests.jsonl').read_text().splitlines()] if (work / 'requests.jsonl').exists() else []
            for args in requests:
                self.assertNotIn(KEY, ' '.join(args))
                self.assertNotIn('private-cloudflare-token', ' '.join(args))
            data = json.loads(result.read_text()) if result.exists() else None
            return process.returncode, data, requests, log

    def test_same_command_fetches_latest_domain_without_exposing_credentials(self):
        for domain in ('one.example.invalid', 'changed.example.invalid'):
            code, result, requests, log = self.exercise(record=domain)
            self.assertEqual(code, 0, log)
            self.assertEqual(result['record'], domain)
            self.assertEqual(result['release'], '1.4.15')
            self.assertEqual(len(requests), 3)

    def test_revocation_does_not_install_from_cached_or_default_config(self):
        code, result, requests, _ = self.exercise(reject=True)
        self.assertNotEqual(code, 0); self.assertIsNone(result); self.assertEqual(len(requests), 1)

    def test_tampered_release_script_never_executes(self):
        code, result, _, _ = self.exercise(corrupt=True)
        self.assertNotEqual(code, 0); self.assertIsNone(result)

    def test_invalid_panel_origin_rejected_before_credential_request(self):
        for panel in ('https://panel.example.invalid@evil.example.invalid', 'https://panel.example.invalid/path', 'https://panel.example.invalid/?x=1'):
            code, result, requests, _ = self.exercise(panel=panel)
            self.assertNotEqual(code, 0); self.assertIsNone(result); self.assertEqual(requests, [])


if __name__ == '__main__':
    unittest.main()
