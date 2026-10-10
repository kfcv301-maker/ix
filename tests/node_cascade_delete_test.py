"""Exercise offline node cascade and rollback through HTTP on disposable MySQL containers."""
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
INSERT INTO node (id,name,secret,ip,server_ip,port_sta,port_end,created_time,status) VALUES
 (100,'retired','cascade-fixture','127.0.0.1','127.0.0.1',19000,19999,1,0),
 (101,'surviving','surviving-fixture','127.0.0.1','127.0.0.1',19000,19999,1,0);
INSERT INTO tunnel (id,name,in_node_id,in_ip,out_node_id,out_ip,type,flow,created_time,updated_time,status) VALUES
 (100,'primary',100,'127.0.0.1',101,'127.0.0.1',2,2,1,1,1),
 (101,'egress',101,'127.0.0.1',100,'127.0.0.1',2,2,1,1,1),
 (102,'secondary',101,'127.0.0.1',101,'127.0.0.1',1,2,1,1,1),
 (103,'unrelated',101,'127.0.0.1',101,'127.0.0.1',1,2,1,1,1);
INSERT INTO tunnel_entry_node (tunnel_id,node_id,created_time,status) VALUES
 (100,100,1,1),(101,101,1,1),(102,101,1,1),(102,100,1,1),(103,101,1,1);
INSERT INTO forward (id,user_id,user_name,name,tunnel_id,in_port,out_port,remote_addr,created_time,updated_time,status)
 SELECT id,1,'admin',name,id,19000+id,19500+id,'127.0.0.1:9999',1,1,3 FROM tunnel WHERE id BETWEEN 100 AND 103;
INSERT INTO user_tunnel (id,user_id,tunnel_id,num,flow,flow_reset_time,exp_time,status)
 VALUES (100,1,100,10,100,1,4102444800000,1);
INSERT INTO tunnel_entry_domain (tunnel_id,domain,is_default,created_time,status)
 VALUES (100,'cascade.example.com',1,1,1);
INSERT INTO speed_limit (id,name,speed,tunnel_id,tunnel_name,created_time,status)
 VALUES (100,'cascade-limit',100,100,'primary',1,1);
INSERT INTO forward_port_reservation (node_id,port,forward_id,endpoint,created_time)
 VALUES (100,19100,100,'ingress',1),(101,19600,100,'egress',1);
""")
# Fail the last delete, after the service has removed its dependencies. The
# transaction must restore them and must not dispatch its uncommitted tasks.
sql("CREATE TRIGGER fail_cascade BEFORE DELETE ON node FOR EACH ROW "
    "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='intentional cascade rollback fixture';")
response = api('node/delete', {'id': 100}, token)
assert response.get('code') != 0, response
assert sql('SELECT COUNT(*) FROM node WHERE id=100') == '1'
assert sql('SELECT COUNT(*) FROM tunnel WHERE id BETWEEN 100 AND 102') == '3'
assert sql('SELECT COUNT(*) FROM forward WHERE id BETWEEN 100 AND 102') == '3'
assert sql("SELECT COUNT(*) FROM forward_sync_task WHERE operation='cleanup' AND forward_id BETWEEN 100 AND 102") == '0'
assert sql('SELECT COUNT(*) FROM user_tunnel WHERE id=100') == '1'
sql('DROP TRIGGER fail_cascade')

started = time.monotonic()
response = api('node/delete', {'id': 100}, token)
assert response.get('code') == 0, response
assert time.monotonic() - started < 8, 'Deletion waited for offline node ACKs'
for table, condition in [('node', 'id=100'), ('tunnel', 'id BETWEEN 100 AND 102'),
                         ('forward', 'id BETWEEN 100 AND 102'), ('user_tunnel', 'tunnel_id=100'),
                         ('tunnel_entry_domain', 'tunnel_id=100'), ('speed_limit', 'tunnel_id=100'),
                         ('tunnel_entry_node', 'node_id=100 OR tunnel_id BETWEEN 100 AND 102')]:
    assert sql(f'SELECT COUNT(*) FROM {table} WHERE {condition}') == '0', table
assert sql('SELECT COUNT(*) FROM node WHERE id=101') == '1'
assert sql('SELECT COUNT(*) FROM tunnel WHERE id=103') == '1'
assert sql('SELECT COUNT(*) FROM forward WHERE id=103') == '1'
assert sql('SELECT COUNT(*) FROM forward_port_reservation WHERE node_id=100') == '0'
assert sql('SELECT COUNT(*) FROM forward_port_reservation WHERE node_id=101 AND forward_id=100') == '1'
deadline = time.monotonic() + 10
while time.monotonic() < deadline:
    pending = sql("SELECT COUNT(*) FROM forward_sync_task WHERE operation='cleanup' AND node_id=101 "
                  "AND task_status='pending' AND attempts>0")
    retired = sql("SELECT COUNT(*) FROM forward_sync_task WHERE operation='cleanup' AND node_id=100 "
                  "AND task_status IN ('pending','running')")
    if int(pending) > 0 and retired == '0':
        break
    time.sleep(0.2)
else:
    raise AssertionError('Detached cleanup did not settle retired nodes and retry surviving nodes')
print('Offline node cascade, multi-ingress/egress scope, rollback and durable cleanup passed')
