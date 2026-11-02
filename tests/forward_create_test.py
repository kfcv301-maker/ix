"""Exercise creation transactions and retries only in disposable CI containers."""
import concurrent.futures
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
    command = ['docker', 'exec', '-i', backend, 'curl', '-sS', '--max-time', '10',
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
ALTER TABLE forward AUTO_INCREMENT=10000;
INSERT INTO node (id,name,secret,ip,server_ip,port_sta,port_end,created_time,status)
VALUES (9200,'offline-create-fixture','create-fixture-secret','127.0.0.1','127.0.0.1',23000,23099,1,0);
INSERT INTO tunnel (id,name,in_node_id,in_ip,out_node_id,out_ip,type,flow,created_time,updated_time,status)
VALUES (9200,'create-fixture',9200,'127.0.0.1',9200,'127.0.0.1',1,2,1,1,1);
INSERT INTO tunnel_entry_node (tunnel_id,node_id,created_time,status) VALUES (9200,9200,1,1);
""")
payload = dict(name='concurrent-create', tunnelId=9200, inPort=23001,
               remoteAddr='127.0.0.1:443', strategy='fifo', proxyProtocol=0,
               requestId='create-retry-00000001')
started = time.monotonic()
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    results = list(pool.map(lambda _: api('forward/create', payload, token), range(4)))
assert time.monotonic() - started < 10, 'Creation must not wait for offline node RPCs'
assert all(result['code'] == 0 for result in results), results
ids = {result['data']['forwardId'] for result in results}
assert len(ids) == 1, results
forward_id = ids.pop()
assert sql("SELECT COUNT(*) FROM forward WHERE create_request_id='create-retry-00000001'") == '1'
assert sql(f'SELECT status FROM forward WHERE id={forward_id}') == '2'
assert sql(f'SELECT COUNT(*) FROM forward_port_reservation WHERE forward_id={forward_id}') == '1'
assert sql(f'SELECT COUNT(*) FROM forward_sync_task WHERE forward_id={forward_id}') == '1'
assert api('forward/create', dict(payload, remoteAddr='127.0.0.1:444'), token)['code'] != 0
assert api('forward/create', dict(payload, requestId='create-conflict-00001'), token)['code'] != 0
assert sql("SELECT COUNT(*) FROM forward WHERE tunnel_id=9200") == '1'

# Force an error after the forward and port rows are inserted: all must roll back.
sql("""DELIMITER $$
CREATE TRIGGER fail_create_task BEFORE INSERT ON forward_sync_task FOR EACH ROW
BEGIN IF NEW.node_id=9200 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CI forced queue failure'; END IF; END$$
DELIMITER ;
""")
try:
    result = api('forward/create', dict(payload, inPort=23002, requestId='create-rollback-00001'), token)
    assert result['code'] != 0, result
    assert sql("SELECT COUNT(*) FROM forward WHERE create_request_id='create-rollback-00001'") == '0'
    assert sql('SELECT COUNT(*) FROM forward_port_reservation WHERE node_id=9200 AND port=23002') == '0'
finally:
    sql('DROP TRIGGER fail_create_task')

# Legacy clients still work without requestId. Both protocols are queued durably.
legacy = dict(payload, inPort=23003, name='legacy-client')
legacy.pop('requestId')
assert api('forward/create', legacy, token)['code'] == 0
assert sql("SELECT COUNT(*) FROM forward WHERE tunnel_id=9200 AND create_request_id IS NULL") == '1'
print('Concurrent replay, payload mismatch, port conflict, rollback and legacy create checks passed')
