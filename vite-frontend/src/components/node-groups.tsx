import {useEffect,useState} from 'react';
import {Button} from '@heroui/button';
import {Input} from '@heroui/input';
import {Modal,ModalContent,ModalHeader,ModalBody,ModalFooter} from '@heroui/modal';
import toast from 'react-hot-toast';
import {listNodeGroups,saveNodeGroup,deleteNodeGroup,assignNodeGroup,assignHostGroup,type NodeGroup} from '@/api/traffic-pools';

export function useNodeGroups(enabled:boolean) {
  const [groups,setGroups]=useState<NodeGroup[]>([]);
  const [error,setError]=useState('');
  const reload=async()=>{if(!enabled)return;const r=await listNodeGroups();if(r.code===0){setGroups(r.data);setError('');}else setError(r.msg||'分组暂时无法加载');};
  useEffect(()=>{void reload();},[enabled]);
  return {groups,reload,error};
}
export function NodeGroupsModal({open,onClose,groups,onChanged,nodes,kind='node'}:{open:boolean;onClose:()=>void;groups:NodeGroup[];onChanged:()=>Promise<void>;nodes:{id:number;name:string}[];kind?:'node'|'host'}) {
  const [name,setName]=useState(''),[editing,setEditing]=useState<NodeGroup|null>(null),[busy,setBusy]=useState(false);
  const [selection,setSelection]=useState<number[]>([]),[target,setTarget]=useState('');
  const run=async(call:()=>Promise<{code:number;msg:string}>)=>{setBusy(true);try{const r=await call();if(r.code!==0)throw new Error(r.msg);await onChanged();toast.success('分组已更新');return true;}catch(e){toast.error(e instanceof Error?e.message:'操作未完成');return false;}finally{setBusy(false);}};
  return <Modal isOpen={open} onClose={onClose} size="2xl" scrollBehavior="inside"><ModalContent><ModalHeader>{kind==='host'?'服务器分组':'节点分组'}</ModalHeader><ModalBody>
    <p className="text-sm text-default-500">按地区、供应商或用途整理节点。分组调整保留服务器、转发规则和连接。</p>
    <div className="flex gap-2"><Input label={editing?'修改分组名称':'新分组名称'} value={name} onValueChange={setName} maxLength={100}/><Button className="self-end" color="primary" isLoading={busy} isDisabled={!name.trim()} onPress={async()=>{if(await run(()=>saveNodeGroup({id:editing?.id||'',name,revision:editing?.revision||0}))){setName('');setEditing(null);}}}>{editing?'保存':'创建'}</Button>{editing&&<Button onPress={()=>{setEditing(null);setName('');}}>取消</Button>}</div>
    <div className="divide-y divide-divider">{groups.map(g=><div className="flex items-center justify-between gap-2 py-2" key={g.id}><span>{g.name}<small className="ml-2 text-default-500">{(kind==='host'?g.hostIds:g.nodeIds)?.length||0} 个成员</small></span><div className="flex gap-1"><Button size="sm" variant="light" onPress={()=>{setEditing(g);setName(g.name);}}>重命名</Button><Button size="sm" color="danger" variant="light" isDisabled={busy} onPress={async()=>{if(window.confirm(`删除分组“${g.name}”？成员会回到未分组，服务器与转发规则会保留。`))await run(()=>deleteNodeGroup(g.id,g.revision));}}>删除</Button></div></div>)}</div>
    <h3 className="mt-3 font-semibold">批量归组</h3>
    <select className="console-filter-select" aria-label="目标节点分组" value={target} onChange={e=>setTarget(e.target.value)}><option value="">未分组</option>{groups.map(g=><option key={g.id} value={g.id}>{g.name}</option>)}</select>
    <div className="grid max-h-64 grid-cols-1 gap-2 overflow-y-auto sm:grid-cols-2">{nodes.map(n=><label key={n.id} className="flex items-center gap-2 rounded-lg border border-divider px-3 py-2 text-sm"><input type="checkbox" checked={selection.includes(n.id)} onChange={e=>setSelection(old=>e.target.checked?[...old,n.id]:old.filter(id=>id!==n.id))}/><span className="truncate">{n.name}</span></label>)}</div>
  </ModalBody><ModalFooter><Button variant="light" onPress={onClose}>关闭</Button><Button color="primary" isDisabled={!selection.length||busy} onPress={async()=>{if(await run(()=>(kind==='host'?assignHostGroup:assignNodeGroup)(target,selection)))setSelection([]);}}>将 {selection.length} 个成员移入分组</Button></ModalFooter></ModalContent></Modal>;
}
