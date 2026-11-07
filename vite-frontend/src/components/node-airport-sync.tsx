import { useEffect, useRef, useState } from 'react';
import { Button } from '@heroui/button';
import { Input } from '@heroui/input';
import { Switch } from '@heroui/switch';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Spinner } from '@heroui/spinner';
import { getAirportSync, saveAirportSync, removeAirportSync, getAirportObserver, type AirportSyncSettings } from '@/api';

const messages: Record<string, string> = {
  disabled: '未启用，不会向机场发送地址', waiting_source: '等待可信 IPv4：请核对来源配置、代理链及节点心跳',
  waiting_observer: '尚未收到采集器首报：新机器自动安装，已有机器请先补装采集器',
  confirming: '正在核对连续心跳，确认地址稳定', pending: '地址已确认，等待同步', synced: '机场已确认接收',
  retry_transport: '连接暂时失败，自动重试', retry_http: '机场暂时不可用，自动重试', retry_receipt: '未取得有效回执，自动重试',
  blocked_401: '密钥无效，请更新绑定密钥并保存', blocked_403: '机场绑定已停用或锁定其他实例，请核对后保存',
  blocked_409: '机场地址被手动修改、绑定缺失或报告顺序冲突，请核对绑定后保存',
  blocked_422: '机场拒绝地址或时间，请核对时钟与绑定后保存', blocked_destination: '接收地址解析到不允许的网络，已停止同步',
  blocked_redirect: '接收端发生跳转，已停止同步，请填写最终 HTTPS 接口', blocked_credential: '已保存密钥无法解密，请重新输入',
};
const time = (value: number) => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未同步';

