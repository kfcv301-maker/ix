import { parseProxyProtocol } from './forward-proxy-protocol.js';

/** Selective changes use the existing update API and never move rules or owners. */
export interface BulkForwardSource {
  id: number;
  userId?: number;
  name: string;
  tunnelId: number;
  inPort: number;
  remoteAddr: string;
  interfaceName?: string;
  strategy: string;
  proxyProtocol?: number;
  vpsHostId?: number;
  status: number;
  syncState?: string;
}

export type BulkForwardField = 'remoteAddr' | 'interfaceName' | 'strategy' | 'proxyProtocol';
export type BulkForwardPatch = Partial<Record<BulkForwardField, string>>;
type NormalizedBulkForwardPatch = Omit<BulkForwardPatch, 'proxyProtocol'> & { proxyProtocol?: number };
export interface BulkForwardAccess { isAdministrator: boolean; currentUserId: number | null }
export interface BulkForwardPayload {
  id: number;
  userId: number;
  name: string;
  tunnelId: number;
  inPort: number;
  remoteAddr: string;
  interfaceName: string;
  strategy: string;
  proxyProtocol: number;
}
export interface BulkForwardChange { field: BulkForwardField; before: string; after: string }
export interface BulkForwardPlanEntry {
  id: number;
  name: string;
  state: 'ready' | 'skipped';
  message: string;
  before?: BulkForwardSource;
  payload?: BulkForwardPayload;
  changes: BulkForwardChange[];
}
export interface BulkForwardResult {
  id: number;
  state: 'applying' | 'success' | 'failed' | 'conflict' | 'skipped';
  message: string;
}

export const bulkForwardFieldLabels: Record<BulkForwardField, string> = {
  remoteAddr: '目标地址', interfaceName: '出口接口 / IP', strategy: '负载策略', proxyProtocol: '发送 Proxy Protocol',
};

