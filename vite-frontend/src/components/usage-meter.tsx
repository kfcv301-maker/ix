export function UsageMeter({ percentage, unlimited = false, size = 'md', label = '已用占比' }: {
  percentage: number;
  unlimited?: boolean;
  size?: 'sm' | 'md';
  label?: string;
}) {
  const value = Number.isFinite(percentage) ? Math.max(0, percentage) : 0;
  const tone = !unlimited && value >= 90 ? 'danger' : !unlimited && value >= 70 ? 'warning' : 'normal';
  return <div className={`usage-meter usage-meter-${size} usage-meter-${tone}${unlimited ? ' is-unlimited' : ''}`}>
    <div className="usage-meter-caption"><span>{label}</span><strong>{unlimited ? '不限量' : `${value.toFixed(1)}%`}</strong></div>
    {unlimited ? <div className="usage-meter-track" role="img" aria-label="流量或转发额度不限量"><span className="usage-meter-fill"/></div>
      : <div className="usage-meter-track" role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.min(value, 100)} aria-valuetext={`已使用 ${value.toFixed(1)}%`}><span className="usage-meter-fill" style={{ width: `${Math.min(value, 100)}%` }}/></div>}
  </div>;
}
