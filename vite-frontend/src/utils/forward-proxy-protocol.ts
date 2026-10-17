export type ProxyProtocolVersion = 0 | 1 | 2;

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
