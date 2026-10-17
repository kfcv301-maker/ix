"""Exercise the real schema, DTO validation and rule queries in disposable CI containers."""
import json
import subprocess
import sys

backend, mysql = sys.argv[1:]
assert backend.startswith('lunaris-check-') and mysql.startswith('lunaris-check-')


def sql(statement):
    return subprocess.run(
        ['docker', 'exec', '-i', mysql, 'mysql', '-uroot', '-pintegration-only-root',
         '-N', 'flux_panel'], input=statement, text=True, capture_output=True,
        check=True).stdout.strip()


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
admin = api('user/login', {'username': values['username'], 'password': values['password']})
assert admin['code'] == 0, admin
admin_token = admin['data']['token']

sql("""
INSERT INTO user (id,user,pwd,token_version,role_id,exp_time,flow,in_flow,out_flow,
                  flow_reset_time,num,created_time,updated_time,status)
 SELECT 303,'proxy-protocol-fixture',pwd,0,1,4102444800000,100,0,0,1,100,1,1,1
 FROM user WHERE id=1;
INSERT INTO node (id,name,secret,ip,server_ip,port_sta,port_end,created_time,status)
 VALUES (300,'proxy-protocol-fixture','proxy-protocol-fixture-secret','127.0.0.1',
         '127.0.0.1',26000,26999,1,0);
INSERT INTO tunnel (id,name,in_node_id,in_ip,out_node_id,out_ip,type,flow,
                    created_time,updated_time,status)
 VALUES (300,'proxy-protocol-fixture',300,'127.0.0.1',300,'127.0.0.1',1,2,1,1,1);
INSERT INTO tunnel_entry_node (tunnel_id,node_id,created_time,status) VALUES (300,300,1,1);
INSERT INTO user_tunnel (id,user_id,tunnel_id,num,flow,flow_reset_time,exp_time,status)
 VALUES (303,303,300,100,100,1,4102444800000,1);
INSERT INTO forward (id,user_id,user_name,name,tunnel_id,in_port,remote_addr,
                     created_time,updated_time,status)
 VALUES (300,1,'admin','proxy-default',300,26000,'127.0.0.1:9999',1,1,0);
INSERT INTO forward (id,user_id,user_name,name,tunnel_id,in_port,remote_addr,
                     proxy_protocol,created_time,updated_time,status) VALUES
 (301,1,'admin','proxy-v2',300,26001,'127.0.0.1:9999',2,1,1,0),
 (302,1,'admin','proxy-v1',300,26002,'127.0.0.1:9999',1,1,1,0),
 (303,303,'proxy-protocol-fixture','proxy-owned',300,26003,'127.0.0.1:9999',1,1,1,0);
""")
expected = {300: 0, 301: 2, 302: 1, 303: 1}
response = api('forward/list', {}, admin_token)
assert response['code'] == 0, response
actual = {row['id']: row.get('proxyProtocol') for row in response['data'] if row['id'] in expected}
assert actual == expected, f'Admin mapper lost Proxy Protocol configuration: {actual}'

ordinary = api('user/login', {'username': 'proxy-protocol-fixture', 'password': values['password']})
assert ordinary['code'] == 0, ordinary
response = api('forward/list', {}, ordinary['data']['token'])
assert response['code'] == 0, response
assert {row['id']: row.get('proxyProtocol') for row in response['data']} == {303: 1}, response

snapshot = sql('SELECT GROUP_CONCAT(CONCAT(id,":",proxy_protocol) ORDER BY id) '
               'FROM forward WHERE id BETWEEN 300 AND 303')
for value in (-1, 3):
    for path, extra in [('forward/create', {}), ('forward/update', {'id': 301, 'userId': 1})]:
        response = api(path, dict(name='proxy-invalid', tunnelId=300, inPort=26001,
                                  remoteAddr='127.0.0.1:9999', proxyProtocol=value, **extra), admin_token)
        assert response.get('code') != 0, f'{path} accepted unsupported version {value}'
        assert 'proxy' in response.get('msg', '').lower(), response
assert sql('SELECT GROUP_CONCAT(CONCAT(id,":",proxy_protocol) ORDER BY id) '
           'FROM forward WHERE id BETWEEN 300 AND 303') == snapshot
assert sql("SELECT COUNT(*) FROM forward WHERE name='proxy-invalid'") == '0'
print('Proxy Protocol defaults, v1/v2 persistence, owner-scoped queries and DTO rejection passed')
