import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Button } from '@heroui/button';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Link } from 'react-router-dom';
import toast from 'react-hot-toast';
import { getTunnelList } from '@/api';
import {
  changePoolServerAddress, configurePool, enrollPoolServers, getConfiguredPool, listConfiguredPools,
  type ConfiguredPool, type ConfiguredServer, type PoolSettings,
} from '@/api/traffic-pools';
import { ConsoleIcon } from '@/components/console-ui';
import { JwtUtil } from '@/utils/jwt';
import './traffic-pools.css';

const GB = 1_000_000_000;
const MAX_BATCH = 16;
const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });
const states: Record<string, { label: string; tone: string }> = {
  installing: { label: '安装中', tone: 'warn' },
  failed: { label: '安装失败', tone: 'offline' },
  healthy: { label: '在线', tone: '' },
  offline: { label: '离线', tone: 'offline' },
  report_stale: { label: '离线', tone: 'offline' },
  maintenance: { label: '已停用', tone: 'muted' },
  missing: { label: '离线', tone: 'offline' },
};
const dnsStates: Record<string, string> = {
  disabled: '未配置解析',
  synced: '解析已同步',
  syncing: '解析同步中',
  waiting_checks: '解析同步中',
  dns_error: '解析同步失败',
};

type TunnelOption = { id: number; name: string; inIp: string; type: number; status: number; ownerUserId?: number | null };
type ServerRow = { key: number; ip: string; port: string; username: string; password: string; used: string; quota: string; error?: string };
type ServerDialog = { pool: ConfiguredPool; rows: ServerRow[]; mode: 'add' | 'retry' | 'address'; target?: ConfiguredServer };

function serverRow(key: number, server?: ConfiguredServer): ServerRow {
  return { key, ip: server?.ip ?? '', port: String(server?.port ?? 22), username: 'root',
    password: '', used: '', quota: '' };
}

function poolSettings(pool?: ConfiguredPool): PoolSettings {
  return { id: pool?.id ?? '', name: pool?.name ?? '', domain: pool?.domain ?? '', zoneId: '',
    token: '', revision: pool?.revision ?? 0, tunnelId: pool?.tunnelId ?? null };
}

