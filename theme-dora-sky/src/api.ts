import type { ApiResponse, CaptchaState, ForwardInfo, LoginPayload, LoginResult, NodeInfo, SiteConfig, SpeedLimitInfo, TunnelInfo, UserInfo } from './types';

const API_BASE = `${(import.meta.env.VITE_API_BASE || '').replace(/\/$/, '')}/api/v1`;

async function request<T>(path: string, body: unknown = {}): Promise<ApiResponse<T>> {
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: localStorage.getItem('token') || '' },
      body: JSON.stringify(body ?? {}),
    });
    const result = (await response.json()) as ApiResponse<T>;
    if (response.status === 401 || result.code === 401) {
      localStorage.removeItem('token'); localStorage.removeItem('role_id'); localStorage.removeItem('name');
      if (location.pathname !== '/') location.href = '/';
    }
    return result;
  } catch (error) {
    return { code: -1, msg: error instanceof Error ? error.message : '网络请求失败', data: null as T };
  }
}

export const api = {
  login: (data: LoginPayload) => request<LoginResult>('/user/login', data),
  captchaCheck: () => request<CaptchaState>('/captcha/check'),
  captchaGenerate: () => request<CaptchaState>('/captcha/generate'),
  captchaVerify: (captchaId: string, trackData: string) => request('/captcha/verify', { captchaId, trackData }),
  nodes: () => request<NodeInfo[]>('/node/list'),
  createNode: (data: Partial<NodeInfo>) => request('/node/create', data),
  updateNode: (data: Partial<NodeInfo>) => request('/node/update', data),
  deleteNode: (id: number) => request('/node/delete', { id }),
  nodeInstall: (id: number, options: { ddnsEnabled?: boolean; cfApiToken?: string; cfRecordName?: string } = {}) => request<string>('/node/install', { id, ...options }),
  tunnels: () => request<TunnelInfo[]>('/tunnel/list'),
  createTunnel: (data: Partial<TunnelInfo>) => request('/tunnel/create', data),
  updateTunnel: (data: Partial<TunnelInfo>) => request('/tunnel/update', data),
  deleteTunnel: (id: number) => request('/tunnel/delete', { id }),
  diagnoseTunnel: (tunnelId: number) => request('/tunnel/diagnose', { tunnelId }),
  forwards: () => request<ForwardInfo[]>('/forward/list'),
  createForward: (data: Partial<ForwardInfo>) => request('/forward/create', data),
  updateForward: (data: Partial<ForwardInfo>) => request('/forward/update', data),
  deleteForward: (id: number) => request('/forward/delete', { id }),
  forceDeleteForward: (id: number) => request('/forward/force-delete', { id }),
  pauseForward: (id: number) => request('/forward/pause', { id }),
  resumeForward: (id: number) => request('/forward/resume', { id }),
  diagnoseForward: (forwardId: number) => request('/forward/diagnose', { forwardId }),
  reorderForwards: (forwards: Array<{ id: number; inx: number }>) => request('/forward/update-order', { forwards }),
  users: (pageData: unknown = {}) => request<UserInfo[]>('/user/list', pageData),
  createUser: (data: Partial<UserInfo> & { pwd?: string }) => request('/user/create', data),
  updateUser: (data: Partial<UserInfo> & { pwd?: string }) => request('/user/update', data),
  deleteUser: (id: number) => request('/user/delete', { id }),
  userPackage: () => request('/user/package'),
  resetUserFlow: (id: number, type: number) => request('/user/reset', { id, type }),
  limits: () => request<SpeedLimitInfo[]>('/speed-limit/list'),
  createLimit: (data: Partial<SpeedLimitInfo>) => request('/speed-limit/create', data),
  updateLimit: (data: Partial<SpeedLimitInfo>) => request('/speed-limit/update', data),
  deleteLimit: (id: number) => request('/speed-limit/delete', { id }),
  configs: () => request<SiteConfig[]>('/config/list'),
  config: (name: string) => request<string>('/config/get', { name }),
  updateConfig: (name: string, value: string) => request('/config/update-single', { name, value }),
};

export const unwrapList = <T>(response: ApiResponse<T[] | { records?: T[]; list?: T[] }>): T[] => {
  const value = response.data;
  if (Array.isArray(value)) return value;
  if (value && Array.isArray(value.records)) return value.records;
  if (value && Array.isArray(value.list)) return value.list;
  return [];
};
