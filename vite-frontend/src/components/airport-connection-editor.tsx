import { useEffect, useRef, useState } from 'react';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Switch } from '@heroui/switch';
import { saveAirportConnection, checkAirportConnection, type AirportConnection } from '@/api';

const empty = { id: '', name: '', origin: '', connectionId: '', enabled: true, revision: 0, token: '' };
const statuses: Record<string, string> = {
  connected: '配对成功，已获取机场授权范围', unchecked: '已保存，尚未测试连接', disabled: '连接已停用',
  blocked_401: '配对密钥错误或已失效', blocked_403: '机场未授权此转发面板，或连接已停用',
  blocked_409: '机场授权配置存在冲突', blocked_422: '双方连接信息不匹配', blocked_destination: '机场地址解析到不允许的网络',
  blocked_redirect: '接口发生跳转，请填写最终 HTTPS 地址', retry_transport: '暂时连接失败，请稍后重试',
  retry_http: '机场接口不可用，请核对插件版本', retry_receipt: '回执不匹配，请核对双方标识和插件版本',
};
export function AirportConnectionEditor({ connections, panelId, onUpdated }: {
  connections: AirportConnection[]; panelId: string; onUpdated: (c: AirportConnection) => void;
}) {
  const [form, setForm] = useState(empty), [selected, setSelected] = useState('');
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const [result, setResult] = useState<AirportConnection | null>(null);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const change = (key: keyof typeof empty, value: string | boolean) => { setForm(f => ({ ...f, [key]: value })); setResult(null); setError(''); };
  function choose(id: string) {
    setSelected(id); setError('');
    const c = connections.find(item => item.id === id);
    setForm(c ? { id: c.id, name: c.name, origin: c.origin, connectionId: c.connectionId, enabled: c.enabled, revision: c.revision, token: '' } : empty);
    setResult(c || null);
  }
  async function save() {
    if (busy) return; setBusy(true); setError('');
    try {
      const r = await saveAirportConnection(form);
      if (!mounted.current) return;
      setForm(f => ({ ...f, token: '' }));
      if (r.code !== 0) { setError(r.msg || '保存失败'); return; }
      setForm(f => ({ ...f, id: r.data.id, connectionId: r.data.connectionId, revision: r.data.revision, token: '' }));
      setSelected(r.data.id); setResult(r.data); onUpdated(r.data);
      if (r.data.enabled) {
        const checked = await checkAirportConnection(r.data.id, r.data.revision);
        if (!mounted.current) return;
        if (checked.code !== 0) { setError(checked.msg || '已保存，但连接测试失败'); return; }
        setResult(checked.data); setForm(f => ({ ...f, revision: checked.data.revision })); onUpdated(checked.data);
      }
    } catch { if (mounted.current) setError('保存失败，请重新核对连接配置'); }
    finally { if (mounted.current) setBusy(false); }
  }
  async function check() {
    if (!result || busy) return; setBusy(true); setError('');
    try {
      const r = await checkAirportConnection(result.id, result.revision);
      if (!mounted.current) return;
      if (r.code !== 0) { setError(r.msg || '测试失败'); return; }
      setResult(r.data); setForm(f => ({ ...f, revision: r.data.revision })); onUpdated(r.data);
    } catch { if (mounted.current) setError('连接测试失败'); }
    finally { if (mounted.current) setBusy(false); }
  }
  return <details className="rounded-2xl border border-default-200 bg-default-50 p-4">
    <summary className="cursor-pointer font-medium">管理机场连接 · 双方配对</summary>
    <div className="mt-4 flex flex-col gap-4">
      <div className="rounded-xl bg-content1 p-3 text-sm">
        <p className="font-medium">先在机场插件中添加此转发面板</p>
        <p className="mt-2 break-all">转发面板地址：{window.location.origin}</p>
        <p className="mt-1 break-all font-mono">面板标识：{panelId}</p>
        <p className="mt-2 text-default-500">将机场生成的配对 Token 填入下方，连接 ID 会自动识别。机场插件还需授权具体的转发节点编号。</p>
      </div>
      <label className="text-sm">编辑连接<select aria-label="编辑机场连接" value={selected} disabled={busy} onChange={e => choose(e.target.value)} className="mt-2 w-full rounded-xl bg-default-100 p-3"><option value="">新建机场连接</option>{connections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
      <Input label="连接名称" value={form.name} onValueChange={v => change('name', v)} isDisabled={busy}/>
      <Input label="机场通信地址" placeholder="https://你的机场域名" value={form.origin} onValueChange={v => change('origin', v)} isDisabled={busy}/>
      <Input label="配对 Token" type="password" autoComplete="new-password" value={form.token} onValueChange={v => change('token', v)} isDisabled={busy} description="粘贴机场插件生成的 ns2. 开头的 Token。已有同一连接可留空保留。"/>
      <details className="text-xs text-default-500"><summary>高级：分开填写连接 ID 和密钥</summary><Input className="mt-2" label="机场连接 ID" value={form.connectionId} onValueChange={v => change('connectionId', v)} isDisabled={busy}/></details>
      <Switch isSelected={form.enabled} onValueChange={v => change('enabled', v)} isDisabled={busy}>允许使用此机场连接</Switch>
      {error && <p role="alert" className="text-sm text-danger">{error}</p>}
      {result && <p role="status" className={`rounded-xl p-3 text-sm ${result.status === 'connected' ? 'bg-success-50 text-success-700' : 'bg-default-100'}`}>{statuses[result.status] || '等待检查'}{result.status === 'connected' && ` · ${result.bindings.length} 条授权映射`}</p>}
      <div className="flex flex-wrap gap-2"><Button size="sm" color="primary" isLoading={busy} onPress={() => void save()}>保存并测试连接</Button><Button size="sm" variant="flat" isDisabled={busy || !result?.enabled} onPress={() => void check()}>重新测试</Button></div>
      <p className="text-xs text-default-500">修改连接影响所有选择它的节点，保存后需重新测试。密钥不会下发到节点。停用连接不会停止转发服务。</p>
    </div>
  </details>;
}
