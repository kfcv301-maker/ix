"""Check prompt dispatch after a real committed pause transaction.

Only called by backend_mysql_test.sh against its disposable containers.
An offline node must promptly produce a persisted retry instead of leaving a
claimed row running because its worker read the pre-commit state.
"""
import json
import subprocess
import sys
import time

backend, mysql = sys.argv[1:]
assert backend.startswith('lunaris-check-') and mysql.startswith('lunaris-check-')


def sql(statement):
    result = subprocess.run(['docker', 'exec', '-i', mysql, 'mysql', '-uroot',
                             '-pintegration-only-root', '-N', 'flux_panel'],
                            input=statement, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def api(path, data, token=None):
    command = ['docker', 'exec', '-i', backend, 'curl', '-fsS',
               'http://localhost:6365/api/v1/' + path, '-H', 'Content-Type: application/json',
               '--data-binary', '@-']
    if token:
        command += ['-H', 'Authorization: ' + token]
    response = subprocess.run(command, input=json.dumps(data), text=True,
                              capture_output=True, check=True)
    payload = json.loads(response.stdout)
    assert payload.get('code') == 0, f'{path}: {payload.get("msg")}'
    return payload['data']


sql("""
UPDATE vite_config SET value='false' WHERE name='captcha_enabled';
INSERT INTO node (id,name,secret,ip,server_ip,port_sta,port_end,created_time,status)
VALUES (99,'offline-dispatch-fixture','fixture-secret','127.0.0.1','127.0.0.1',18000,18099,1,0);
INSERT INTO tunnel (id,name,in_node_id,in_ip,out_node_id,out_ip,type,flow,created_time,updated_time,status)
VALUES (99,'dispatch-fixture',99,'127.0.0.1',99,'127.0.0.1',1,2,1,1,1);
INSERT INTO tunnel_entry_node (id,tunnel_id,node_id,created_time,status) VALUES (99,99,99,1,1);
INSERT INTO forward (id,user_id,user_name,name,tunnel_id,in_port,remote_addr,created_time,updated_time,status)
VALUES (99,1,'admin','dispatch-fixture',99,18001,'127.0.0.1:18002',1,1,1);
""")
credentials = subprocess.check_output(['docker', 'exec', backend, 'cat',
                                       '/app/config/initial-admin-credentials'], text=True)
values = dict(line.split('=', 1) for line in credentials.splitlines() if '=' in line)
token = api('user/login', {'username': values['username'], 'password': values['password']})['token']
api('forward/pause', {'id': 99}, token)
deadline = time.monotonic() + 8
while time.monotonic() < deadline:
    rows = sql("SELECT CONCAT(task_status,':',attempts) FROM forward_sync_task WHERE forward_id=99")
    if rows and all(row.startswith('pending:') and int(row.split(':')[1]) >= 1 for row in rows.splitlines()):
        assert sql('SELECT status FROM forward WHERE id=99') == '2'
        print('Committed pause dispatch reaches the offline node and persists a prompt retry')
        break
    time.sleep(0.2)
else:
    raise AssertionError('Committed pause was not dispatched within 8 seconds: ' + rows)
