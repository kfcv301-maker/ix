"""Persistent IP history, date counts and tenant isolation in disposable MySQL CI."""
import base64
import hashlib
import hmac
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

backend, mysql = sys.argv[1:]
assert backend.startswith('lunaris-check-') and mysql.startswith('lunaris-check-')

def sql(statement):
    return subprocess.run(['docker','exec','-i',mysql,'mysql','-uroot','-pintegration-only-root','-N','flux_panel'],
                          input=statement,text=True,capture_output=True,check=True).stdout.strip()

def api(path, body, token=None):
    command=['docker','exec','-i',backend,'curl','-sS','--max-time','10','http://localhost:6365/api/v1/'+path,
             '-H','Content-Type: application/json','--data-binary','@-']
    if token: command+=['-H','Authorization: '+token]
    return json.loads(subprocess.run(command,input=json.dumps(body),text=True,capture_output=True,check=True).stdout)

def owner_token(uid):
    def enc(value): return base64.urlsafe_b64encode(value).decode().rstrip('=')
    header=enc(b'{"alg":"HmacSHA256","typ":"JWT"}')
    payload=enc(json.dumps(dict(sub=str(uid),user='ip-fixture-'+str(uid),role_id=1,token_version=0,iat=int(time.time()),exp=int(time.time())+300)).encode())
    data=header+'.'+payload
    secret=b'integration-only-64-character-jwt-secret-0123456789012345678901234567'
    return data+'.'+enc(hmac.new(secret,data.encode(),hashlib.sha256).digest())

credentials=subprocess.check_output(['docker','exec',backend,'cat','/app/config/initial-admin-credentials'],text=True)
values=dict(line.split('=',1) for line in credentials.splitlines() if '=' in line)
admin=api('user/login',{'username':values['username'],'password':values['password']})['data']['token']
assert sql("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='flux_panel' AND TABLE_NAME IN ('node_ip_state','node_ip_event')")=='2'
sql("""INSERT INTO user (id,user,pwd,token_version,role_id,exp_time,flow,in_flow,out_flow,num,created_time,updated_time,status)
VALUES (9301,'ip-fixture-9301','unused',0,1,4102444800000,100,0,0,100,1,1,1),
(9302,'ip-fixture-9302','unused',0,1,4102444800000,100,0,0,100,1,1,1);
INSERT INTO node (id,name,secret,ip,server_ip,port_sta,port_end,owner_user_id,created_time,status)
VALUES (9301,'owner-ip','fixture-ip-secret-1','192.0.2.10','192.0.2.10',10000,10099,9301,1,0),
(9302,'other-ip','fixture-ip-secret-2','192.0.2.20','192.0.2.20',10000,10099,9302,1,0),
(9303,'admin-ip','fixture-ip-secret-3','192.0.2.30','192.0.2.30',10000,10099,NULL,1,0);""")
now=int(time.time()*1000)
today=datetime.now(ZoneInfo('Asia/Shanghai')).replace(hour=0,minute=0,second=0,microsecond=0)
start=int(today.timestamp()*1000)
state=dict(target='192.0.2.10',source='configured',addresses=['192.0.2.10'],candidate=[],candidateSince=0,
           startedAt=start-86400000,baselineAt=start-86400000,checkedAt=now,resolvedAt=now,lastChangeAt=start,status='ok')
encoded=json.dumps(state,separators=(',',':'))
sql("INSERT INTO node_ip_state (node_id,payload,checked_at) VALUES (9301,'"+encoded+"',"+str(now)+") ON DUPLICATE KEY UPDATE payload=VALUES(payload),checked_at=VALUES(checked_at)")
# 52 changes span midnight: 2 today, 50 yesterday. Baselines never count.
rows=[]
for i in range(53):
    stamp=start if i<2 else start-1000
    kind='BASELINE' if i==52 else 'CHANGE'
    rows.append(f"(9301,'{kind}','192.0.2.10','configured','[\"192.0.2.9\"]','[\"192.0.2.10\"]',{stamp},{stamp})")
sql('INSERT INTO node_ip_event (node_id,kind,target,source,previous_addresses,addresses,observed_at,confirmed_at) VALUES '+','.join(rows))
owner=owner_token(9301)
first=api('node/ip-history',{'id':9301},owner)
assert first['code']==0,first
data=first['data']
assert data['summary']['todayChanges']==2 and data['summary']['weekChanges']==52,data
assert data['summary']['timezone']=='Asia/Shanghai'
assert len(data['events'])==50 and data['nextBeforeId']
second=api('node/ip-history',{'id':9301,'beforeId':data['nextBeforeId']},owner)['data']
assert len(second['events'])>=3 and second['nextBeforeId'] is None
assert not ({e['id'] for e in data['events']} & {e['id'] for e in second['events']})
for node in (9302,9303): assert api('node/ip-history',{'id':node},owner)['code']==403
assert api('node/ip-history',{'id':9302},admin)['code']==0
summaries=api('node/ip-history/summaries',{},owner)
assert summaries['code']==0 and {s['nodeId'] for s in summaries['data']}=={9301}
assert 'secret' not in json.dumps(first).lower() and 'token' not in json.dumps(summaries).lower()
sql('UPDATE user SET status=0 WHERE id=9301')
assert api('node/ip-history',{'id':9301},owner)['code']!=0
assert api('node/ip-history/summaries',{},owner)['code']!=0
print('IP history MySQL pagination, Shanghai day boundary, baseline exclusion and tenant isolation passed')
