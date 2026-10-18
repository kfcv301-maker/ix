import type { ReactNode } from 'react';

const paths: Record<string, ReactNode> = {
  dashboard: <><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="4" rx="1.5"/><rect x="14" y="11" width="7" height="10" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/></>,
  forward: <><path d="M4 7h15m-4-4 4 4-4 4M20 17H5m4-4-4 4 4 4"/></>,
  tunnel: <><path d="m10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0m2 1 1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0" transform="translate(1 0)"/></>,
  node: <><rect x="3" y="3" width="18" height="7" rx="2"/><rect x="3" y="14" width="18" height="7" rx="2"/><path d="M7 6.5h.01M7 17.5h.01M11 6.5h6M11 17.5h6"/></>,
  vps: <><rect x="3" y="3" width="18" height="13" rx="2"/><path d="M8 21h8m-4-5v5m-5-13 3 2-3 2m6 0h3"/></>,
  user: <><circle cx="9" cy="8" r="3"/><path d="M3 21v-2a6 6 0 0 1 12 0v2m2-16a3 3 0 0 1 0 6m1 4a5 5 0 0 1 3 4v2"/></>,
  profile: <><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></>,
  limit: <><path d="M4 18a9 9 0 1 1 16 0M12 13l5-5M5 7l1 1m12-1-1 1M12 3v2"/><circle cx="12" cy="13" r="1"/></>,
  config: <><path d="M4 7h9m4 0h3M4 17h3m4 0h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></>,
  settings: <><path d="M4 7h9m4 0h3M4 17h3m4 0h9"/><circle cx="15" cy="7" r="2"/><circle cx="9" cy="17" r="2"/></>,
  search: <><circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/></>,
  plus: <path d="M12 5v14M5 12h14"/>,
  chevron: <path d="m9 5 7 7-7 7"/>,
  menu: <path d="M4 6h16M4 12h16M4 18h16"/>,
  panel: <><rect x="3" y="3" width="18" height="18" rx="3"/><path d="M9 3v18m5-13 3 4-3 4"/></>,
  close: <path d="m6 6 12 12M6 18 18 6"/>,
  arrow: <path d="M5 12h14m-5-5 5 5-5 5"/>,
  theme: <><path d="M21 12a9 9 0 1 1-9-9c1 0 2 .2 3 .5a3 3 0 0 0 5.5 5.5c.3 1 .5 2 .5 3Z"/><path d="M7 11h.01M10 7h.01M7 16h.01"/></>,
  logout: <><path d="M9 3H4v18h5m5-14 5 5-5 5m-7-5h12"/></>,
  edit: <><path d="m14 5 5 5M3 21l5-1L21 7a2 2 0 0 0-5-5L3 15v6Z"/></>,
  activity: <path d="M2 12h5l3-8 4 16 3-8h5"/>,
  more: <><circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/></>,
  grid: <><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></>,
  list: <><path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/></>,
  lock: <><rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 5v2"/></>,
  refresh: <><path d="M20 7v5h-5M4 17v-5h5M5 7a8 8 0 0 1 13-2l2 3M4 16l2 3a8 8 0 0 0 13-2"/></>,
  upload: <path d="M12 20V4m-5 5 5-5 5 5"/>,
  download: <path d="M12 4v16m-5-5 5 5 5-5"/>,
  globe: <><circle cx="12" cy="12" r="9"/><ellipse cx="12" cy="12" rx="4" ry="9"/><path d="M3 12h18"/></>,
};

export function ConsoleIcon({ name, size = 18, className = '' }: { name: string; size?: number; className?: string }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" className={className}>{paths[name] || paths.node}</svg>;
}

export function ConsoleHeading({ title, description, children }: { title: string; description?: string; children?: ReactNode }) {
  return <div className="console-page-heading"><div><h1>{title}</h1>{description && <p>{description}</p>}</div>{children && <div className="console-page-actions">{children}</div>}</div>;
}

export function ConsoleSearch({ value, onChange, placeholder = '搜索名称、地址…' }: { value: string; onChange: (value: string) => void; placeholder?: string }) {
  return <div className="console-search-field"><ConsoleIcon name="search" size={16}/><input aria-label={placeholder} placeholder={placeholder} value={value} onChange={event => onChange(event.target.value)} type="search"/>{value && <button type="button" onClick={() => onChange('')} aria-label="清空搜索"><ConsoleIcon name="close" size={14}/></button>}</div>;
}

export function ConsoleStats({ items }: { items: { label: string; value: ReactNode; detail?: string; tone?: string }[] }) {
  return <div className="console-stats">{items.map(item => <div className="console-stat" key={item.label}><span className="console-stat-label">{item.label}</span><strong className={item.tone ? `console-tone-${item.tone}` : ''}>{item.value}</strong>{item.detail && <span className="console-stat-detail">{item.detail}</span>}</div>)}</div>;
}

export function ConsoleEmpty({ onClear }: { onClear: () => void }) {
  return <div className="console-filter-empty"><ConsoleIcon name="search" size={28}/><strong>没有匹配的结果</strong><p>试试其他关键词，或清空筛选条件。</p><button type="button" onClick={onClear}>清空筛选</button></div>;
}
