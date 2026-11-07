import { useEffect, useState } from 'react';
import { Button } from '@heroui/button';
import { getAirportObserverTargets, installAirportObserver, type AirportObserverTargets } from '@/api';

export function AirportObserverSetup({ nodeId }: { nodeId: number }) {
  const [data, setData] = useState<AirportObserverTargets | null>(null), [host, setHost] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  useEffect(() => {
    let active = true, fetching = false;
    async function refresh() {
      if (fetching) return; fetching = true;
      try { const r = await getAirportObserverTargets(nodeId); if (active) { if (r.code === 0) setData(r.data); else setError(r.msg || '读取失败'); } }
      catch { if (active) setError('读取启用状态失败'); } finally { fetching = false; }
    }
    void refresh(); const timer = window.setInterval(() => void refresh(), 3000);
    return () => { active = false; window.clearInterval(timer); };
  }, [nodeId]);
  async function install() {
    if (!host || busy || data?.status === 'running') return; setBusy(true); setError('');
    try { const r = await installAirportObserver(nodeId, Number(host)); if (r.code !== 0) setError(r.msg || '启用失败'); else setData(d => d && { ...d, status: r.data }); }
    catch { setError('启用请求失败，请核对状态'); } finally { setBusy(false); }
  }
  const statuses: Record<string, string> = { idle: '等待操作', running: '正在在线准备上报组件', ready: '组件已就绪，等待面板收到首报', failed: '启用失败，请检查 SSH、节点身份、Python3 和配置权限', fingerprint_changed: 'SSH 主机身份变化，请先在 VPS 管理核对并确认' };
  return <details className="rounded-xl border border-default-200 p-3 text-sm">
    <summary className="cursor-pointer">旧节点：在线启用上报</summary>
    <p className="my-2 text-default-500">新安装节点已预置，无需操作。旧节点选择已录入 VPS 管理的对应服务器，只补齐上报组件，不重装、不重启转发核心。执行前会核对服务器内的节点身份。</p>
    <select aria-label="上报组件目标服务器" className="w-full rounded-xl bg-default-100 p-3" value={host} disabled={busy || data?.status === 'running'} onChange={e => setHost(e.target.value)}><option value="">请选择对应服务器</option>{data?.hosts.map(h => <option key={h.id} value={h.id}>{h.name} · {h.host}</option>)}</select>
    {data?.hosts.length === 0 && <p className="mt-2 text-default-500">请先在 VPS 管理录入对应服务器并确认 SSH 指纹。</p>}
    <p role="status" className="mt-2">{statuses[data?.status || 'idle'] || '等待检查'}</p>
    {error && <p role="alert" className="mt-2 text-danger">{error}</p>}
    <Button className="mt-3" size="sm" color="primary" isDisabled={!host || busy || data?.status === 'running'} isLoading={busy || data?.status === 'running'} onPress={() => void install()}>在线启用上报</Button>
  </details>;
}
