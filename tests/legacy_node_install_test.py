"""Old command syntax still installs a new Agent without requiring new panel APIs.

Use real argument parsing, checksum download and config writing. Replace host
service/network operations with harmless stubs and use synthetic credentials.
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
STUBS = r'''
require_root() { :; }
detect_init_system() { INIT_SYSTEM=systemd; }
apply_tcp_tuning() { :; }
configure_tcp_tuning_guard() { :; }
write_service() { :; }
configure_ddns() {
  printf '%s\n' "$DDNS_MODE" "$CF_RECORD_NAME" "$TCP_PROFILE_OVERRIDE" "$TCP_AUTO_TUNE" > "$INSTALL_DIR/options.txt"
}
start_agent_service() { printf 'started\n' > "$INSTALL_DIR/started"; }
sleep() { :; }
uname() { [[ "${1:-}" == -m ]] && printf '%s\n' "$LEGACY_ARCH" || printf 'fixture\n'; }
'''


@unittest.skipUnless(BASH, 'bash required')
class LegacyNodeInstallerTest(unittest.TestCase):
    def exercise(self, args, *, architecture='x86_64', old_config=None, corrupt=False, release_base=None):
        with tempfile.TemporaryDirectory(prefix='legacy-node-install-') as folder:
            work=Path(folder)
            agent=work/'agent'; agent.mkdir()
            if old_config is not None:
                (agent/'config.json').write_text(json.dumps(old_config))
            script=(ROOT/'install.sh').read_text(encoding='utf-8')
            self.assertEqual(script.count('\ninstall_agent\n'),1)
            script=script.replace('\ninstall_agent\n','\n'+STUBS+'\ninstall_agent\n')
            entry=work/'install.sh'; entry.write_text(script,encoding='utf-8',newline='\n')
            bindir=work/'bin';bindir.mkdir()
            (bindir/'python3').write_text('#!/usr/bin/env bash\nexec "'+sys.executable.replace('\\','/')+'" "$@"\n',newline='\n')
            (bindir/'python3').chmod(0o755)
            mock=r'''#!/usr/bin/env python3
import hashlib,json,os,sys
from pathlib import Path
args=sys.argv[1:]
url=next(a for a in args if a.startswith('https://'))
root=Path(os.environ['LEGACY_FIXTURES'])
with (root/'requests.jsonl').open('a') as f:f.write(json.dumps(args)+'\n')
assert '/releases/download/' in url and '/api/' not in url
name=url.split('/')[-1]
data=b'Harmless fixture; never execute this file.\n'
if name.endswith('.sha256'):
    digest='0'*64 if os.environ.get('LEGACY_CORRUPT')=='1' else hashlib.sha256(data).hexdigest()
    data=(digest+'  '+name.removesuffix('.sha256')+'\n').encode()
Path(args[args.index('-o')+1]).write_bytes(data)
'''
            (bindir/'curl').write_text(mock,newline='\n');(bindir/'curl').chmod(0o755)
            bashenv=work/'bash-env.sh'
            bashenv.write_text('export PATH="'+('$(cygpath -u "$LEGACY_BIN")' if os.name=='nt' else '$LEGACY_BIN')+':$PATH"\n',newline='\n')
            env=dict(os.environ,BASH_ENV=str(bashenv),LEGACY_BIN=str(bindir),LEGACY_FIXTURES=str(work),
                     INSTALL_DIR=agent.as_posix(),LEGACY_ARCH=architecture,LEGACY_CORRUPT='1' if corrupt else '0')
            env.pop('AGENT_RELEASE',None);env.pop('AGENT_RELEASE_BASE',None)
            if release_base:env['AGENT_RELEASE_BASE']=release_base
            result=subprocess.run([BASH,str(entry),*args],env=env,capture_output=True,text=True,encoding='utf-8',timeout=30)
            output=result.stdout+result.stderr
            self.assertNotIn('fixture-node-secret',output)
            self.assertNotIn('fixture-provider-secret',output)
            requests=[json.loads(x) for x in (work/'requests.jsonl').read_text().splitlines()]
            config=json.loads((agent/'config.json').read_text()) if (agent/'config.json').exists() else None
            options=(agent/'options.txt').read_text().splitlines() if (agent/'options.txt').exists() else None
            return result.returncode,config,options,requests,(agent/'started').exists(),output

    def test_old_parameter_aliases_support_machine_replacement_on_both_architectures(self):
        for panel,token in (('--panel','--token'),('--server','--secret'),('-a','-s'),('-p','-t')):
            for arch,suffix in (('x86_64','amd64'),('aarch64','arm64')):
                with self.subTest(panel=panel,arch=arch):
                    code,config,options,requests,started,output=self.exercise([panel,'https://old-panel.example.invalid',token,'fixture-node-secret','--skip-tcp-tuning'],architecture=arch)
                    self.assertEqual(code,0,output);self.assertTrue(started)
                    self.assertEqual(config['addr'],'https://old-panel.example.invalid')
                    self.assertEqual(config['secret'],'fixture-node-secret')
                    self.assertNotIn('runtime',config)
                    self.assertEqual(options[0],'unchanged')
                    self.assertEqual(len(requests),2)
                    urls=[next(a for a in r if a.startswith('https://')) for r in requests]
                    self.assertTrue(urls[0].endswith('/flux-panel-agent-linux-'+suffix))
                    self.assertEqual(urls[1],urls[0]+'.sha256')

    def test_legacy_ddns_and_tuning_arguments_remain_supported(self):
        code,config,options,_,started,output=self.exercise(['--panel','https://old-panel.example.invalid','--token','fixture-node-secret',
            '--cf-api-token','fixture-provider-secret','--cf-record','node.example.invalid','--tcp-auto-tune','--tcp-profile-min','tiny','--tcp-profile-max','standard'])
        self.assertEqual(code,0,output);self.assertTrue(started)
        self.assertEqual(options,['enabled','node.example.invalid','','1'])
        self.assertNotIn('runtime',config)

    def test_old_release_base_and_existing_protocol_flags_are_preserved(self):
        base='https://github.com/kfcv301-maker/ix/releases/download/1.4.13'
        code,config,_,requests,started,output=self.exercise(['-a','http://old-panel.example.invalid:6365','-s','fixture-node-secret'],
            old_config={'http':0,'tls':1,'socks':0},release_base=base)
        self.assertEqual(code,0,output);self.assertTrue(started)
        self.assertEqual([config[k] for k in ('http','tls','socks')],[0,1,0])
        self.assertTrue(all(any(a.startswith(base+'/') for a in r) for r in requests))

    def test_bad_download_does_not_write_config_or_start_agent(self):
        code,config,_,_,started,_=self.exercise(['--panel','https://old-panel.example.invalid','--token','fixture-node-secret'],corrupt=True)
        self.assertNotEqual(code,0);self.assertIsNone(config);self.assertFalse(started)


if __name__=='__main__':
    unittest.main()
