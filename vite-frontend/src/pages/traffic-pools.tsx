import {useEffect,useState} from 'react';
import {Button} from '@heroui/button';
import {Modal,ModalContent,ModalHeader,ModalBody,ModalFooter} from '@heroui/modal';
import {Spinner} from '@heroui/spinner';
import toast from 'react-hot-toast';
import {ConsoleIcon} from '@/components/console-ui';
import {getNodeList,getTunnelList} from '@/api';
import {listTrafficPools,saveTrafficPool,previewPoolDns,adoptPoolDns,getPoolEvents,type TrafficPool,type PoolInput,type PoolMember} from '@/api/traffic-pools';
import {useNodeGroups} from '@/components/node-groups';
import {JwtUtil} from '@/utils/jwt';
import './traffic-pools.css';

const time=(value:number)=>value?new Date(value).toLocaleString('zh-CN'):'等待首次检查';
const dnsStatus:Record<string,string>={disabled:'自动解析已关闭',waiting_checks:'同步中',syncing:'同步中',synced:'解析已同步',dns_error:'解析同步失败'};
const memberIp=(m:PoolMember)=>m.serverIp??m.addresses.find(a=>a.eligible)?.ip??m.addresses[0]?.ip??'';
function memberStatus(m:PoolMember){
 if(!m.enabled||m.status==='missing')return '停用';
 const address=m.addresses.find(a=>a.ip===memberIp(m));
 if(address?.eligible)return '在线';
 if(['offline','report_stale','ip_missing'].includes(m.status)||address?.failures)return '离线';
 return '检查中';
}
const onlineCount=(p:TrafficPool)=>p.members.filter(m=>memberStatus(m)==='在线').length;
const empty=():PoolInput=>({id:'',name:'',revision:0,mode:'individual',direction:'both',quotaBytes:0,resetDay:1,domain:'',zoneId:'',token:'',dnsEnabled:false,dnsAcknowledgement:'',probePort:10000,failureThreshold:3,recoveryThreshold:2,ttl:60,keepLastAddress:false,members:[],tunnelIds:[]});
type NodeOption={id:number;name:string};type TunnelOption={id:number;name:string;entryNodeIds?:number[];inNodeId:number};
type DnsPreview=Awaited<ReturnType<typeof previewPoolDns>>['data'];