export function NodeAirportSyncModal({ node, onClose }: { node: { id: number; name: string } | null; onClose: () => void }) {
  const [data, setData] = useState<AirportSyncSettings | null>(null);
  const [enabled, setEnabled] = useState(false), [endpoint, setEndpoint] = useState(''), [binding, setBinding] = useState('');
  const [token, setToken] = useState(''), [confirmed, setConfirmed] = useState(false);
  const [sourceMode, setSourceMode] = useState<'peer' | 'aws-imds'>('aws-imds');
  const [busy, setBusy] = useState(false), [loading, setLoading] = useState(false), [error, setError] = useState('');
  const epoch = useRef(0);
  useEffect(() => {
    const current = ++epoch.current;
    setData(null); setToken(''); setError(''); setConfirmed(false); setBusy(false);
    if (!node) return;
    setLoading(true);
    void getAirportSync(node.id).then(response => {
      if (epoch.current !== current) return;
      if (response.code !== 0 || !response.data) { setError(response.msg || '读取失败'); return; }
      const next = response.data;
      setData(next); setEnabled(next.enabled); setEndpoint(next.endpoint); setBinding(next.bindingId);
      setSourceMode(next.revision ? next.sourceMode : 'aws-imds');
    }).catch(() => { if (epoch.current === current) setError('读取失败，请重新打开'); })
      .finally(() => { if (epoch.current === current) setLoading(false); });
    return () => { epoch.current++; };
  }, [node?.id]);
  // Refresh status without overwriting an administrator's unsaved settings or secret.
  useEffect(() => {
    if (!node || !data || busy) return;
    let active = true, fetching = false;
    const refresh = async () => {
      if (document.hidden || fetching) return;
      fetching = true;
      try {
        const result = await getAirportSync(node.id);
        if (active && result.code === 0) setData(previous => previous && result.data.revision === previous.revision ? result.data : previous);
      } finally { fetching = false; }
    };
    const timer = window.setInterval(() => { void refresh().catch(() => {}); }, 5000);
    return () => { active = false; window.clearInterval(timer); };
  }, [node?.id, data?.revision, busy]);
  async function submit(remove = false) {
    if (!node || !data || busy) return;
    const current = epoch.current;
    setBusy(true); setError('');
    try {
      const response = remove ? await removeAirportSync(node.id, data.revision) : await saveAirportSync({
        nodeId: node.id, enabled, endpoint: endpoint.trim(), bindingId: binding.trim(), token, revision: data.revision, sourceMode,
      });
      if (epoch.current !== current) return;
      setToken('');
      if (response.code !== 0) { setError(response.msg || '保存失败'); return; }
      if (remove) { onClose(); return; }
      setData(response.data); setConfirmed(false);
    } catch { if (epoch.current === current) setError('保存失败，请重新核对状态'); }
    finally { if (epoch.current === current) setBusy(false); }
  }
  async function downloadObserver() {
    if (!node || busy) return;
    const current = epoch.current;
    setBusy(true); setError('');
    try {
      const response = await getAirportObserver(node.id);
      if (epoch.current !== current) return;
      if (response.code !== 0 || !response.data) { setError(response.msg || '下载失败'); return; }
      const bytes = Uint8Array.from(atob(response.data.scriptBase64), char => char.charCodeAt(0));
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), value => value.toString(16).padStart(2, '0')).join('');
      if (hash !== response.data.sha256) throw new Error('Invalid package');
      if (epoch.current !== current) return;
      const url = URL.createObjectURL(new Blob([bytes], { type: 'text/x-python' }));
      const link = document.createElement('a'); link.href = url; link.download = 'airport_address_reporter.py'; link.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch { if (epoch.current === current) setError('采集器下载或校验失败，请稍后重试'); }
    finally { if (epoch.current === current) setBusy(false); }
  }
  return <Modal isOpen={!!node} onClose={() => { if (!busy) onClose(); }} isDismissable={!busy} hideCloseButton={busy} size="2xl" scrollBehavior="inside">
    <ModalContent>
      <ModalHeader className="flex flex-col gap-1"><span>机场地址同步</span><span className="text-sm font-normal text-default-500">{node?.name} · 仅管理员可管理</span></ModalHeader>
      <ModalBody className="gap-5">
        {loading && <Spinner label="读取绑定设置"/>}
        {error && <div role="alert" className="rounded-xl bg-danger-50 p-3 text-sm text-danger">{error}</div>}
        {data && <>
          <div className="rounded-2xl border border-default-200 bg-default-50 p-4">
            <Switch isSelected={enabled} onValueChange={setEnabled} isDisabled={busy}>同步此节点的公网 IPv4</Switch>
            <p className="mt-2 text-sm text-default-500">只更新机场内此绑定所选节点的地址。端口、协议和用户权限保持不变。</p>
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="rounded-xl border border-default-200 p-3"><p className="text-xs text-default-500">当前可信来源</p><p className="mt-1 break-all font-mono">{data.sourceIp || '暂无可信 IPv4'}</p></div>
            <div className="rounded-xl border border-default-200 p-3"><p className="text-xs text-default-500">机场最后确认</p><p className="mt-1 break-all font-mono">{data.lastIp || '—'}</p><p className="mt-1 text-xs text-default-500">{time(data.lastSuccessAt)}</p></div>
          </div>
          <p role="status" className={`rounded-xl p-3 text-sm ${data.status.startsWith('blocked_') ? 'bg-danger-50 text-danger' : 'bg-primary-50 text-primary'}`}>{messages[data.status] || '等待检查'}{data.nextAttemptAt > Date.now() && <span> · 下次尝试 {time(data.nextAttemptAt)}</span>}</p>
          <label className="text-sm">IPv4 来源<select className="mt-2 w-full rounded-xl bg-default-100 p-3" value={sourceMode} onChange={event => setSourceMode(event.target.value as 'peer' | 'aws-imds')} disabled={busy}><option value="aws-imds">AWS 实例元数据（适用于 AWS 换机）</option><option value="peer">已核验的节点连接来源</option></select></label>
          {sourceMode === 'aws-imds' && <p className="text-sm text-default-500">新机器执行原安装命令时自动安装轻量采集器。已有机器需补装采集器后才会报告；保存设置不会重启节点。</p>}
          {data.enabled && data.sourceMode === 'aws-imds' && <details className="rounded-xl border border-default-200 p-3 text-sm"><summary className="cursor-pointer">已有机器：仅补装采集器</summary><p className="my-2 text-default-500">下载后上传到对应节点，使用以下命令安装，只启动采集器。</p><code className="block break-all text-xs">sudo python3 airport_address_reporter.py --install --root /etc/flux-panel-agent</code><Button className="mt-3" size="sm" variant="flat" isDisabled={busy} onPress={() => void downloadObserver()}>下载采集器</Button></details>}
          <Input label="机场接收接口" placeholder="https://你的机场域名/api/v1/node-address-sync/report" value={endpoint} onValueChange={setEndpoint} isDisabled={busy}/>
          <Input label="绑定 ID" description="在机场后台创建绑定并明确选择需要更新的节点。" value={binding} onValueChange={setBinding} isDisabled={busy}/>
          <Input label="独立绑定密钥" type="password" autoComplete="new-password" value={token} onValueChange={setToken} isDisabled={busy}
            placeholder={data.hasToken ? '已保存；留空保留同一绑定密钥' : '输入机场绑定生成的密钥'} description="仅保存在面板后端，不加入安装命令；更换接口或绑定需重新填写。"/>
          {enabled && <label className="flex items-start gap-2 text-sm text-default-600"><input type="checkbox" className="mt-1" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} disabled={busy}/><span>我已核对来源 IPv4 是这台机器的入口地址，机场绑定未锁定旧实例，并已停用其他上报程序，避免互相覆盖。</span></label>}
          <p className="text-xs text-default-500">使用原安装命令即可重新接入。AWS 模式读取实例公网 IPv4，不受面板连接使用 IPv6 的影响；连接来源模式需先核验代理链与入口地址。</p>
        </>}
      </ModalBody>
      <ModalFooter className="flex-wrap">
        {data && data.revision > 0 && <Button variant="light" color="danger" isDisabled={busy} onPress={() => void submit(true)}>解除绑定并停止同步</Button>}
        <Button variant="light" isDisabled={busy} onPress={onClose}>关闭</Button>
        <Button color="primary" isLoading={busy} isDisabled={!data || loading || (enabled && !confirmed)} onPress={() => void submit()}>保存设置</Button>
      </ModalFooter>
    </ModalContent>
  </Modal>;
}
