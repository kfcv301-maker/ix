"""Bounded transport checks before changing a node's connection address.

TLS checks use the node's existing SNI and certificate policy. QUIC checks use
RFC 9000 version negotiation, not a TCP surrogate or an authenticated proxy test.
"""
import asyncio,ipaddress,json,os,socket,ssl,struct,time

def version_negotiation(data,dcid,scid):
    if len(data)<7 or not data[0]&0x80 or data[1:5]!=b'\0'*4:return False
    pos=5;size=data[pos];pos+=1
    if size!=len(scid) or data[pos:pos+size]!=scid:return False
    pos+=size
    if pos>=len(data):return False
    size=data[pos];pos+=1
    if size!=len(dcid) or data[pos:pos+size]!=dcid:return False
    pos+=size;versions=data[pos:]
    return len(versions)>=4 and len(versions)%4==0 and any(
        struct.unpack('!I',versions[i:i+4])[0]in(1,0x6b3343cf)for i in range(0,len(versions),4))

class Prober:
    def __init__(self):self.sem=asyncio.Semaphore(32)

    async def tcp(self,target):
        opts={}
        if target['mode']=='tls':
            context=ssl.create_default_context()
            if target.get('allow_insecure'):
                context.check_hostname=False;context.verify_mode=ssl.CERT_NONE
            opts={'ssl':context,'server_hostname':target.get('sni')or target['ip'],'ssl_handshake_timeout':2}
        _,writer=await asyncio.open_connection(target['ip'],target['port'],**opts)
        try:
            if target['mode']=='tls' and writer.get_extra_info('ssl_object') is None:
                raise RuntimeError('tls_missing')
        finally:
            writer.close()
            try:await asyncio.wait_for(writer.wait_closed(),.2)
            except Exception:pass

    async def quic(self,target):
        dcid=os.urandom(8);scid=os.urandom(8)
        # A reserved version solicits negotiation without user credentials or traffic.
        payload=b'\xc0'+struct.pack('!I',0x0a1a2a3a)+bytes([len(dcid)])+dcid+bytes([len(scid)])+scid
        payload+=b'\0'*(1200-len(payload))
        family=socket.AF_INET6 if ':'in target['ip'] else socket.AF_INET
        sock=socket.socket(family,socket.SOCK_DGRAM);sock.setblocking(False)
        loop=asyncio.get_running_loop()
        try:
            await loop.sock_connect(sock,(target['ip'],target['port']))
            # Retry once within the same two-second budget for UDP loss.
            for _ in range(2):
                await loop.sock_sendall(sock,payload)
                try:data=await asyncio.wait_for(loop.sock_recv(sock,2048),.85)
                except asyncio.TimeoutError:continue
                if version_negotiation(data,dcid,scid):return
            raise RuntimeError('quic_no_valid_reply')
        finally:sock.close()

    async def target(self,target):
        result={k:target[k]for k in ('node_id','ip','mode','fingerprint')}
        started=time.monotonic()
        async with self.sem:
            try:
                address=ipaddress.ip_address(target['ip'])
                if not address.is_global or address.is_multicast or not 1<=int(target['port'])<=65535:raise ValueError()
                if target.get('unsupported') or target['mode']not in ('tcp','tls','quic'):
                    result.update(ok=False,error='probe_unsupported')
                else:
                    await asyncio.wait_for(self.quic(target) if target['mode']=='quic' else self.tcp(target),2.2)
                    result.update(ok=True)
            except asyncio.TimeoutError:result.update(ok=False,error='probe_timeout')
            except ssl.SSLCertVerificationError:result.update(ok=False,error='certificate_failed')
            except ssl.SSLError:result.update(ok=False,error='tls_handshake_failed')
            except Exception:result.update(ok=False,error='connect_failed')
        result.update(checked_at_ms=int(time.time()*1000),elapsed_ms=round((time.monotonic()-started)*1000))
        return result

    async def batch(self,targets):
        if len(targets)>128:raise ValueError('probe_capacity')
        # Coalesce identical endpoint checks without mixing node snapshot fingerprints.
        groups={}
        for target in targets:
            key=json.dumps([target.get(k)for k in ('ip','port','mode','sni','allow_insecure','unsupported')])
            groups.setdefault(key,[]).append(target)
        results=await asyncio.gather(*(self.target(group[0])for group in groups.values()))
        out=[]
        for group,result in zip(groups.values(),results):
            for target in group:out.append(dict(result,node_id=target['node_id'],fingerprint=target['fingerprint']))
        return out
