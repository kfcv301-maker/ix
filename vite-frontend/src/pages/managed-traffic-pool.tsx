import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Button } from '@heroui/button';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Link } from 'react-router-dom';
import toast from 'react-hot-toast';
import { enrollManagedServer, getManagedPool, saveManagedPool, type ManagedPool, type ManagedPoolServer } from '@/api/traffic-pools';
import { ConsoleIcon } from '@/components/console-ui';
import { JwtUtil } from '@/utils/jwt';
import './traffic-pools.css';

const GIGABYTE = 1_000_000_000;
const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });
const states: Record<string, { text: string; tone: string }> = {
  installing: { text: '安装中', tone: 'warn' },
  failed: { text: '安装失败', tone: 'offline' },
  healthy: { text: '在线', tone: '' },
  offline: { text: '离线', tone: 'offline' },
  report_stale: { text: '离线', tone: 'offline' },
  waiting_rules: { text: '等待转发', tone: 'warn' },
  confirming: { text: '检查中', tone: 'warn' },
};
const dnsStates: Record<string, string> = {
  disabled: '尚未设置解析', synced: '解析已同步', syncing: '正在同步解析',
  waiting_checks: '正在检查解析', dns_error: '解析同步失败',
};

type ServerForm = { ip: string; port: string; password: string; used: string; quota: string; retry: boolean };

function serverForm(server?: ManagedPoolServer): ServerForm {
  return { ip: server?.ip ?? '', port: String(server?.port ?? 22), password: '',
    used: String((server?.usedBytes ?? 0) / GIGABYTE), quota: server?.quotaBytes ? String(server.quotaBytes / GIGABYTE) : '',
    retry: Boolean(server) };
}