export default function ManagedTrafficPoolPage() {
  const admin = JwtUtil.getRoleIdFromToken() === 0;
  const [pools, setPools] = useState<ConfiguredPool[] | null>(null);
  const [selected, setSelected] = useState('');
  const [detail, setDetail] = useState<{ pool: ConfiguredPool; servers: ConfiguredServer[] } | null>(null);
  const [tunnels, setTunnels] = useState<TunnelOption[]>([]);
  const [settings, setSettings] = useState<PoolSettings | null>(null);
  const [servers, setServers] = useState<ServerDialog | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const nextRow = useRef(1);
  const detailRequest = useRef(0);
  const listRequest = useRef(0);

  const reloadPools = useCallback(async () => {
    const request = ++listRequest.current;
    try {
      const reply = await listConfiguredPools();
      if (request !== listRequest.current) return;
      if (reply.code !== 0) throw new Error(reply.msg);
      setPools(reply.data);
      setSelected(current => reply.data.some(pool => pool.id === current) ? current : reply.data[0]?.id ?? '');
      setError('');
    } catch (failure) {
      if (request === listRequest.current) setError(failure instanceof Error ? failure.message : '无法读取流量池');
    }
  }, []);

  const reloadDetail = useCallback(async (id: string) => {
    const request = ++detailRequest.current;
    try {
      const reply = await getConfiguredPool(id);
      if (request !== detailRequest.current) return;
      if (reply.code !== 0) throw new Error(reply.msg);
      setDetail(reply.data);
      setError('');
    } catch (failure) {
      if (request === detailRequest.current) setError(failure instanceof Error ? failure.message : '无法读取服务器');
    }
  }, []);

  useEffect(() => {
    if (!admin) return;
    void reloadPools();
    void getTunnelList().then(reply => {
      if (reply.code === 0 && Array.isArray(reply.data)) setTunnels(reply.data);
    }).catch(() => {});
    return () => { ++listRequest.current; };
  }, [admin, reloadPools]);

  useEffect(() => {
    if (!admin || !selected) return;
    let active = true;
    let timer: number;
    const refresh = async () => {
      await reloadDetail(selected);
      if (active) timer = window.setTimeout(refresh, 5000);
    };
    void refresh();
    return () => { active = false; ++detailRequest.current; window.clearTimeout(timer); };
  }, [admin, selected, reloadDetail]);

  const current = detail?.pool.id === selected ? detail : null;
  const pool = current?.pool ?? pools?.find(item => item.id === selected);
  const settingsPool = pool?.id === settings?.id ? pool : pools?.find(item => item.id === settings?.id);
  const assigned = new Set(pools?.map(item => item.tunnelId));
  const candidateDomain = settings?.domain.trim().toLowerCase().replace(/\.$/, '');
  const candidates = tunnels.filter(tunnel => tunnel.type === 1 && tunnel.status === 1 && tunnel.ownerUserId == null
    && !assigned.has(tunnel.id) && tunnel.inIp?.toLowerCase() === candidateDomain);
  const openServers = (target: ConfiguredPool, server?: ConfiguredServer, mode: ServerDialog['mode'] = 'add') => {
    setFormError('');
    const row = serverRow(nextRow.current++, server);
    if (mode === 'address') row.ip = '';
    setServers({ pool: target, rows: [row], mode, target: server });
  };
  const openSettings = (target?: ConfiguredPool) => {
    void getTunnelList().then(reply => {
      if (reply.code === 0 && Array.isArray(reply.data)) setTunnels(reply.data);
    }).catch(() => {});
    setFormError('');
    setSettings(poolSettings(target));
  };
  const close = () => {
    if (busy) return;
    setServers(null);
    setSettings(null);
    setFormError('');
  };
  const updateRow = (key: number, field: 'ip' | 'port' | 'username' | 'password' | 'used' | 'quota', value: string) =>
    setServers(form => form && ({ ...form, rows: form.rows.map(row => row.key === key ? { ...row, [field]: value, error: undefined } : row) }));

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!settings || busy) return;
    setBusy(true);
    setFormError('');
    try {
      const reply = await configurePool({ ...settings, name: settings.name.trim(), domain: settings.domain.trim(),
        zoneId: settings.zoneId.trim(), token: settings.token.trim() });
      if (reply.code !== 0) throw new Error(reply.msg || '保存流量池失败');
      const saved = reply.data;
      setPools(items => [...(items ?? []).filter(item => item.id !== saved.id), saved]);
      setSelected(saved.id);
      setSettings(null);
      toast.success(settings.id ? '流量池设置已保存' : '流量池已创建');
      if (!settings.id) openServers(saved);
      if (selected === saved.id) void reloadDetail(saved.id);
      void reloadPools();
    } catch (failure) {
      setFormError(failure instanceof Error ? failure.message : '保存流量池失败');
    } finally {
      setBusy(false);
    }
  };

  const add = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!servers || busy) return;
    setBusy(true);
    setFormError('');
    try {
      if (servers.mode === 'address' && servers.target) {
        const { ip, username, password } = servers.rows[0];
        const reply = await changePoolServerAddress({ id: servers.pool.id, nodeId: servers.target.id, ip: ip.trim(), username, password });
        if (reply.code !== 0) throw new Error(reply.msg || '更新 IP 失败');
        setServers(null);
        toast.success('已更新绑定 IP，解析会自动同步');
        if (selected === servers.pool.id) void reloadDetail(servers.pool.id);
        return;
      }
      const reply = await enrollPoolServers(servers.pool.id, servers.rows.map(row => ({
        ip: row.ip.trim(), port: Number(row.port), username: row.username.trim(),
        password: row.password, panelOrigin: window.location.origin,
        usedBytes: Math.round(Number(row.used) * GB), quotaBytes: Math.round(Number(row.quota) * GB),
      })));
      if (reply.code !== 0) throw new Error(reply.msg || '提交安装失败');
      const results = new Map(reply.data.map(item => [item.index, item]));
      const pending = servers.rows.flatMap((row, index) => {
        const result = results.get(index);
        return result?.accepted ? [] : [{ ...row, password: '', error: result?.message ?? '提交结果未知，请刷新后重试' }];
      });
      const accepted = servers.rows.length - pending.length;
      if (accepted) toast.success(accepted + ' 台服务器已提交安装');
      setServers(pending.length ? { ...servers, rows: pending } : null);
      if (selected === servers.pool.id) void reloadDetail(servers.pool.id);
    } catch (failure) {
      setFormError(failure instanceof Error ? failure.message : '提交安装失败');
    } finally {
      setBusy(false);
    }
  };

  if (!admin) return <div className="pool-page"><h1>无权访问</h1></div>;
  const rows = current?.servers ?? [];
  const online = rows.filter(server => server.status === 'healthy').length;
  const inDns = rows.filter(server => server.published).length;

  return <div className="pool-page pool-setup">
    <header className="pool-hero">
      <div className="pool-hero-title">
        <span className="pool-hero-mark" aria-hidden="true"><ConsoleIcon name="traffic-pools" size={22}/></span>
        <div><h1>流量池</h1><p className="pool-muted">每个池绑定一个域名和一条隧道，可挂载多台服务器。</p></div>
      </div>
      <Button color="primary" onPress={() => openSettings()} startContent={<ConsoleIcon name="plus"/>}>添加流量池</Button>
    </header>
    {error && <p className="pool-alert" role="alert">{error}</p>}
    {!pools && !error && <p className="pool-muted">正在加载流量池…</p>}
    {pools?.length === 0 && <section className="pool-card pool-empty">
      <h2>还没有流量池</h2>
      <p className="pool-muted">先设置入口域名、DNS 和隧道，再添加服务器。</p>
    </section>}
    {!!pools?.length && <div className="pool-tabs" aria-label="选择流量池">
      {pools.map(item => <Button key={item.id} size="sm" variant={item.id === selected ? 'solid' : 'light'}
        color={item.id === selected ? 'primary' : 'default'} aria-pressed={item.id === selected}
        onPress={() => setSelected(item.id)}>{item.name}</Button>)}
    </div>}
    {pool && <>
      <section className="pool-card" aria-label="流量池设置">
        <div className="pool-card-head"><h2>{pool.name}</h2>
          <Button size="sm" variant="light" onPress={() => openSettings(pool)}>池设置</Button>
        </div>
        <div className="pool-setup-summary">
          <div><span className="pool-muted">入口域名</span><code>{pool.domain || '未配置'}</code></div>
          <div><span className="pool-muted">隧道</span><strong>{pool.tunnelId
            ? tunnels.find(tunnel => tunnel.id === pool.tunnelId)?.name ?? ('隧道 #' + pool.tunnelId)
            : '首台服务器安装后创建'}</strong></div>
          <div><span className="pool-muted">DNS</span><strong>{dnsStates[pool.dnsStatus] ?? '解析同步中'}</strong></div>
        </div>
      </section>
      <section className="pool-card" aria-label="服务器列表">
        <div className="pool-card-head">
          <div><h2>服务器</h2>{current && <p className="pool-muted">{online} 台在线 / 共 {rows.length} 台
            {pool.dnsStatus === 'synced' && (' · ' + inDns + ' 台在解析中')}</p>}</div>
          <Button size="sm" color="primary" onPress={() => pool.hasToken && pool.domain ? openServers(pool) : openSettings(pool)}
            startContent={<ConsoleIcon name="plus"/>}>添加服务器</Button>
        </div>
        {!current && <p className="pool-muted">正在加载服务器…</p>}
        {current && rows.length === 0 && <p className="pool-muted pool-empty">可以一次添加多台，面板会通过 SSH 自动安装。</p>}
        {current && pool.tunnelId && pool.probePort === 0 && rows.some(row => row.status !== 'installing' && row.status !== 'failed') &&
          <p className="pool-alert">请在 <Link className="text-primary underline" to="/forward">转发管理</Link> 为这条隧道添加转发规则，端口检测通过后会自动加入解析。</p>}
        {rows.map(server => {
          const state = states[server.status] ?? { label: '检查中', tone: 'warn' };
          return <div className="pool-node pool-managed-node" key={server.id}>
            <div className="min-w-0">
              {server.name && server.name !== '池节点 ' + server.ip && <strong className="block break-all">{server.name}</strong>}
              <code className="block break-all">{server.ip || '—'}</code>
              {server.port && <small className="pool-muted">SSH {server.port}</small>}
              {server.message && <p className="pool-muted">{server.message}</p>}
            </div>
            <span className="pool-muted pool-server-usage">{number.format(server.usedBytes / GB)}
              {server.quotaBytes > 0 && ' / ' + number.format(server.quotaBytes / GB)} GB</span>
            <div className="pool-row-actions pool-server-actions justify-end">
              {server.published != null && <span className="pool-muted">{server.published ? '已加入解析' : '未加入解析'}</span>}
              <span className={'pool-pill ' + state.tone}>{state.label}</span>
              {server.port != null && server.status !== 'installing' && <Button size="sm" variant="light"
                onPress={() => openServers(pool, server, server.status === 'failed' ? 'retry' : 'address')}>
                {server.status === 'failed' ? '重试' : '更换 IP'}</Button>}
            </div>
          </div>;
        })}
      </section>
    </>}
    <Modal isOpen={Boolean(servers || settings)} onClose={close} isDismissable={!busy} isKeyboardDismissDisabled={busy}
      size={servers ? '3xl' : 'lg'} scrollBehavior="inside">
      <ModalContent>
        {settings && <form onSubmit={save} className="flex min-h-0 flex-col">
          <ModalHeader>{settings.id ? '流量池设置' : '添加流量池'}</ModalHeader>
          <ModalBody>
            <label className="pool-field">流量池名称<input required maxLength={100} value={settings.name}
              onChange={e => setSettings({ ...settings, name: e.target.value })}/></label>
            <label className="pool-field">入口域名<input required maxLength={253} value={settings.domain} placeholder="pool.example.com"
              onChange={e => setSettings({ ...settings, domain: e.target.value, tunnelId: settingsPool?.tunnelId ?? null })}/></label>
            <label className="pool-field">隧道<select value={settings.tunnelId ?? ''} disabled={Boolean(settingsPool?.tunnelId)}
              onChange={e => setSettings({ ...settings, tunnelId: e.target.value ? Number(e.target.value) : null })}>
              <option value="">自动创建专用隧道</option>
              {settingsPool?.tunnelId && <option value={settingsPool.tunnelId}>
                {tunnels.find(tunnel => tunnel.id === settingsPool.tunnelId)?.name ?? ('隧道 #' + settingsPool.tunnelId)}</option>}
              {candidates.map(tunnel => <option key={tunnel.id} value={tunnel.id}>{tunnel.name}</option>)}
            </select>{!settingsPool?.tunnelId && <small>也可选择入口域名相同、尚未绑定流量池的直连隧道。</small>}</label>
            <label className="pool-field">Cloudflare API Token<input required={!settingsPool?.hasToken} type="password" autoComplete="new-password"
              value={settings.token} maxLength={256} placeholder={settingsPool?.hasToken ? '留空保留当前令牌' : ''}
              onChange={e => setSettings({ ...settings, token: e.target.value })}/><small>需有读取 Zone 和编辑 DNS 的权限。</small></label>
            <label className="pool-field">Zone ID（选填）<input value={settings.zoneId} maxLength={32}
              placeholder={settingsPool?.zoneId || '留空自动识别'}
              onChange={e => setSettings({ ...settings, zoneId: e.target.value })}/></label>
            {formError && <p className="pool-alert" role="alert">{formError}</p>}
          </ModalBody>
          <ModalFooter><Button variant="light" isDisabled={busy} onPress={close}>取消</Button>
            <Button color="primary" type="submit" isLoading={busy}>{settings.id ? '保存设置' : '创建并添加服务器'}</Button></ModalFooter>
        </form>}
        {servers && <form onSubmit={add} className="flex min-h-0 flex-col">
          <ModalHeader>{servers.mode === 'address' ? '更换服务器 IP' : servers.mode === 'retry' ? '重试安装' : '添加服务器'}<span className="pool-dialog-context">{servers.pool.name}</span></ModalHeader>
          <ModalBody>
            {servers.mode === 'address' && <p className="pool-muted">当前 IP {servers.target?.ip}。面板会核对服务器身份，再更新绑定。</p>}
            {servers.rows.map((row, index) => <fieldset className="pool-batch-server" key={row.key}>
              <legend>服务器 {index + 1}</legend>
              <div className="pool-batch-fields">
                <label className="pool-field">公网 IP<input required maxLength={45} value={row.ip} readOnly={servers.mode === 'retry'}
                  onChange={e => updateRow(row.key, 'ip', e.target.value)} placeholder="IPv4 或 IPv6"/></label>
                <label className="pool-field">SSH 端口<input required type="number" min={1} max={65535} value={row.port} readOnly={servers.mode === 'address'}
                  onChange={e => updateRow(row.key, 'port', e.target.value)}/></label>
                <label className="pool-field">SSH 用户名<input required autoComplete="off" maxLength={32} value={row.username}
                  onChange={e => updateRow(row.key, 'username', e.target.value)}/></label>
                <label className="pool-field">SSH 密码<input required type="password" autoComplete="new-password" maxLength={512}
                  value={row.password} onChange={e => updateRow(row.key, 'password', e.target.value)}/></label>
              </div>
              {servers.mode === 'add' && <details className="pool-batch-extra">
                <summary>初始流量（选填）</summary>
                <div className="pool-form-grid">
                  <label className="pool-field">已用流量（GB）<input type="number" min={0} max={9000000} step="any" value={row.used}
                    placeholder="0" onChange={e => updateRow(row.key, 'used', e.target.value)}/></label>
                  <label className="pool-field">总流量（GB）<input type="number" min={0} max={9000000} step="any" value={row.quota}
                    onChange={e => updateRow(row.key, 'quota', e.target.value)}/></label>
                </div>
              </details>}
              {row.error && <p className="pool-alert" role="alert">{row.error}</p>}
              {servers.rows.length > 1 && <Button size="sm" variant="light" isDisabled={busy} aria-label={'移除服务器 ' + (index + 1)}
                onPress={() => setServers({ ...servers, rows: servers.rows.filter(item => item.key !== row.key) })}>移除</Button>}
            </fieldset>)}
            {servers.mode === 'add' && <div><Button size="sm" variant="bordered" isDisabled={busy || servers.rows.length >= MAX_BATCH}
              onPress={() => setServers({ ...servers, rows: [...servers.rows, serverRow(nextRow.current++)] })}>再加一台</Button></div>}
            {formError && <p className="pool-alert" role="alert">{formError}</p>}
          </ModalBody>
          <ModalFooter><Button variant="light" isDisabled={busy} onPress={close}>取消</Button>
            <Button color="primary" type="submit" isLoading={busy}>{servers.mode === 'address' ? '验证并保存' : '安装 ' + servers.rows.length + ' 台'}</Button></ModalFooter>
        </form>}
      </ModalContent>
    </Modal>
  </div>;
}
