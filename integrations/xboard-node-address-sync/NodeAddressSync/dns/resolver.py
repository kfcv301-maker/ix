"""Central authoritative DNS resolver. No node access, IMDS, HTTP report or credentials."""
import asyncio, hashlib, ipaddress, json, pathlib, re, sys, time

BASE = pathlib.Path(__file__).resolve().parent
wheel = BASE / 'dnspython-2.8.0-py3-none-any.whl'
if wheel.exists():
    expected=(BASE/'dnspython.sha256').read_text().strip()
    if hashlib.sha256(wheel.read_bytes()).hexdigest()!=expected:
        raise SystemExit('DNS dependency checksum mismatch')
    sys.path.insert(0,str(wheel))
import dns.asyncquery, dns.asyncresolver, dns.flags, dns.message, dns.name, dns.rcode, dns.rdatatype

CDN = tuple(ipaddress.ip_network(x) for x in json.loads((BASE/'cloudflare-networks.json').read_text()))
CDN_NAMES = ('cloudfront.net','fastly.net','fastlylb.net','edgekey.net','edgesuite.net','akamaiedge.net','azureedge.net','cdn.cloudflare.net')

class ResolutionError(Exception): pass

def canonical(name):
    value=str(name).rstrip('.').lower()
    if not re.fullmatch(r'(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}',value):
        raise ResolutionError('invalid_domain')
    return value

def safe_address(value):
    ip=ipaddress.ip_address(value)
    if not ip.is_global or ip.is_multicast or any(ip in net for net in CDN):
        raise ResolutionError('nonpublic_or_proxy')
    if ip.version==6 and (ip not in ipaddress.ip_network('2000::/3') or any(ip in ipaddress.ip_network(n) for n in ('2001::/23','2001:db8::/32','2002::/16','3fff::/20'))):
        raise ResolutionError('nonpublic_or_proxy')
    return ip.compressed

