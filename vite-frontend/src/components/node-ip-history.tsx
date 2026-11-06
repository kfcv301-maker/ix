import { useEffect, useRef, useState } from 'react';
import { Button } from '@heroui/button';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Spinner } from '@heroui/spinner';
import { ConsoleIcon } from './console-ui';
import { getNodeIpHistory, getNodeIpSummaries, type NodeIpHistory, type NodeIpSummary } from '@/api';
import './node-ip-history.css';

const formatTime = (value: number, short = false) => value ? new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', ...(short ? {} : { second: '2-digit' as const }), hour12: false,
}).format(value) : '—';

const states: Record<string, { label: string; tone: string; detail: string }> = {
  ok: { label: '解析正常', tone: 'good', detail: '最近一次观测成功' },
  confirming: { label: '变化待确认', tone: 'amber', detail: '等待约两分钟并核对连续观测，合并 IPv4 / IPv6 先后变化' },
  incomplete_families: { label: '双栈地址待确认', tone: 'amber', detail: 'IPv4 或 IPv6 暂时缺失，保留上次完整地址，不计次数' },
  pending: { label: '等待首次观测', tone: 'neutral', detail: '后台正在建立地址基线' },
  stale: { label: '数据待刷新', tone: 'amber', detail: '近期没有新的观测结果' },
  no_records: { label: '暂无解析记录', tone: 'amber', detail: '未获得 A / AAAA 记录，保留上次地址' },
  resolve_failed: { label: '解析暂时失败', tone: 'amber', detail: '暂未获得解析结果，不计为 IP 变化' },
  invalid_target: { label: '地址无法观测', tone: 'amber', detail: '需要有效的域名或 IP 地址' },
  invalid_answer: { label: '解析结果异常', tone: 'amber', detail: '未采用异常结果，保留上次地址' },
};

const judgments: Record<string, string> = {
  IP_ONLY_LIKELY: '倾向仅换 IP', MACHINE_SUSPECTED: '疑似换机或重启',
  CONTINUOUS: '运行时间连续', UNKNOWN: '无法判定机器变化',
};
const reasons: Record<string, string> = {
  V4_ONLY_CHANGED: 'IPv4 已变化，IPv6 保持不变；按当前机器的地址分配规律，判断为仅换 IPv4。',
  DUAL_STACK_CHANGED: 'IPv4、IPv6 在同一确认窗口内均发生变化，按当前机器的地址分配规律判断为疑似换机。',
  DUAL_STACK_CHANGED_RESTART: 'IPv4、IPv6 均发生变化，附近还观察到运行时长重置或机器配置变化，进一步支持换机判断。',
  INCOMPLETE_ADDRESS_FAMILIES: '缺少完整的 IPv4 / IPv6 前后对照，暂不能区分换机与换 IP。',
  V6_ONLY_CHANGED: '仅 IPv6 变化，不符合当前的换 IPv4 或换机规律，暂不归类。',
  UPTIME_CONTINUOUS: '前后上报的系统运行时间连续，未发现换机证据。',
  BOOT_CHANGED: '重连前后的推算启动时间发生变化，可能换机，也可能系统重启。',
  CONFIG_CHANGED: '重连后连续两次观察到硬件配置明显不同，可能换机或调整了配置。',
  NEAR_RECONNECT: '地址变化附近也观察到疑似换机或重启，两者是否有关尚未核实。',
  OBSERVATION_GAP: '上报观测间隔过长，无法可靠比较前后机器。',
  PANEL_RESTART: '面板重新启动后建立新的观测基线，不计作换机。',
  INCONSISTENT_UPTIME: '运行时间上报不连续，证据不足，暂不判断换机。',
  RECONNECT_PENDING: '新的连接尚在等待第二次有效观测。',
  FIRST_SAMPLE: '刚开始记录机器运行时间，尚无足够前后对照。',
  NO_METRICS: '缺少有效的运行时间上报，仅能依据完整的双栈地址变化判断。',
  NO_BASELINE: '没有可用于比较的历史基线。',
};

