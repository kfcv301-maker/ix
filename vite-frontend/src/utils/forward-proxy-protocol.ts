export type ProxyProtocolVersion = 0 | 1 | 2;

export const PROXY_PROTOCOL_DESCRIPTION = '仅 TCP 生效。真实 IP 通过 PROXY 报头传递，目标的 TCP 对端仍是节点 IP。目标须开启对应版本接收，未开启可能连接失败。';

/** Older rules and exports omit the setting and keep their original TCP bytes. */
export function parseProxyProtocol(value: unknown): ProxyProtocolVersion {
  if (value === undefined || value === null) return 0;
  if (value === 0 || value === 1 || value === 2 || value === '0' || value === '1' || value === '2') {
    return Number(value) as ProxyProtocolVersion;
  }
  throw new Error('Proxy Protocol 必须为关闭、PROXY v1 或 PROXY v2');
}

export function proxyProtocolLabel(value: unknown): string {
  const version = parseProxyProtocol(value);
  return version === 0 ? '关闭' : `PROXY v${version}`;
}

/** A configured version does not establish that the destination parsed it. */
export function proxyProtocolDiagnosisNotice(value: unknown): string | null {
  if (value !== 1 && value !== 2 && value !== '1' && value !== '2') return null;
  return `${proxyProtocolLabel(value)} · 目标接收与真实 IP 识别未验证`;
}
