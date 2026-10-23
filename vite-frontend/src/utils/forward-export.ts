import { parseProxyProtocol } from './forward-proxy-protocol.js';

export type ForwardExportScope = 'tunnel' | 'filtered';
export interface ForwardExportSource {
  name: string;
  tunnelId: number;
  remoteAddr: string;
  inPort?: number | null;
  strategy?: string;
  proxyProtocol?: number;
  interfaceName?: string;
}

/** Inputs already belong to the current user's permitted list and view. */
export function selectForwardExportRecords<T extends ForwardExportSource>(
  scope: ForwardExportScope, tunnelId: number | null, all: T[], filtered: T[],
): T[] {
  return scope === 'filtered' ? [...filtered]
    : tunnelId === null ? [] : all.filter(forward => forward.tunnelId === tunnelId);
}

export function parseForwardInterface(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value !== 'string') throw new Error('出口接口 / IP 必须是文本');
  const result = value.trim();
  if (result.length > 128 || /[\s\u0000-\u001f\u007f]/.test(result)) {
    throw new Error('出口接口 / IP 不得包含空白或控制字符，长度不得超过 128');
  }
  return result;
}

export function createForwardExport(records: ForwardExportSource[], tunnelId: number | null, now = new Date()) {
  if (records.length === 0) throw new Error('当前导出范围没有转发数据');
  return {
    format: 'flux-panel-forward-export',
    version: 2,
    exportedAt: now.toISOString(),
    tunnelId,
    // Allow only portable rule settings. Never spread API records: they also
    // contain private infrastructure addresses and owner/association metadata.
    forwards: records.map(forward => ({
      name: forward.name,
      remoteAddr: forward.remoteAddr,
      inPort: forward.inPort ?? null,
      interfaceName: parseForwardInterface(forward.interfaceName),
      strategy: forward.strategy || 'fifo',
      proxyProtocol: parseProxyProtocol(forward.proxyProtocol),
    })),
  };
}

export interface ForwardImportItem {
  line: string;
  name?: unknown;
  remoteAddr?: unknown;
  inPort?: unknown;
  interfaceName?: unknown;
  strategy?: unknown;
  proxyProtocol?: unknown;
}

export function parseForwardImport(raw: string): ForwardImportItem[] {
  const input = raw.trim();
  if (input.startsWith('{')) {
    const bundle = JSON.parse(input);
    if (bundle?.format !== 'flux-panel-forward-export' || bundle?.version !== 2 || !Array.isArray(bundle.forwards)) {
      throw new Error('JSON 导入文件不是受支持的 v2 转发导出格式');
    }
    return bundle.forwards.map((item: unknown) => {
      const value = item && typeof item === 'object' ? item as Record<string, unknown> : {};
      return { line: JSON.stringify(value), name: value.name, remoteAddr: value.remoteAddr,
        inPort: value.inPort, interfaceName: value.interfaceName, strategy: value.strategy, proxyProtocol: value.proxyProtocol };
    });
  }
  // The legacy fourth column is display-only and must not override ingress.
  return input.split('\n').filter(line => line.trim()).map(line => {
    const [remoteAddr, name, inPort] = line.trim().split('|');
    return { line: line.trim(), remoteAddr, name, inPort, strategy: 'fifo' };
  });
}