export default function ManagedTrafficPoolPage() {
  const admin = JwtUtil.getRoleIdFromToken() === 0;
  const [pool, setPool] = useState<ManagedPool | null>(null);
  const [server, setServer] = useState<ServerForm | null>(null);
  const [settings, setSettings] = useState<{ domain: string; token: string; revision: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const request = useRef(0);
  const reload = useCallback(async () => {
    const id = ++request.current;
    const response = await getManagedPool();
    if (id !== request.current) return;
    if (response.code === 0) {
      setPool(response.data);
      setError('');
    } else {
      setError(response.msg || '暂时无法加载流量池');
    }
  }, []);

  useEffect(() => {
    if (!admin) return;
    let active = true;
    let timer: number;
    const refresh = async () => {
      await reload();
      if (active) timer = window.setTimeout(refresh, 5000);
    };
    void refresh();
    return () => { active = false; ++request.current; window.clearTimeout(timer); };
  }, [admin, reload]);

  const openServer = (existing?: ManagedPoolServer) => {
    setFormError('');
    setServer(serverForm(existing));
  };
  const openSettings = () => {
    if (!pool) return;
    setFormError('');
    setSettings({ domain: pool.domain, token: '', revision: pool.revision });
  };
  const close = () => {
    if (busy) return;
    setServer(null);
    setSettings(null);
    setFormError('');
  };

  const add = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!server || busy) return;
    setBusy(true);
    setFormError('');
    try {
      const response = await enrollManagedServer({
        ip: server.ip.trim(), port: Number(server.port), password: server.password, panelOrigin: window.location.origin,
        usedBytes: Math.round(Number(server.used) * GIGABYTE), quotaBytes: Math.round(Number(server.quota) * GIGABYTE),
      });
      if (response.code !== 0) { setFormError(response.msg || '提交安装失败'); return; }
      setServer(null);
      toast.success('已开始安装');
      await reload();
    } finally {
      setBusy(false);
    }
  };

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!settings || busy) return;
    setBusy(true);
    setFormError('');
    try {
      const response = await saveManagedPool({ ...settings, domain: settings.domain.trim(), token: settings.token.trim() });
      if (response.code !== 0) { setFormError(response.msg || '保存设置失败'); return; }
      ++request.current;
      setPool(response.data);
      setSettings(null);
      toast.success('流量池设置已保存');
    } finally {
      setBusy(false);
    }
  };

  if (!admin) return <div className="pool-page"><h1>无权访问</h1></div>;
  const online = pool?.servers.filter(item => item.status === 'healthy').length ?? 0;
  return <div className="pool-page">
    <div className="pool-hero">
      <div className="pool-hero-title">
        <span className="pool-hero-mark" aria-hidden="true"><ConsoleIcon name="traffic-pools" size={22}/></span>
        <div><h1>流量池</h1><p className="pool-muted">在线加入解析，离线自动撤下。</p></div>
      </div>
      <div className="pool-row-actions">
        <Button variant="bordered" isDisabled={!pool} onPress={openSettings}>流量池设置</Button>
        <Button color="primary" isDisabled={!pool} onPress={() => openServer()}>添加服务器</Button>
      </div>
    </div>
    {error && <p className="pool-alert" role="alert">{error}</p>}
    {!pool && !error && <p className="pool-muted">正在加载流量池…</p>}
    {pool && <section className="pool-card" aria-label="服务器清单">
      <div className="pool-entry">
        <div><span className="pool-muted">入口域名</span>{pool.domain ? <code>{pool.domain}</code> : <strong>尚未设置</strong>}</div>
        <span className={`pool-pill ${pool.dnsStatus === 'synced' && online > 0 ? '' : 'muted'}`}>
          {pool.dnsStatus === 'synced' && online === 0 ? '暂无在线入口' : dnsStates[pool.dnsStatus] || '正在检查解析'}
        </span>
      </div>
      <div className="pool-card-head pool-member-head"><h2>服务器</h2><span className="pool-muted">{online} 台在线 / 共 {pool.servers.length} 台</span></div>
      {pool.servers.length === 0 && <p className="pool-muted">添加服务器后，面板会通过 SSH 自动安装节点。</p>}
      {pool.tunnelId && pool.servers.some(item => item.status === 'waiting_rules') && online === 0 &&
        <p className="pool-muted">在 <Link className="text-primary underline" to="/forward">转发管理</Link> 给「独立流量池」添加转发后，系统会检查解析。</p>}
      {pool.servers.map(item => {
        const state = states[item.status] || states.confirming;
        return <div className="pool-node pool-managed-node" key={item.id}>
          <div className="min-w-0"><strong className="block break-all">{item.ip}</strong><small className="pool-muted">SSH {item.port}</small>
            {item.message && <p className="pool-muted">{item.message}</p>}</div>
          <span className="pool-muted pool-server-usage">{number.format(item.usedBytes / GIGABYTE)}{item.quotaBytes ? ` / ${number.format(item.quotaBytes / GIGABYTE)}` : ''} GB</span>
          <div className="pool-row-actions pool-server-actions justify-end">
            <span className={`pool-pill ${state.tone}`}>{state.text}</span>
            {item.status === 'failed' && <Button size="sm" variant="light" onPress={() => openServer(item)}>重试</Button>}
          </div>
        </div>;
      })}
    </section>}
    <Modal isOpen={Boolean(server || settings)} onClose={close} isDismissable={!busy} scrollBehavior="inside">
      <ModalContent>
        {server && <form onSubmit={add} className="flex min-h-0 flex-col">
          <ModalHeader>{server.retry ? '重试安装' : '添加服务器'}</ModalHeader>
          <ModalBody>
            <label className="pool-field">公网服务器 IP<input required maxLength={45} value={server.ip} readOnly={server.retry}
              onChange={e => setServer({ ...server, ip: e.target.value })} placeholder="IPv4 或 IPv6"/></label>
            <div className="pool-form-grid">
              <label className="pool-field">SSH 端口<input required type="number" min={1} max={65535} value={server.port}
                onChange={e => setServer({ ...server, port: e.target.value })}/></label>
              <label className="pool-field">root 密码<input required type="password" autoComplete="new-password" maxLength={512}
                value={server.password} onChange={e => setServer({ ...server, password: e.target.value })}/></label>
            </div>
            {!server.retry && <div className="pool-form-grid">
              <label className="pool-field">初始已用流量（GB）<input type="number" min={0} max={9000000} step="any" value={server.used}
                onChange={e => setServer({ ...server, used: e.target.value })}/></label>
              <label className="pool-field">总流量（GB，可选）<input type="number" min={0} max={9000000} step="any" value={server.quota}
                onChange={e => setServer({ ...server, quota: e.target.value })}/></label>
            </div>}
            {formError && <p className="pool-alert" role="alert">{formError}</p>}
          </ModalBody>
          <ModalFooter><Button variant="light" isDisabled={busy} onPress={close}>取消</Button><Button color="primary" type="submit" isLoading={busy}>开始安装</Button></ModalFooter>
        </form>}
        {settings && <form onSubmit={save} className="flex min-h-0 flex-col">
          <ModalHeader>流量池设置</ModalHeader>
          <ModalBody>
            <label className="pool-field">入口域名<input required maxLength={253} value={settings.domain} placeholder="pool.example.com"
              onChange={e => setSettings({ ...settings, domain: e.target.value })}/></label>
            <label className="pool-field">Cloudflare API Token<input required={!pool?.hasToken} type="password" autoComplete="new-password"
              value={settings.token} maxLength={256} placeholder={pool?.hasToken ? '留空保留当前令牌' : ''}
              onChange={e => setSettings({ ...settings, token: e.target.value })}/><small>需要读取 Zone 和编辑 DNS 的权限。</small></label>
            {formError && <p className="pool-alert" role="alert">{formError}</p>}
          </ModalBody>
          <ModalFooter><Button variant="light" isDisabled={busy} onPress={close}>取消</Button><Button color="primary" type="submit" isLoading={busy}>保存设置</Button></ModalFooter>
        </form>}
      </ModalContent>
    </Modal>
  </div>;
}
