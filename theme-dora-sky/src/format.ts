export const bytes = (value?: number) => { if (!value || value < 0) return '0 B'; const u=['B','KB','MB','GB','TB']; let v=value,i=0; while(v>=1024&&i<u.length-1){v/=1024;i++} return `${v.toFixed(i>1?1:0)} ${u[i]}` };
export const percent = (value?: number) => `${Math.max(0,Math.min(100,value||0)).toFixed(1)}%`;
export const dateTime = (value?: number) => value ? new Date(value < 1e12 ? value*1000 : value).toLocaleString('zh-CN') : '永久';
export const isAdmin = () => Number(localStorage.getItem('role_id')) === 0;