export function normalizeBulkTargetAddresses(value: string): string {
  const addresses = value.split(/[\n,]/).map(item => item.trim()).filter(Boolean);
  if (addresses.length === 0) throw new Error('请输入目标地址');
  for (const [index, address] of addresses.entries()) {
    const match = /^(\[[^\]]+\]|[^\s:\[\]/?#@]+):(\d+)$/.exec(address);
    if (!match || Number(match[2]) < 1 || Number(match[2]) > 65535) {
      throw new Error(`第 ${index + 1} 个目标必须是 IP / 域名:端口，端口范围 1–65535`);
    }
    const host = match[1];
    if (host.startsWith('[')) {
      try {
        // URL parses IPv6 locally; it performs no DNS lookup or request.
        const parsed = new URL(`http://${host}/`);
        if (!parsed.hostname.startsWith('[')) throw new Error();
      } catch {
        throw new Error(`第 ${index + 1} 个 IPv6 地址格式错误`);
      }
    } else if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
      if (host.split('.').some(part => Number(part) > 255)) {
        throw new Error(`第 ${index + 1} 个 IPv4 地址格式错误`);
      }
    } else if (host.length > 253 || !host.split('.').every(label =>
      /^[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$/.test(label))) {
      throw new Error(`第 ${index + 1} 个目标域名格式错误`);
    }
  }
  return addresses.join(',');
}

function normalizePatch(patch: BulkForwardPatch): NormalizedBulkForwardPatch {
  const next: NormalizedBulkForwardPatch = {};
  if (patch.remoteAddr !== undefined) next.remoteAddr = normalizeBulkTargetAddresses(patch.remoteAddr);
  if (patch.interfaceName !== undefined) {
    const value = patch.interfaceName.trim();
    if (value.length > 128 || /[\s\u0000-\u001f\u007f]/.test(value)) throw new Error('出口接口 / IP 不得包含空白或控制字符，长度不得超过 128');
    next.interfaceName = value;
  }
  if (patch.strategy !== undefined) {
    if (!['fifo', 'round', 'rand', 'hash'].includes(patch.strategy)) throw new Error('请选择有效的负载策略');
    next.strategy = patch.strategy;
  }
  if (patch.proxyProtocol !== undefined) next.proxyProtocol = parseProxyProtocol(patch.proxyProtocol);
  if (Object.keys(next).length === 0) throw new Error('请至少勾选一个要修改的字段');
  return next;
}

function blockedReason(source: BulkForwardSource, access: BulkForwardAccess): string | undefined {
  if (!Number.isInteger(Number(source.userId)) || Number(source.userId) <= 0) return '缺少规则归属，不能安全更新';
  if (!access.isAdministrator && (access.currentUserId === null || Number(source.userId) !== access.currentUserId)) return '没有该规则的编辑权限';
  if (source.syncState || source.status === 2 || source.status === 3) return '规则正在同步，暂不修改';
  // The current backend update endpoint changes a saved rule to active. Skip
  // paused and failed rules to preserve their state on both panel and Agent.
  if (source.status !== 1) return source.status === 0 ? '已暂停：为保留暂停状态，已跳过' : '规则状态异常，先单独处理';
  return undefined;
}

function configuration(source: BulkForwardSource) {
  return {
    userId: Number(source.userId), name: source.name, tunnelId: Number(source.tunnelId),
    inPort: Number(source.inPort), remoteAddr: source.remoteAddr,
    interfaceName: source.interfaceName || '', strategy: source.strategy || 'fifo',
    proxyProtocol: parseProxyProtocol(source.proxyProtocol),
    vpsHostId: source.vpsHostId == null ? null : Number(source.vpsHostId),
  };
}

export function planBulkForwardUpdates(
  sources: BulkForwardSource[], selectedIds: Iterable<number>, patch: BulkForwardPatch,
  access: BulkForwardAccess,
): BulkForwardPlanEntry[] {
  const normalized = normalizePatch(patch);
  const byId = new Map(sources.map(source => [Number(source.id), source]));
  return [...new Set(selectedIds)].map(id => {
    const source = byId.get(id);
    const skipped = (message: string): BulkForwardPlanEntry => ({ id, name: source?.name || `规则 #${id}`, state: 'skipped', message, changes: [] });
    if (!source) return skipped('规则已移除或不再可见');
    const reason = blockedReason(source, access);
    if (reason) return skipped(reason);
    const before = { ...source };
    const config = configuration(source);
    const payload: BulkForwardPayload = {
      id, userId: config.userId, name: config.name, tunnelId: config.tunnelId,
      inPort: config.inPort, remoteAddr: config.remoteAddr,
      interfaceName: config.interfaceName, strategy: config.strategy, proxyProtocol: config.proxyProtocol, ...normalized,
    };
    if (normalized.strategy && payload.remoteAddr.split(',').filter(Boolean).length < 2 && normalized.strategy !== 'fifo') {
      return skipped('单个目标不适用轮询 / 随机 / 哈希策略');
    }
    const changes = (Object.keys(normalized) as BulkForwardField[]).flatMap(field =>
      config[field] === payload[field] ? [] : [{ field, before: String(config[field]), after: String(payload[field]) }]);
    if (changes.length === 0) return skipped('勾选字段的值没有变化');
    return { id, name: source.name, state: 'ready', message: '待应用', before, payload, changes };
  });
}

/** Sequential requests limit load and preserve an independent outcome per rule. */
export async function executeBulkForwardUpdates(
  entries: BulkForwardPlanEntry[], access: BulkForwardAccess,
  api: { list: () => Promise<BulkForwardSource[]>; update: (payload: BulkForwardPayload) => Promise<{ code: number; msg?: string }> },
  report: (result: BulkForwardResult) => void,
): Promise<void> {
  for (const entry of entries) {
    const emit = (state: BulkForwardResult['state'], message: string) => report({ id: entry.id, state, message });
    if (entry.state !== 'ready' || !entry.before || !entry.payload) {
      emit('skipped', entry.message);
      continue;
    }
    emit('applying', '正在检查最新配置');
    try {
      const current = (await api.list()).find(source => Number(source.id) === entry.id);
      if (!current) { emit('conflict', '规则已移除或权限已变化，请重新预览'); continue; }
      const reason = blockedReason(current, access);
      if (reason) { emit('conflict', `${reason}；请重新预览`); continue; }
      const currentConfig = JSON.stringify(configuration(current));
      const expected = { ...entry.before, ...entry.payload };
      if (currentConfig === JSON.stringify(configuration(expected))) {
        // A lost HTTP response may have followed a successful update. A retry
        // checks the server first and must not rebuild the same service again.
        emit('skipped', '目标配置已生效，未重复下发');
        continue;
      }
      if (currentConfig !== JSON.stringify(configuration(entry.before))) {
        emit('conflict', '配置自预览后已改变，请重新预览，避免覆盖其他修改');
        continue;
      }
      // The legacy endpoint has no version precondition. This check detects
      // changes already visible to the list API; it is not an atomic lock.
      const response = await api.update({ ...entry.payload });
      emit(response.code === 0 ? 'success' : 'failed', response.msg || (response.code === 0 ? '修改成功' : '修改失败，可单独重试'));
    } catch (error) {
      emit('failed', error instanceof Error ? error.message : '网络请求失败，可单独重试');
    }
  }
}
