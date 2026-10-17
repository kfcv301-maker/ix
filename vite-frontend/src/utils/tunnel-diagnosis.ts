export const TCP_DIAGNOSIS_NOTICE = '仅验证 TCP 端口连通，未验证转发业务、目标解析 Proxy Protocol 或真实 IP 识别。';

export type DiagnosisStatus = 'queued' | 'running' | 'cancelling' | 'completed' | 'cancelled' | 'timed_out' | 'failed';
export interface PingCheck {
  success: boolean;
  description: string;
  nodeName: string;
  nodeId: string;
  targetIp: string;
  targetPort?: number;
  message?: string;
  averageTime?: number;
  packetLoss?: number;
}
export interface TunnelPingForwardReport {
  forwardId: number;
  forwardName: string;
  remoteAddress?: string;
  proxyProtocol?: number;
  success: boolean;
  outcome?: 'passed' | 'failed' | 'cancelled' | 'timed_out' | 'not_run';
  message?: string;
  results: PingCheck[];
}
export interface TunnelPingSummary {
  taskId?: string;
  status?: DiagnosisStatus;
  completedForwards?: number;
  skippedForwards?: number;
  batchSize?: number;
  currentBatch?: number;
  completedBatches?: number;
  totalBatches?: number;
  deadlineAt?: number;
  message?: string;
  tunnelId: number;
  tunnelName: string;
  totalForwards: number;
  successfulForwards: number;
  failedForwards: number;
  forwards: TunnelPingForwardReport[];
}
export interface DiagnosisState {
  summary: TunnelPingSummary | null;
  loading: boolean;
  cancelling: boolean;
  error: string | null;
}
type Response = { code: number; msg?: string; data?: TunnelPingSummary };
interface DiagnosisApi {
  start: (tunnelId: number) => Promise<Response>;
  get: (taskId: string) => Promise<Response>;
  cancel: (taskId: string) => Promise<Response>;
}
interface Scheduler {
  set: (callback: () => void) => unknown;
  clear: (handle: unknown) => void;
}
const terminal = new Set<DiagnosisStatus>(['completed', 'cancelled', 'timed_out', 'failed']);
export const emptyDiagnosisState = (): DiagnosisState => ({ summary: null, loading: false, cancelling: false, error: null });
export const isDiagnosisTerminal = (summary: TunnelPingSummary) => !!summary.status && terminal.has(summary.status);

export const diagnosisProgressText = (summary: TunnelPingSummary | null, loading: boolean) => {
  if (!summary) return loading ? '正在创建检测任务…' : '暂无检测结果';
  const statuses: Record<DiagnosisStatus, string> = {
    queued: '等待检测', running: '正在检测', cancelling: '正在取消', completed: '检测完成',
    cancelled: '已取消', timed_out: '检测超时', failed: '任务中止',
  };
  const status = summary.status ? statuses[summary.status] : loading ? '正在检测' : '检测完成';
  const completed = summary.completedForwards ?? summary.forwards.length;
  const batch = summary.totalBatches ? ` · ${summary.currentBatch ? `第 ${summary.currentBatch}/${summary.totalBatches} 批` : `共 ${summary.totalBatches} 批`}` : '';
  return `${status} · 已检测 ${completed}/${summary.totalForwards} 条${batch}${summary.skippedForwards ? ` · ${summary.skippedForwards} 条未检测` : ''}`;
};

/** Serial polling with generation ownership: closing/restarting cannot leak a late task. */
export function createTunnelDiagnosisController(api: DiagnosisApi, changed: (state: DiagnosisState) => void,
  scheduler: Scheduler = {
    set: callback => globalThis.setTimeout(callback, 1200),
    clear: handle => globalThis.clearTimeout(handle as ReturnType<typeof setTimeout>),
  }) {
  let state = emptyDiagnosisState();
  let generation = 0;
  let taskId: string | null = null;
  let timer: unknown;
  const emit = (patch: Partial<DiagnosisState>) => { state = { ...state, ...patch }; changed(state); };
  const clearTimer = () => { if (timer !== undefined) scheduler.clear(timer); timer = undefined; };
  const cancelQuietly = (id: string) => { void api.cancel(id).catch(() => undefined); };
  const close = (notify = true) => {
    generation++; clearTimer();
    if (taskId) cancelQuietly(taskId);
    taskId = null;
    state = emptyDiagnosisState();
    if (notify) changed(state);
  };
  const accept = (summary: TunnelPingSummary) => {
    const done = isDiagnosisTerminal(summary) || !summary.taskId;
    taskId = done ? null : summary.taskId!;
    emit({ summary, loading: !done, cancelling: false, error: null });
    return !done;
  };
  const schedule = (owner: number) => {
    if (owner !== generation || !taskId) return;
    clearTimer();
    timer = scheduler.set(() => { timer = undefined; void poll(owner); });
  };
  const poll = async (owner: number) => {
    if (owner !== generation || !taskId || state.cancelling) return;
    const requestedTask = taskId;
    try {
      const response = await api.get(requestedTask);
      if (owner !== generation || taskId !== requestedTask) return;
      if (state.cancelling) return;
      if (response.code === 0 && response.data) {
        if (accept(response.data)) schedule(owner);
      } else if (response.code === 403 || response.code === 404
        || (response.code === -1 && response.msg === '诊断任务不存在或已过期')) {
        taskId = null;
        emit({ loading: false, cancelling: false, error: response.msg || '检测任务不存在或无权查看' });
      } else {
        emit({ error: response.msg || '暂未取到最新进度，将自动重试。' }); schedule(owner);
      }
    } catch {
      if (owner !== generation || taskId !== requestedTask || state.cancelling) return;
      emit({ error: '暂未取到最新进度，将自动重试。' }); schedule(owner);
    }
  };
  const start = async (input: { tunnelId: number; tunnelName: string; totalForwards?: number }) => {
    close(false);
    const owner = generation;
    emit({ loading: true, summary: { ...input, status: 'queued', totalForwards: input.totalForwards ?? 0,
      successfulForwards: 0, failedForwards: 0, completedForwards: 0, forwards: [] } });
    try {
      const response = await api.start(input.tunnelId);
      if (owner !== generation) {
        if (response.code === 0 && response.data?.taskId && !isDiagnosisTerminal(response.data)) cancelQuietly(response.data.taskId);
        return;
      }
      if (response.code === 0 && response.data) { if (accept(response.data)) schedule(owner); }
      else emit({ loading: false, summary: state.summary ? { ...state.summary, status: 'failed' } : null, error: response.msg || '无法开始检测' });
    } catch {
      if (owner === generation) emit({ loading: false, summary: state.summary ? { ...state.summary, status: 'failed' } : null,
        error: '无法连接服务器，未能确认检测任务是否创建，请稍后重试。' });
    }
  };
  const cancel = async () => {
    if (!taskId || state.cancelling) return;
    const owner = generation;
    const requestedTask = taskId;
    clearTimer(); emit({ cancelling: true, error: null });
    try {
      const response = await api.cancel(requestedTask);
      if (owner !== generation || taskId !== requestedTask) return;
      if (response.code === 0 && response.data) { if (accept(response.data)) schedule(owner); }
      else { emit({ cancelling: false, error: response.msg || '未能确认取消，请重试' }); schedule(owner); }
    } catch {
      if (owner !== generation || taskId !== requestedTask) return;
      emit({ cancelling: false, error: '未能确认取消，任务仍可能运行，请重试。' }); schedule(owner);
    }
  };
  return { start, cancel, close };
}
