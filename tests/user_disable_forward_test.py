"""Verify account disable and durable multi-node pause intents commit together."""
import json
import subprocess
import sys
import time

backend, mysql = sys.argv[1:]
assert backend.startswith('lunaris-check-') and mysql.startswith('lunaris-check-')


def sql(statement):
    return subprocess.run(['docker', 'exec', '-i', mysql, 'mysql', '-uroot',
                           '-pintegration-only-root', '-N', 'flux_panel'], input=statement,
                          text=True, capture_output=True, check=True).stdout.strip()


def api(path, data, token=None):
    command = ['docker', 'exec', '-i', backend, 'curl', '-sS',
               'http://localhost:6365/api/v1/' + path, '-H', 'Content-Type: application/json',
               '--data-binary', '@-']
    if token:
        command += ['-H', 'Authorization: ' + token]
    return json.loads(subprocess.run(command, input=json.dumps(data), text=True,
                                     capture_output=True, check=True).stdout)


credentials = subprocess.check_output(['docker', 'exec', backend, 'cat',
                                       '/app/config/initial-admin-credentials'], text=True)
values = dict(line.split('=', 1) for line in credentials.splitlines() if '=' in line)
token = api('user/login', {'username': values['username'], 'password': values['password']})['data']['token']
sql("""
INSERT INTO user (id,user,pwd,token_version,role_id,exp_time,flow,in_flow,out_flow,flow_reset_time,num,created_time,updated_time,status)
 SELECT 200,'disable-fixture',pwd,0,1,4102444800000,100,0,0,1,100,1,1,1 FROM user WHERE id=1;
INSERT INTO node (id,name,secret,ip,server_ip,port_sta,port_end,created_time,status) VALUES
 (200,'disable-ingress','disable-ingress-fixture','127.0.0.1','127.0.0.1',21000,21999,1,0),
 (201,'disable-egress','disable-egress-fixture','127.0.0.1','127.0.0.1',22000,22999,1,0);
INSERT INTO tunnel (id,name,in_node_id,in_ip,out_node_id,out_ip,type,flow,created_time,updated_time,status)
 VALUES (200,'disable-tunnel',200,'127.0.0.1',201,'127.0.0.1',2,2,1,1,1);
INSERT INTO tunnel_entry_node (tunnel_id,node_id,created_time,status) VALUES (200,200,1,1);
INSERT INTO user_tunnel (id,user_id,tunnel_id,num,flow,flow_reset_time,exp_time,status)
 VALUES (200,200,200,100,100,1,4102444800000,1);
INSERT INTO forward (id,user_id,user_name,name,tunnel_id,in_port,out_port,remote_addr,created_time,updated_time,status) VALUES
 (200,200,'disable-fixture','active',200,21000,22000,'127.0.0.1:9999',1,1,1),
 (201,200,'disable-fixture','resuming',200,21001,22001,'127.0.0.1:9999',1,1,2),
 (202,200,'disable-fixture','error',200,21002,22002,'127.0.0.1:9999',1,1,-1),
 (203,200,'disable-fixture','paused',200,21003,22003,'127.0.0.1:9999',1,1,0),
 (204,200,'disable-fixture','deleting',200,21004,22004,'127.0.0.1:9999',1,1,3),
 (205,1,'admin','unrelated',200,21005,22005,'127.0.0.1:9999',1,1,1);
INSERT INTO forward_sync_task (operation_id,forward_id,node_id,endpoint,operation,service_name,target_status,task_status,attempts,next_retry_time,created_time,updated_time,status)
 VALUES ('disable-existing-resume',201,200,'ingress','resume','201_200_200',1,'pending',0,4102444800000,1,1,1);
""")
update = dict(id=200, user='disable-fixture', flow=100, num=100, expTime=4102444800000,
              flowResetTime=1, status=0)
sql("CREATE TRIGGER fail_disable_pause BEFORE INSERT ON forward_sync_task FOR EACH ROW "
    "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='intentional pause rollback fixture';")
response = api('user/update', update, token)
assert response.get('code') != 0, response
assert sql('SELECT status FROM user WHERE id=200') == '1'
assert sql('SELECT status FROM forward WHERE id=200') == '1'
assert sql("SELECT COUNT(*) FROM forward_sync_task WHERE forward_id BETWEEN 200 AND 204 AND operation='pause'") == '0'
sql('DROP TRIGGER fail_disable_pause')
started = time.monotonic()
response = api('user/update', update, token)
assert response.get('code') == 0, response
assert time.monotonic() - started < 8, 'Account disable waited for offline nodes'
assert sql('SELECT status FROM user WHERE id=200') == '0'
assert sql('SELECT GROUP_CONCAT(status ORDER BY id) FROM forward WHERE id BETWEEN 200 AND 205') == '2,2,2,0,3,1'
assert sql("SELECT task_status FROM forward_sync_task WHERE operation_id='disable-existing-resume'") == 'cancelled'
assert sql("SELECT COUNT(*) FROM forward_sync_task WHERE forward_id BETWEEN 200 AND 202 AND operation='pause'") == '6'
response = api('forward/resume', {'id': 203}, token)
assert response.get('code') != 0, 'Administrator resumed a disabled owner rule'
deadline = time.monotonic() + 10
while time.monotonic() < deadline:
    count = sql("SELECT COUNT(*) FROM forward_sync_task WHERE forward_id BETWEEN 200 AND 202 "
                "AND operation='pause' AND task_status='pending' AND attempts>0")
    if count == '6':
        break
    time.sleep(0.2)
else:
    raise AssertionError('Offline ingress/egress pause work did not persist retries')
update['status'] = 1
assert api('user/update', update, token).get('code') == 0
assert sql('SELECT GROUP_CONCAT(status ORDER BY id) FROM forward WHERE id BETWEEN 200 AND 205') == '2,2,2,0,3,1'
assert sql("SELECT COUNT(*) FROM forward_sync_task WHERE forward_id BETWEEN 200 AND 204 "
           "AND operation='resume' AND task_status IN ('pending','running')") == '0'
print('Account disable atomically pauses active/error/resuming rules; offline retries, rollback and explicit re-enable passed')
