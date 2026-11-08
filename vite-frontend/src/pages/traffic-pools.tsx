import {useEffect,useState} from 'react';
import {Button} from '@heroui/button';
import {Modal,ModalContent,ModalHeader,ModalBody,ModalFooter} from '@heroui/modal';
import {Spinner} from '@heroui/spinner';
import toast from 'react-hot-toast';
import {ConsoleIcon} from '@/components/console-ui';
import {getNodeList,getTunnelList} from '@/api';
import {listTrafficPools,saveTrafficPool,previewPoolDns,adoptPoolDns,getPoolEvents,type TrafficPool,type PoolInput} from '@/api/traffic-pools';
import {useNodeGroups} from '@/components/node-groups';
import {JwtUtil} from '@/utils/jwt';
import './traffic-pools.css';

const GB=1_000_000_000;
const format=(bytes:number)=>bytes>=1e12?`${(bytes/1e12).toFixed(2)} TB`:`${(bytes/GB).toFixed(1)} GB`;
const time=(value:number)=>value?new Date(value).toLocaleString('zh-CN'):'等待首次检查';
const status:Record<string,string>={disabled:'自动解析未启用',waiting_checks:'等待健康检查',syncing:'正在分批同步',synced:'解析已同步',all_unhealthy_held:'全部异常，保留现有解析',dns_error:'解析同步异常',healthy:'可用',confirming:'检查确认中',waiting:'等待检查',waiting_rules:'等待规则上报确认',maintenance:'维护中',exhausted:'额度已用尽',missing:'节点已删除'};
const empty=():PoolInput=>({id:'',name:'',revision:0,mode:'individual',direction:'both',quotaBytes:0,resetDay:1,domain:'',zoneId:'',token:'',dnsEnabled:false,dnsAcknowledgement:'',probePort:10000,failureThreshold:3,recoveryThreshold:2,ttl:60,keepLastAddress:true,members:[],tunnelIds:[]});
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
 if(!admin)return <div className="pool-page"><h1>无权访问</h1><p>节点流量池仅管理员可见。</p></div>;
 const used=pools.reduce((sum,p)=>sum+p.usedBytes,0),total=pools.reduce((sum,p)=>sum+p.totalBytes,0);
 const field=(label:string,key:keyof PoolInput,type='text',hint?:string)=><label className="pool-field">{label}<input type={type} value={String(form?.[key]??'')} onChange={e=>setForm(f=>f?{...f,[key]:type==='number'?Number(e.target.value):e.target.value}:f)}/>{hint&&<small>{hint}</small>}</label>;
 return <div className="pool-page">
  <div className="pool-hero"><div><div className="pool-eyebrow">NODE TRAFFIC POOLS</div><h1>节点流量池管理</h1><p className="pool-muted mt-2">每个池使用一个入口域名，池内节点共用。开启自动解析后，入口随可用节点更新。</p></div><Button color="primary" onPress={()=>edit()} startContent={<ConsoleIcon name="plus"/>}>新建流量池</Button></div>
  <div className="pool-stats"><div className="pool-stat"><span>已建流量池</span><strong>{pools.length}</strong></div><div className="pool-stat"><span>本周期已用 · 面板估算</span><strong>{format(used)}</strong></div><div className="pool-stat"><span>已设置总额度</span><strong>{format(total)}</strong></div></div>
  {error&&<div className="pool-alert" role="alert">{error}<Button size="sm" variant="light" onPress={()=>void reload()}>重新加载</Button></div>}
  {loading?<div className="pool-empty"><Spinner/><p>正在加载流量池…</p></div>:!pools.length?<div className="pool-empty"><ConsoleIcon name="node" size={36}/><h2 className="text-lg font-semibold my-3">让多台服务器成为一个入口</h2><p className="pool-muted mb-5">选择成员、绑定隧道，再为入口域名开启自动增删解析。</p><Button color="primary" onPress={()=>edit()}>创建第一个流量池</Button></div>:<div className="pool-layout">
   <aside className="pool-list" aria-label="流量池列表">{pools.map(p=><button className={`pool-pick ${selected===p.id?'active':''}`} key={p.id} onClick={()=>setSelected(p.id)}><strong>{p.name}</strong><small>{p.members.length} 台成员 · {p.members.filter(m=>m.status==='healthy').length} 台可用</small><div className="pool-meter"><i style={{width:`${p.totalBytes?Math.min(100,p.usedBytes/p.totalBytes*100):0}%`}}/></div><small>{p.domain||'尚未绑定入口域名'}</small></button>)}</aside>
   {pool&&<main><section className="pool-card"><div className="pool-card-head"><div><h2>{pool.name}</h2><p className="pool-muted">{pool.mode==='shared'?'共享总额度':'各机独立额度汇总'} · 每月 {pool.resetDay} 日重置 · {pool.direction==='both'?'双向计量':pool.direction==='out'?'仅发送':'仅接收'}</p></div><Button size="sm" variant="bordered" onPress={()=>edit(pool)} startContent={<ConsoleIcon name="edit" size={15}/>}>管理</Button></div>
    <div className="pool-entry" role="group" aria-label="本池入口域名"><div><span className="pool-muted">本池入口域名 · 全池共用</span>{pool.domain?<code>{pool.domain}</code>:<strong>尚未配置</strong>}</div><span className={`pool-pill ${pool.dnsStatus==='synced'?'':'warn'}`}>{pool.domain?(status[pool.dnsStatus]||pool.dnsStatus):'待配置域名'}</span><Button size="sm" variant="flat" onPress={()=>pool.domain?setTab('dns'):edit(pool)}>{pool.domain?'查看入口解析':'配置入口域名'}</Button></div>
    <div className="pool-stats"><div className="pool-stat"><span>本周期已用</span><strong>{format(pool.usedBytes)}</strong></div><div className="pool-stat"><span>额度内剩余</span><strong>{pool.totalBytes?format(pool.remainingBytes):'未设额度'}</strong></div><div className="pool-stat"><span>入口候选 IP</span><strong>{pool.desiredAddresses.length}</strong></div></div>
    <div className="pool-note">用量来自服务器累计网络计数，加入时建立基线；允许校准已用值。计数重置、离线跨周期及服务商计费差异会影响估算，不能替代服务商账单。额度只控制解析候选，不会强制切断现有连接。</div>
   </section><div className="pool-tabs">{[['overview','成员与状态'],['dns','入口解析'],['events','操作记录']].map(([key,label])=><Button size="sm" key={key} color={tab===key?'primary':'default'} variant={tab===key?'flat':'light'} onPress={()=>setTab(key)}>{label}</Button>)}</div>
   {tab==='overview'&&<section className="pool-card"><div className="pool-card-head"><h2>成员服务器</h2><small className="pool-muted">绑定 {pool.tunnelIds.length} 条隧道</small></div>{pool.members.map(m=><div className="pool-node" key={m.nodeId}><div><strong>{m.name}</strong><p className="pool-muted">{format(m.usedBytes)} / {m.quotaBytes?format(m.quotaBytes):'未设额度'}</p></div><div>{m.addresses.length?m.addresses.map(a=><div key={a.ip}><code>{a.ip}</code><small className="pool-muted"> · {a.eligible?'可发布':`确认中 ${a.successes}/${pool.recoveryThreshold}`}</small></div>):<small className="pool-muted">等待地址观测</small>}</div><span className={`pool-pill ${m.status==='healthy'?'':'warn'}`}>{status[m.status]||m.status}</span></div>)}{!pool.members.length&&<p className="pool-muted">尚未选择成员，点击管理添加。</p>}</section>}
   {tab==='dns'&&<section className="pool-card" aria-label="本池入口解析">
    <div className="pool-card-head"><div><h2>入口解析</h2><p className="pool-muted">Cloudflare · A / AAAA · 仅 DNS</p></div></div>
    <div className="pool-note">开启后，每约 20 秒检查本池节点：连续成功 {pool.recoveryThreshold} 次加入 IP，连续失败 {pool.failureThreshold} 次撤下 IP。只操作本池托管记录。DNS 缓存按 TTL 到期，已有连接继续保持。TCP 检查验证端口可连接，完整业务请结合隧道诊断。</div>
    <p className="pool-muted my-4">TCP 检查端口 {pool.probePort} · TTL {pool.ttl} 秒<br/>最后检查：{time(pool.checkedAt)}<br/>最近同步：{time(pool.lastSyncedAt)}</p>
    <h3 className="text-sm font-semibold mb-2">候选 IP</h3>
    <div className="pool-preview mb-4">{pool.desiredAddresses.length?pool.desiredAddresses.map(ip=><code key={ip}>{ip}</code>):<p className="pool-muted">暂时没有通过检查的候选 IP。</p>}</div>
    <div className="pool-row-actions"><Button variant="bordered" isDisabled={!pool.domain||!pool.hasToken||busy} onPress={()=>void loadPreview(pool)}>预览解析变化</Button><Button variant="light" onPress={()=>edit(pool)}>配置域名与健康检查</Button></div>
    {preview&&<div className="mt-5"><h3 className="font-semibold mb-2">待执行变化 · 只读预览</h3>
     {preview.blockedReason&&<div className="pool-alert">{preview.blockedReason}</div>}
     {preview.canAdopt&&<Button size="sm" variant="bordered" isDisabled={busy} onPress={()=>void adoptPreview(pool,preview)}>确认接管匹配成员的现有记录</Button>}
     {preview.changes.length?preview.changes.map((c,i)=><p key={i} className="pool-muted">{c.action==='add'?'新增':'撤下'} <code>{c.ip}</code></p>):<p className="pool-muted">当前无需更改。全部异常保护开启时，会保留已有解析并提示异常。</p>}
    </div>}
   </section>}
   {tab==='events'&&<section className="pool-card"><h2 className="mb-4">最近操作</h2>{eventRows.length?eventRows.map((e,i)=><div className="pool-event" key={i}><time>{time(e.happened_at)}</time><span>{e.detail}</span></div>):<p className="pool-muted">暂无操作记录</p>}</section>}
   </main>}
  </div>}
  <Modal isOpen={!!form} onClose={()=>!busy&&setForm(null)} size="4xl" scrollBehavior="inside" isDismissable={!busy}><ModalContent><ModalHeader>{form?.id?'管理流量池':'新建流量池'}</ModalHeader><ModalBody>{form&&<>
   <div className="pool-form-grid">{field('流量池名称','name')}{field('本池入口域名','domain','text','每池一个专用域名，例如 pool.example.com。可稍后配置。')}
    <label className="pool-field">额度模式<select value={form.mode} onChange={e=>setForm({...form,mode:e.target.value as PoolInput['mode']})}><option value="individual">每台独立额度，统一汇总</option><option value="shared">服务商共享一个总额度</option></select></label>
    <label className="pool-field">计量方向<select value={form.direction} onChange={e=>setForm({...form,direction:e.target.value as PoolInput['direction']})}><option value="both">接收 + 发送</option><option value="out">仅发送</option><option value="in">仅接收</option></select></label>
    {field('每月重置日（1–31）','resetDay','number','短月份使用该月最后一天，时区为北京时间')}
    {form.mode==='shared'&&<label className="pool-field">共享总额度（GB）<input type="number" min="0" value={form.quotaBytes/GB} onChange={e=>setForm({...form,quotaBytes:Math.round(Number(e.target.value)*GB)})}/><small>1 GB = 1,000,000,000 字节；0 为未设置。</small></label>}
   </div><h3 className="font-semibold mt-4">成员与额度</h3>
   <div className="pool-row-actions"><select className="console-filter-select" aria-label="按分组选择池成员" value={memberGroup} onChange={e=>setMemberGroup(e.target.value)}><option value="all">全部节点</option>{groups.map(g=><option value={g.id} key={g.id}>{g.name}</option>)}</select><Button size="sm" variant="flat" onPress={()=>{const ids=memberGroup==='all'?nodes.map(n=>n.id):groups.find(g=>g.id===memberGroup)?.nodeIds||[];setForm({...form,members:[...form.members,...ids.filter(id=>!form.members.some(m=>m.nodeId===id)).map(nodeId=>({nodeId,enabled:true,quotaBytes:0}))]});}}>加入该组全部节点</Button></div>
   <div className="pool-muted">节点 / 参与解析 / 额度（GB）/ 校准已用（GB）；校准留空保留自动累计，用量和旧连接会保留。</div>
   {nodes.filter(n=>memberGroup==='all'||groups.find(g=>g.id===memberGroup)?.nodeIds.includes(n.id)).map(n=>{const m=form.members.find(m=>m.nodeId===n.id);return <div className="pool-form-member" key={n.id}><input aria-label={`将 ${n.name} 加入池`} type="checkbox" checked={!!m} onChange={e=>{if(!e.target.checked&&form.id&&pool?.members.some(x=>x.nodeId===n.id)){toast('已有成员请切换维护模式');return;}setForm({...form,members:e.target.checked?[...form.members,{nodeId:n.id,enabled:true,quotaBytes:0}]:form.members.filter(x=>x.nodeId!==n.id)});}}/><span>{n.name}</span><label><input type="checkbox" disabled={!m} checked={m?.enabled??false} onChange={e=>setForm({...form,members:form.members.map(x=>x.nodeId===n.id?{...x,enabled:e.target.checked}:x)})}/> 参与</label><input aria-label={`${n.name} 的额度 GB`} type="number" min="0" disabled={!m} value={(m?.quotaBytes||0)/GB} onChange={e=>setForm({...form,members:form.members.map(x=>x.nodeId===n.id?{...x,quotaBytes:Math.round(Number(e.target.value)*GB)}:x)})}/><input aria-label={`${n.name} 的已用校准 GB`} type="number" min="0" disabled={!m} placeholder="不校准" value={m?.usedBytes===undefined?'':m.usedBytes/GB} onChange={e=>setForm({...form,members:form.members.map(x=>x.nodeId===n.id?{...x,usedBytes:e.target.value===''?undefined:Math.round(Number(e.target.value)*GB)}:x)})}/></div>;})}
   <h3 className="font-semibold mt-3">关联隧道</h3><p className="pool-muted">池成员会加入这些隧道的入口，现有入口须全部保留。新增成员在实际规则清单上报确认前不会加入 DNS，旧 Agent 的周期清单可能需要等待约 10 分钟。</p>
   <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">{tunnels.map(t=><label key={t.id} className="pool-checkbox"><input type="checkbox" checked={form.tunnelIds.includes(t.id)} onChange={e=>setForm({...form,tunnelIds:e.target.checked?[...form.tunnelIds,t.id]:form.tunnelIds.filter(id=>id!==t.id)})}/>{t.name}</label>)}</div>
   <h3 className="font-semibold mt-4">本池自动解析</h3><div className="pool-form-grid">{field('Cloudflare Zone ID','zoneId')}{field(form.id?'API Token（留空保留）':'Cloudflare API Token','token','password','仅保存在面板；需要该 Zone 的 DNS 编辑权限')}{field('TCP 健康检查端口','probePort','number','填写可从面板连接的实际转发端口')}{field('连续失败次数','failureThreshold','number')}{field('连续恢复次数','recoveryThreshold','number')}{field('DNS TTL（至少 60 秒）','ttl','number')}</div>
   <label className="pool-checkbox mt-3"><input type="checkbox" checked={form.keepLastAddress} onChange={e=>setForm({...form,keepLastAddress:e.target.checked})}/>全部成员异常时保留现有解析并告警，防止面板网络抖动导致清空整个域名</label>
   <label className="pool-checkbox"><input type="checkbox" checked={form.dnsEnabled} onChange={e=>setForm({...form,dnsEnabled:e.target.checked,dnsAcknowledgement:e.target.checked?form.domain.trim().toLowerCase():''})}/>允许自动管理此池入口域名的 A / AAAA 记录（默认关闭）</label>
   {form.dnsEnabled&&<label className="pool-field">确认自动管理的域名<input value={form.dnsAcknowledgement} onChange={e=>setForm({...form,dnsAcknowledgement:e.target.value})}/></label>}
   <div className="pool-note">关闭自动解析会停止写入并保留现有解析。只管理本池托管的记录。维护模式会撤下该节点的候选 IP，既有连接继续运行。</div>
  </>}</ModalBody><ModalFooter><Button variant="light" isDisabled={busy} onPress={()=>setForm(null)}>取消</Button><Button color="primary" isLoading={busy} isDisabled={!form?.name.trim()} onPress={()=>void submit()}>保存流量池</Button></ModalFooter></ModalContent></Modal>
 </div>;
}