class AuthoritativeResolver:
    def __init__(self):
        self.bootstrap=dns.asyncresolver.Resolver()
        self.bootstrap.timeout=1.2
        self.bootstrap.lifetime=2.5
        self.delegations={}
        self.discovery={}
        self.sem=asyncio.Semaphore(8)

    async def authorities(self,name):
        # Cache delegation only. Final A/AAAA and every CNAME are always queried afresh.
        for zone,(expires,servers) in list(self.delegations.items()):
            if time.monotonic()<expires and (name==zone or name.endswith('.'+zone)):
                # Only reuse exact query-name discovery, to avoid masking child zones.
                if self.discovery.get(name)==zone:return servers
        zone=await asyncio.wait_for(dns.asyncresolver.zone_for_name(name,resolver=self.bootstrap),2.5)
        answer=await self.bootstrap.resolve(zone,'NS',lifetime=2.5,search=False)
        ns_names=sorted({str(x.target).rstrip('.') for x in answer})
        if not ns_names:raise ResolutionError('no_authority')
        async def locate(ns):
            values=await asyncio.gather(*[self.bootstrap.resolve(ns,t,lifetime=2,search=False) for t in ('A','AAAA')],return_exceptions=True)
            ips=[]
            for v in values:
                if not isinstance(v,Exception):
                    ips.extend(str(x) for x in v if ipaddress.ip_address(str(x)).is_global)
            return (ns,ips[:4])
        found=await asyncio.gather(*(locate(n) for n in ns_names[:3]))
        # Distinct NS identities are required, not two IPs of one server.
        servers=[(n,ips) for n,ips in found if ips]
        if len(servers)<min(2,len(ns_names)):raise ResolutionError('authority_unavailable')
        key=str(zone).rstrip('.');self.discovery[name]=key
        self.delegations[key]=(time.monotonic()+min(300,max(1,answer.rrset.ttl)),servers)
        return servers

    async def wire(self,name,kind,ip,port=53):
        q=dns.message.make_query(name,kind,use_edns=0,payload=1232)
        q.flags &= ~dns.flags.RD
        response,_=await dns.asyncquery.udp_with_fallback(q,ip,timeout=1.2,port=port)
        if not response.flags & dns.flags.AA:raise ResolutionError('not_authoritative')
        if response.rcode()==dns.rcode.NXDOMAIN:raise ResolutionError('nxdomain')
        if response.rcode()!=dns.rcode.NOERROR:raise ResolutionError('authority_failure')
        owner=dns.name.from_text(name)
        cname=[];addresses=[];ttls=[]
        for rrset in response.answer:
            if rrset.name!=owner:continue
            if rrset.rdtype==dns.rdatatype.CNAME:
                cname.extend(canonical(rr.target) for rr in rrset);ttls.append(rrset.ttl)
            elif rrset.rdtype==dns.rdatatype.from_text(kind):
                addresses.extend(safe_address(str(rr)) for rr in rrset);ttls.append(rrset.ttl)
        if len(set(cname))>1 or (cname and addresses):raise ResolutionError('ambiguous_cname')
        if not cname and not addresses:raise ResolutionError('empty_answer')
        if len(addresses)>64:raise ResolutionError('too_many_addresses')
        return {'cname':sorted(set(cname)), 'addresses':sorted(set(addresses),key=lambda x:int(ipaddress.ip_address(x))), 'ttl':min(ttls)}

    async def server_answer(self,name,kind,server):
        ns,ips=server
        for ip in ips[:2]:
            try:return await self.wire(name,kind,ip)
            except ResolutionError:raise
            except Exception:continue
        raise ResolutionError('authority_unavailable')

    async def lookup(self,name,kind):
        current=canonical(name);chain=[];seen=set()
        for _ in range(8):
            if current in seen:raise ResolutionError('cname_loop')
            if any(current==n or current.endswith('.'+n) for n in CDN_NAMES):raise ResolutionError('cdn_cname')
            seen.add(current)
            servers=await self.authorities(current)
            results=await asyncio.gather(*(self.server_answer(current,kind,s) for s in servers),return_exceptions=True)
            good=[r for r in results if isinstance(r,dict)]
            failures=[r for r in results if isinstance(r,Exception)]
            # Never pick a fast but stale authoritative answer while another disagrees.
            signatures={json.dumps([r['cname'],r['addresses']]) for r in good}
            if len(signatures)>1:raise ResolutionError('authority_disagreement')
            if any(isinstance(e,ResolutionError) and str(e) in ('nxdomain','empty_answer','nonpublic_or_proxy','cdn_cname') for e in failures):
                raise ResolutionError('authority_disagreement' if good else str(failures[0]))
            if len(good)<min(2,len(servers)):
                raise ResolutionError(str(failures[0]) if failures and isinstance(failures[0],ResolutionError) else 'authority_unavailable')
            result=good[0]
            chain.append({'name':current,'ttl':min(r['ttl'] for r in good),'authorities':[s[0] for s in servers],'cname':result['cname']})
            if result['cname']:
                current=result['cname'][0];continue
            return {'addresses':result['addresses'],'chain':chain,'authoritative':True}
        raise ResolutionError('cname_too_deep')

    async def resolve_job(self,job):
        result={k:job[k] for k in ('id','revision','source_domain','record_type')}
        async with self.sem:
            try:
                if job['record_type'] not in ('A','AAAA'):raise ResolutionError('invalid_type')
                result.update(await asyncio.wait_for(self.lookup(job['source_domain'],job['record_type']),6))
            except Exception as e:
                result['error']=str(e) if isinstance(e,ResolutionError) else ('timeout' if isinstance(e,asyncio.TimeoutError) else 'resolution_failed')
                # Refresh delegation following an error; never fall back to cached A/AAAA.
                self.discovery.pop(job['source_domain'],None)
            result['observed_at_ms']=int(time.time()*1000)
        return result

    async def batch(self,jobs):
        if len(jobs)>16:raise ResolutionError('too_many_bindings')
        # Coalesce bindings sharing the same name/type within one cycle.
        groups={}
        for job in jobs:groups.setdefault((job['source_domain'],job['record_type']),[]).append(job)
        answers=await asyncio.gather(*(self.resolve_job(group[0]) for group in groups.values()))
        out=[]
        for group,answer in zip(groups.values(),answers):
            for job in group:out.append(dict(answer,id=job['id'],revision=job['revision']))
        return out

async def main():
    from probe import Prober
    resolver=AuthoritativeResolver()
    prober=Prober()
    while True:
        line=await asyncio.to_thread(sys.stdin.readline)
        if not line:return
        try:
            jobs=json.loads(line)
            if isinstance(jobs,dict) and jobs.get('operation')=='probe':
                out=await prober.batch(jobs['targets'])
            elif isinstance(jobs,list):out=await resolver.batch(jobs)
            else:raise ValueError()
            print(json.dumps(out,separators=(',',':')),flush=True)
        except Exception:
            print(json.dumps({'error':'invalid_batch'}),flush=True)

if __name__=='__main__':asyncio.run(main())