export default function TrafficPoolsPage(){
 const admin=JwtUtil.getRoleIdFromToken()===0;
 const [pools,setPools]=useState<TrafficPool[]>([]),[nodes,setNodes]=useState<NodeOption[]>([]),[tunnels,setTunnels]=useState<TunnelOption[]>([]);
 const [selected,setSelected]=useState(''),[loading,setLoading]=useState(true),[error,setError]=useState(''),[busy,setBusy]=useState(false);
 const [form,setForm]=useState<PoolInput|null>(null),[tab,setTab]=useState('overview'),[memberGroup,setMemberGroup]=useState('all');
 const [events,setEvents]=useState<{poolId:string;rows:{happened_at:number;kind:string;detail:string}[]}|null>(null);
 const [dnsPreview,setDnsPreview]=useState<{poolId:string;data:DnsPreview}|null>(null);
 const {groups}=useNodeGroups(admin);
 const reload=async()=>{
  if(!admin){setLoading(false);return;}
  try{const [p,n,t]=await Promise.all([listTrafficPools(),getNodeList(),getTunnelList()]);if(p.code!==0||n.code!==0||t.code!==0)throw new Error(p.code!==0?p.msg:n.code!==0?n.msg:t.msg);setPools(p.data);setNodes(n.data);setTunnels(t.data);setSelected(old=>p.data.some(x=>x.id===old)?old:p.data[0]?.id||'');setError('');}catch(e){setError(e instanceof Error?e.message:'数据暂时无法加载');}finally{setLoading(false);}
 };
 useEffect(()=>{void reload();if(!admin)return;const timer=window.setInterval(()=>void reload(),20000);return()=>window.clearInterval(timer);},[admin]);
 const pool=pools.find(p=>p.id===selected);
 const preview=pool&&dnsPreview?.poolId===pool.id&&dnsPreview.data.revision===pool.revision?dnsPreview.data:null;
 const eventRows=events?.poolId===selected?events.rows:[];
 useEffect(()=>{setDnsPreview(null);},[selected,tab]);
 useEffect(()=>{let active=true;if(pool&&tab==='events')void getPoolEvents(pool.id).then(r=>{if(!active)return;if(r.code===0)setEvents({poolId:pool.id,rows:r.data});else toast.error(r.msg);});return()=>{active=false;};},[selected,tab,pool?.checkedAt]);
 const edit=(p?:TrafficPool)=>{setMemberGroup('all');setForm(p?{...p,token:'',dnsAcknowledgement:p.dnsEnabled?p.domain:'',members:p.members.map(m=>({nodeId:m.nodeId,enabled:m.enabled,quotaBytes:m.quotaBytes}))}:empty());};
 const submit=async()=>{
  if(!form)return;setBusy(true);
  try{const r=await saveTrafficPool(form);if(r.code!==0)throw new Error(r.msg);setSelected(r.data.id);setForm(null);toast.success('流量池已保存');await reload();}catch(e){toast.error(e instanceof Error?e.message:'保存失败');}finally{setBusy(false);}
 };
 const loadPreview=async(p:TrafficPool)=>{
  setBusy(true);
  try{const r=await previewPoolDns(p.id);if(r.code!==0)throw new Error(r.msg);setDnsPreview({poolId:p.id,data:r.data});}catch(e){toast.error(e instanceof Error?e.message:'预览失败');}finally{setBusy(false);}
 };
 const adoptPreview=async(p:TrafficPool,initial:DnsPreview)=>{
  if(!window.confirm(`将 ${initial.domain} 中与当前成员匹配的现有 A / AAAA 记录交给此池管理？请先停用该域名的其他自动更新程序。IP 和其他记录内容不会更改。`))return;
  setBusy(true);
  try{
   let current=initial;
   for(let batch=0;batch<512&&current.canAdopt;batch++){
    if(current.revision!==initial.revision)throw new Error('设置已变化，请重新预览');
    const r=await adoptPoolDns(p.id,current.revision,current.fingerprint,initial.domain);if(r.code!==0)throw new Error(r.msg);
    const next=await previewPoolDns(p.id);if(next.code!==0)throw new Error(next.msg);
    current=next.data;setDnsPreview({poolId:p.id,data:current});
   }
   if(current.canAdopt)throw new Error('部分记录已接管，请预览后继续');
   toast.success('匹配成员的记录已接管');
  }catch(e){toast.error(e instanceof Error?e.message:'接管失败');}finally{setBusy(false);}
 };
 const setParticipation=(nodeId:number,enabled:boolean)=>setForm(f=>{
  if(!f)return f;
  const existing=f.members.find(m=>m.nodeId===nodeId);
  if(enabled)return {...f,members:existing?f.members.map(m=>m.nodeId===nodeId?{...m,enabled:true}:m):[...f.members,{nodeId,enabled:true,quotaBytes:0}]};
  const saved=pools.find(p=>p.id===f.id)?.members.some(m=>m.nodeId===nodeId);
  return {...f,members:saved?f.members.map(m=>m.nodeId===nodeId?{...m,enabled:false}:m):f.members.filter(m=>m.nodeId!==nodeId)};
 });
 if(!admin)return <div className="pool-page"><h1>无权访问</h1><p>节点流量池仅管理员可见。</p></div>;
 const groupIds=groups.find(g=>g.id===memberGroup)?.nodeIds;
 const visibleNodes=memberGroup==='all'?nodes:nodes.filter(n=>groupIds?.includes(n.id));
 const field=(label:string,key:keyof PoolInput,type='text',hint?:string)=><label className="pool-field">{label}<input type={type} value={String(form?.[key]??'')} onChange={e=>setForm(f=>f?{...f,[key]:type==='number'?Number(e.target.value):e.target.value}:f)}/>{hint&&<small>{hint}</small>}</label>;
 return <div className="pool-page">
  <div className="pool-hero"><div className="pool-hero-title"><span className="pool-hero-mark" aria-hidden="true"><ConsoleIcon name="traffic-pools" size={22}/></span><div><h1>节点流量池管理</h1><p className="pool-muted">在线节点自动加入解析，离线节点自动撤下。</p></div></div><Button color="primary" onPress={()=>edit()} startContent={<ConsoleIcon name="plus"/>}>新建流量池</Button></div>
  {error&&<div className="pool-alert" role="alert">{error}<Button size="sm" variant="light" onPress={()=>void reload()}>重新加载</Button></div>}
  {loading?<div className="pool-empty"><Spinner/><p>正在加载流量池…</p></div>:!pools.length?<div className="pool-empty"><ConsoleIcon name="node" size={36}/><h2 className="text-lg font-semibold my-3">让多台服务器成为一个入口</h2><p className="pool-muted mb-5">选择成员、绑定隧道，再为入口域名开启自动增删解析。</p><Button color="primary" onPress={()=>edit()}>创建第一个流量池</Button></div>:<div className="pool-layout">
   <aside className="pool-list" aria-label="流量池列表">{pools.map(p=><button className={`pool-pick ${selected===p.id?'active':''}`} aria-pressed={selected===p.id} key={p.id} onClick={()=>setSelected(p.id)}><strong>{p.name}</strong><small>{p.domain||'尚未绑定入口域名'}</small><small className="pool-pick-count">{onlineCount(p)} 台在线</small></button>)}</aside>
   {pool&&<div><section className="pool-card"><div className="pool-card-head"><h2>{pool.name}</h2><Button size="sm" variant="bordered" onPress={()=>edit(pool)} startContent={<ConsoleIcon name="edit" size={15}/>}>设置</Button></div>
    <div className="pool-entry" role="group" aria-label="本池入口域名"><div><span className="pool-muted">入口域名</span>{pool.domain?<code>{pool.domain}</code>:<strong>尚未配置</strong>}</div><span className={`pool-pill ${pool.dnsStatus==='synced'?'':'warn'}`}>{pool.domain?(dnsStatus[pool.dnsStatus]||'同步中'):'待配置域名'}</span></div>
    <div className="pool-card-head pool-member-head"><h2>成员服务器</h2><small className="pool-muted">{onlineCount(pool)} 台在线 / 共 {pool.members.length} 台</small></div>
    {pool.members.map(m=>{const state=memberStatus(m);return <div className="pool-node" key={m.nodeId}><strong>{m.name}</strong><code>{memberIp(m)||'—'}</code><span className={`pool-pill ${state==='在线'?'':state==='停用'?'muted':state==='离线'?'offline':'warn'}`}>{state}</span></div>;})}
    {!pool.members.length&&<p className="pool-muted">还没有成员，可在设置里添加。</p>}
    <div className="pool-row-actions mt-4"><Button size="sm" variant="light" aria-expanded={tab==='dns'} onPress={()=>setTab(tab==='dns'?'overview':'dns')}>解析详情</Button><Button size="sm" variant="light" aria-expanded={tab==='events'} onPress={()=>setTab(tab==='events'?'overview':'events')}>操作记录</Button></div>
   </section>
   {tab==='dns'&&<section className="pool-card" aria-label="本池入口解析">
    <div className="pool-card-head"><h2>解析详情</h2></div>
    <p className="pool-muted mb-4">检查端口 {pool.probePort} · 最近同步：{time(pool.lastSyncedAt)}</p>
    <div className="pool-row-actions"><Button variant="bordered" isDisabled={!pool.domain||!pool.hasToken||busy} onPress={()=>void loadPreview(pool)}>预览解析变化</Button></div>
    {preview&&<div className="mt-5"><h3 className="font-semibold mb-2">待执行变化 · 只读预览</h3>
     {preview.blockedReason&&<div className="pool-alert">{preview.blockedReason}</div>}
     {preview.canAdopt&&<Button size="sm" variant="bordered" isDisabled={busy} onPress={()=>void adoptPreview(pool,preview)}>确认接管匹配成员的现有记录</Button>}
     {!preview.blockedReason&&(preview.changes.length?preview.changes.map((c,i)=><p key={i} className="pool-muted">{c.action==='add'?'新增':'撤下'} <code>{c.ip}</code></p>):<p className="pool-muted">解析无需更改。</p>)}
    </div>}
   </section>}
   {tab==='events'&&<section className="pool-card"><h2 className="mb-4">最近操作</h2>{eventRows.length?eventRows.map((e,i)=><div className="pool-event" key={i}><time>{time(e.happened_at)}</time><span>{e.detail}</span></div>):<p className="pool-muted">暂无操作记录</p>}</section>}
   </div>}
  </div>}
  <Modal isOpen={!!form} onClose={()=>!busy&&setForm(null)} size="4xl" scrollBehavior="inside" isDismissable={!busy}><ModalContent><ModalHeader>{form?.id?'管理流量池':'新建流量池'}</ModalHeader><ModalBody>{form&&<>
   <div className="pool-form-grid">{field('流量池名称','name')}{field('入口域名','domain')}</div>
   <h3 className="font-semibold mt-3">参与解析的节点</h3>
   <div className="pool-row-actions"><select className="console-filter-select" aria-label="按分组选择池成员" value={memberGroup} onChange={e=>setMemberGroup(e.target.value)}><option value="all">全部节点</option>{groups.map(g=><option value={g.id} key={g.id}>{g.name}</option>)}</select>{memberGroup!=='all'&&<Button size="sm" variant="flat" onPress={()=>setForm({...form,members:[...form.members,...visibleNodes.filter(n=>!form.members.some(m=>m.nodeId===n.id)).map(n=>({nodeId:n.id,enabled:true,quotaBytes:0}))]})}>加入该组节点</Button>}</div>
   <div className="pool-form-members">{visibleNodes.map(n=><label className="pool-form-member" key={n.id}><input type="checkbox" checked={form.members.some(m=>m.nodeId===n.id&&m.enabled)} onChange={e=>setParticipation(n.id,e.target.checked)}/><span>{n.name}</span></label>)}</div>
   <details className="pool-form-detail" open={!form.id}><summary>解析与隧道设置</summary>
    <div className="pool-form-grid mt-3">{field('Cloudflare Zone ID','zoneId')}{field(form.id?'API Token（留空保留）':'Cloudflare API Token','token','password')}{field('检查端口','probePort','number','填写节点实际提供转发的 TCP 端口')}</div>
    <label className="pool-checkbox mt-3"><input type="checkbox" checked={form.dnsEnabled} onChange={e=>setForm({...form,dnsEnabled:e.target.checked,dnsAcknowledgement:e.target.checked?form.domain.trim().toLowerCase():''})}/>开启自动解析</label>
    {form.dnsEnabled&&<label className="pool-field mt-2">确认入口域名<input value={form.dnsAcknowledgement} onChange={e=>setForm({...form,dnsAcknowledgement:e.target.value})}/></label>}
    <h3 className="font-semibold mt-3">关联隧道</h3><div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mt-2">{tunnels.map(t=><label key={t.id} className="pool-checkbox"><input type="checkbox" checked={form.tunnelIds.includes(t.id)} onChange={e=>setForm({...form,tunnelIds:e.target.checked?[...form.tunnelIds,t.id]:form.tunnelIds.filter(id=>id!==t.id)})}/>{t.name}</label>)}</div>
   </details>
  </>}</ModalBody><ModalFooter><Button variant="light" isDisabled={busy} onPress={()=>setForm(null)}>取消</Button><Button color="primary" isLoading={busy} isDisabled={!form?.name.trim()} onPress={()=>void submit()}>保存流量池</Button></ModalFooter></ModalContent></Modal>
 </div>;
}