export function useNodeIpSummaries(enabled: boolean) {
  const [summaries, setSummaries] = useState<Record<number, NodeIpSummary>>({});
  const [unavailable, setUnavailable] = useState(false);
  useEffect(() => {
    let active = true, busy = false;
    if (!enabled) { setSummaries({}); return; }
    async function refresh() {
      if (busy || document.hidden) return;
      busy = true;
      try {
        const response = await getNodeIpSummaries();
        if (!active) return;
        if (response.code !== 0 || !Array.isArray(response.data)) { setUnavailable(true); return; }
        setSummaries(Object.fromEntries(response.data.map(item => [item.nodeId, item])));
        setUnavailable(false);
      } catch { if (active) setUnavailable(true); }
      finally { busy = false; }
    }
    void refresh();
    const timer = window.setInterval(() => void refresh(), 60_000);
    const onVisible = () => { if (!document.hidden) void refresh(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => { active = false; window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisible); };
  }, [enabled]);
  return { summaries, unavailable };
}

function AddressPills({ addresses, empty = '尚未记录' }: { addresses: string[]; empty?: string }) {
  return <div className="ip-address-pills">{addresses.length ? addresses.map(address =>
    <span className={`ip-address-pill ${address.includes(':') ? 'is-v6' : ''}`} key={address}>
      <span>{address.includes(':') ? 'IPv6' : 'IPv4'}</span><code>{address}</code>
    </span>) : <span className="ip-muted">{empty}</span>}</div>;
}

export function NodeIpHistoryModal({ node, onClose }: { node: { id: number; name: string } | null; onClose: () => void }) {
  const [data, setData] = useState<NodeIpHistory | null>(null);
  const [loading, setLoading] = useState(false);
  const [moreLoading, setMoreLoading] = useState(false);
  const [error, setError] = useState('');
  const generation = useRef(0);
  const mounted = useRef(true);
  const paging = useRef(false);
  const selectedId = useRef(node?.id);
  selectedId.current = node?.id;

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);

  async function load(id: number, beforeId?: number) {
    if (beforeId && paging.current) return;
    const request = beforeId ? generation.current : ++generation.current;
    if (beforeId) { paging.current = true; setMoreLoading(true); } else { setLoading(true); paging.current = false; setMoreLoading(false); }
    setError('');
    try {
      const response = await getNodeIpHistory(id, beforeId);
      if (!mounted.current || selectedId.current !== id || request !== generation.current) return;
      if (response.code !== 0 || !response.data) {
        if (response.code === 403) setData(null);
        setError(response.msg || 'IP 记录暂时无法获取，请稍后重试'); return;
      }
      const incoming = response.data;
      setData(previous => beforeId && previous ? { ...incoming, events: [...previous.events, ...incoming.events.filter(e => !previous.events.some(old => old.id === e.id))] } : incoming);
    } catch { if (mounted.current && selectedId.current === id && request === generation.current) setError('网络连接异常，请稍后重试'); }
    finally {
      if (mounted.current && selectedId.current === id && request === generation.current) { setLoading(false); setMoreLoading(false); paging.current = false; }
    }
  }

  useEffect(() => {
    generation.current++; paging.current = false; setData(null); setError(''); setMoreLoading(false);
    if (node) void load(node.id); else setLoading(false);
  }, [node?.id]);

  const summary = data?.summary;
  const state = states[summary?.status || 'pending'] || states.pending;
  const confirmed = !!summary?.baselineAt;
  const maxCount = Math.max(1, ...data?.days.map(day => day.count) || [1]);
  return <Modal isOpen={!!node} onOpenChange={open => { if (!open) onClose(); }} size="4xl" scrollBehavior="inside" classNames={{ base: 'ip-history-modal', closeButton: 'ip-history-close' }}>
    <ModalContent>
      <ModalHeader className="ip-history-header">
        <div className="ip-history-symbol"><ConsoleIcon name="globe" size={25}/></div>
        <div><span className="ip-eyebrow">节点变化观察</span><h2>IP 与机器变化 <span>{node?.name}</span></h2></div>
      </ModalHeader>
      <ModalBody className="ip-history-body">
        <div className="ip-history-intro"><p>地址变化与机器变化分开记录，保留判断依据。</p><span>仅管理员 · 北京时间 · 保留 90 天</span></div>
        {error && <div role="alert" className="ip-history-error">{error}<Button size="sm" variant="light" onPress={() => node && void load(node.id)}>重新获取</Button></div>}
        {loading && !data ? <div className="ip-history-loading"><Spinner size="sm"/><span>正在读取地址记录…</span></div> : data && summary ? <>
          <div className="ip-stats-grid">
            <div className="ip-stat is-featured"><div className="ip-stat-label">今日检测到的变化<ConsoleIcon name="activity" size={18}/></div><div className="ip-stat-number">{confirmed ? summary.todayChanges : '—'}<span>次</span></div><small>{confirmed ? '首次采样与配置调整不计入' : '首次采样后开始统计'}</small></div>
            <div className="ip-stat"><div className="ip-stat-label">最近 7 天<ConsoleIcon name="forward" size={18}/></div><div className="ip-stat-number">{confirmed ? summary.weekChanges : '—'}<span>次</span></div><small>包含今天 · IPv4 / IPv6 合并计次</small></div>
            <div className="ip-stat ip-stat-last"><div className="ip-stat-label">最近一次变化<ConsoleIcon name="refresh" size={18}/></div><div className="ip-stat-time">{summary.lastChangeAt ? formatTime(summary.lastChangeAt, true) : '尚无变化记录'}</div><small>{summary.startedAt ? `开始观测于 ${formatTime(summary.startedAt, true)}` : '等待后台首次观测'}</small></div>
          </div>
          <div className="ip-classification-card">
            <div className="ip-section-heading"><h3>今日变化分类</h3><span className="ip-muted">机器判断为推测</span></div>
            <div className="ip-classification-grid">
              <div><span className="ip-classification-icon is-blue"><ConsoleIcon name="globe" size={19}/></span><strong>{data.todayClassification?.ipOnlyLikely ?? '—'}</strong><span>仅换 IP</span></div>
              <div><span className="ip-classification-icon is-amber"><ConsoleIcon name="activity" size={19}/></span><strong>{data.todayClassification?.machineSuspected ?? '—'}</strong><span>疑似换机</span></div>
              <div><span className="ip-classification-icon"><ConsoleIcon name="eye" size={19}/></span><strong>{data.todayClassification?.unknownIp ?? '—'}</strong><span>IP 已变 · 原因未明</span></div>
            </div>
            <p className="ip-observation-note">每个确认窗口只计一次地址变化；双栈一起变化归为疑似换机，重连不重复计数。旧记录保留原判定。{data.todayClassification?.ipNearMachine ? `今日另有 ${data.todayClassification.ipNearMachine} 次 IP 变化伴随疑似换机或重启。` : ''}</p>
          </div>
          <div className="ip-current-card">
            <div className="ip-section-heading"><div><span className="ip-eyebrow">当前地址</span><h3>{summary.target || '尚未配置地址'}</h3></div><span className={`ip-state is-${state.tone}`} title={state.detail}><i/>{summary.source === 'configured' && summary.status === 'ok' ? '配置地址已记录' : state.label}</span></div>
            <AddressPills addresses={summary.addresses} empty="等待有效的地址观测结果"/>
            <div className="ip-current-meta"><span><ConsoleIcon name="globe" size={14}/>{summary.source === 'configured' ? '来源：节点配置中的 IP' : summary.ddnsEnabled ? '来源：DDNS 域名解析' : '来源：服务器域名解析'}</span><span>最后检测 {formatTime(summary.checkedAt)}</span></div>
            {summary.status !== 'ok' && <p className="ip-observation-note">{state.detail}。{summary.addresses.length > 0 ? '上方显示上次确认的地址。' : ''}</p>}
          </div>
          <div className="ip-week-card"><div className="ip-section-heading"><h3>一周变化趋势</h3><span className="ip-muted">每日确认的变化次数</span></div>
            <div className="ip-week-chart" role="img" aria-label={data.days.map(d => `${d.date}：${d.count} 次`).join('；')}>
              {data.days.map((day, index) => <div className={`ip-chart-day ${index === 6 ? 'is-today' : ''}`} key={day.date}><span className="ip-chart-count">{day.count}</span><div className="ip-chart-track"><div className={day.count ? 'has-change' : ''} style={{ height: `${day.count ? Math.max(8, day.count / maxCount * 100) : 3}%` }}/></div><span className="ip-chart-label">{index === 6 ? '今天' : day.date.slice(5).replace('-', '/')}</span></div>)}
            </div>
          </div>
          <div className="ip-history-section"><div className="ip-section-heading"><h3>变化时间线 <span className="ip-count-badge">{data.events.length}{data.nextBeforeId ? '+' : ''}</span></h3><span className="ip-muted">最新记录在前</span></div>
            {!data.events.length ? <div className="ip-timeline-empty"><ConsoleIcon name="globe" size={30}/><strong>记录将从首次观测开始</strong><p>没有历史数据时不会补造记录，也不会把首次发现计作换 IP。</p></div> : <ol className="ip-timeline">
              {data.events.map(event => <li className={event.kind === 'CHANGE' ? 'is-change' : 'is-baseline'} key={event.id}>
                <span className="ip-timeline-dot"/><div className="ip-event-heading"><div><strong>{event.kind === 'CHANGE' ? (event.evidence?.reason === 'V4_ONLY_CHANGED' ? '仅换 IPv4' : event.evidence?.reason.startsWith('DUAL_STACK_CHANGED') ? '疑似换机' : '检测到 IP 变化') : event.kind === 'TARGET_CHANGED' ? '监测地址已调整' : event.kind === 'MACHINE_SUSPECTED' ? '疑似换机或重启' : event.kind === 'RECONNECTED' ? '节点重新连接' : event.kind === 'OBSERVATION_GAP' ? '观测中断后恢复' : '首次记录地址'}</strong><span className="ip-event-tag">{event.kind === 'CHANGE' ? '地址已确认' : event.kind === 'MACHINE_SUSPECTED' ? '推测 · 未核实' : '不计 IP 次数'}</span></div><time dateTime={new Date(event.confirmedAt).toISOString()}>{formatTime(event.confirmedAt)}</time></div>
                {(event.evidence || event.kind === 'CHANGE') && <div className={`ip-evidence ${event.evidence?.classification === 'MACHINE_SUSPECTED' ? 'is-amber' : ''}`}><strong>{event.evidence?.reason === 'V4_ONLY_CHANGED' ? '仅换 IPv4' : event.evidence?.reason.startsWith('DUAL_STACK_CHANGED') ? '疑似换机' : judgments[event.evidence?.classification || 'UNKNOWN'] || judgments.UNKNOWN}</strong><p>{event.evidence ? reasons[event.evidence.reason] || '证据不足，暂不判断换机。' : '早期记录未采集运行时间，无法追溯判断机器是否变化。'}</p></div>}
                <p className="ip-event-target">{event.target}</p>
                {event.kind === 'CHANGE' && <div className="ip-event-address"><span className="ip-event-side">变化前</span><AddressPills addresses={event.previousAddresses}/></div>}
                <div className="ip-event-address"><span className="ip-event-side">{event.kind === 'CHANGE' ? '变化后' : '地址'}</span><AddressPills addresses={event.addresses}/></div>
                {event.kind === 'CHANGE' && <p className="ip-event-footnote">首次发现 {formatTime(event.observedAt)} · 连续两次观测确认</p>}
              </li>)}
            </ol>}
            {data.nextBeforeId && <div className="ip-load-more"><Button variant="flat" size="sm" isLoading={moreLoading} isDisabled={loading} onPress={() => node && void load(node.id, data.nextBeforeId!)}>查看更早记录</Button></div>}
          </div>
          <div className="ip-history-explainer"><ConsoleIcon name="eye" size={17}/><p>约每分钟观测一次域名解析，变化后等待约两分钟，且新地址至少连续两次一致，再合并计次。缺失原有 IPv4 / IPv6 或解析失败时不计次数；检测时间不等于实际换 IP 时间，采样之间的变化可能未被记录。解析正常仅表示 DNS 查询成功，不能证明 DDNS 写入成功或转发连通。</p></div>
          <div className="ip-history-explainer"><ConsoleIcon name="activity" size={17}/><p>按当前机器的地址分配规律：仅 IPv4 变化、IPv6 不变，记为仅换 IP；IPv4、IPv6 一起变化，记为疑似换机。运行时长与重连只补充证据，不单独增加换机次数。缺少完整双栈对照时待确认；超过确认窗口才到达的地址变化可能分开记录。只读取现有数据，不操作节点，旧历史不重新推断。</p></div>
        </> : !loading && !error && <div className="ip-timeline-empty">暂时没有可显示的记录</div>}
      </ModalBody>
      <ModalFooter className="ip-history-footer"><span>记录由面板持续保存 · 关闭页面后继续观测</span><Button variant="flat" size="sm" isLoading={loading} isDisabled={moreLoading} startContent={!loading && <ConsoleIcon name="refresh" size={15}/>} onPress={() => node && void load(node.id)}>刷新记录</Button><Button color="primary" size="sm" onPress={onClose}>完成</Button></ModalFooter>
    </ModalContent>
  </Modal>;
}
