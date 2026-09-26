import Network from './network';

// 登陆相关接口
export interface LoginData {
  username: string;
  password: string;
  captchaId: string;
}

export interface LoginResponse {
  token: string;
  role_id: number;
  name: string;
  requirePasswordChange?: boolean;
}

export const login = (data: LoginData) => Network.post<LoginResponse>("/user/login", data);

// 用户CRUD操作 - 全部使用POST请求
export const createUser = (data: any) => Network.post("/user/create", data);
export const getAllUsers = (pageData: any = {}) => Network.post("/user/list", pageData);
export const updateUser = (data: any) => Network.post("/user/update", data);
export const deleteUser = (id: number) => Network.post("/user/delete", { id });
export const getUserPackageInfo = () => Network.post("/user/package");
export const getSubscriptionToken = () => Network.post<{ user: string; token: string }>("/user/subscription-token");

// VPS 托管：后端按当前登录用户的归属与分配权限过滤数据。
export const getVpsHosts = () => Network.post("/vps/list");
export const createVpsHost = (data: any) => Network.post("/vps/create", data);
export const updateVpsHost = (data: any) => Network.post("/vps/update", data);
export const deleteVpsHost = (id: number) => Network.post("/vps/delete", { id });
export const checkVpsHost = (id: number) => Network.post("/vps/check", { id });
export interface VpsTerminalTicket {
  terminalTicket: string;
  expiresAt: number;
}
export const createVpsTerminalTicket = (id: number) => Network.post<VpsTerminalTicket>("/vps/terminal-ticket", { id });
export const resetVpsHostFingerprint = (id: number) => Network.post("/vps/reset-fingerprint", { id });
export const confirmVpsHostFingerprint = (id: number, fingerprint: string) => Network.post("/vps/confirm-fingerprint", { id, fingerprint });
export const getVpsAssignableUsers = () => Network.post("/vps/assignable-users");
export const installVpsBackend = (id: number) => Network.post("/vps/deploy", { id });
export const getVpsDeploymentTasks = (id: number) => Network.post("/vps/tasks", { id });

export interface RealtimeTicket {
  ticket: string;
  expiresAt: number;
}
export const createRealtimeTicket = () => Network.post<RealtimeTicket>("/realtime/ticket");

// 节点CRUD操作 - 全部使用POST请求
export const createNode = (data: any) => Network.post("/node/create", data);
export const getNodeList = () => Network.post("/node/list");
export const updateNode = (data: any) => Network.post("/node/update", data);
export const deleteNode = (id: number) => Network.post("/node/delete", { id });
export interface NodeInstallOptions {
  /** Legacy browser bundles may still send these. Saved node settings take precedence. */
  ddnsEnabled?: boolean;
  cfApiToken?: string;
  cfRecordName?: string;
  tcpTuningProfile?: 'tiny' | 'small' | 'balanced' | 'standard';
  /** The public origin shown in the administrator's browser, used for Agent callbacks. */
  panelUrl?: string;
}

export const getNodeInstallCommand = (id: number, options: NodeInstallOptions = {}) => Network.post("/node/install", { id, ...options });
export const getPanelUpdateStatus = () => Network.post("/panel-update/status");
export const startPanelUpdate = () => Network.post("/panel-update/start");
export const checkNodeStatus = (nodeId?: number) => {
  const params = nodeId ? { nodeId } : {};
  return Network.post("/node/check-status", params);
};

// 隧道CRUD操作 - 全部使用POST请求
export const createTunnel = (data: any) => Network.post("/tunnel/create", data);
export const getTunnelList = () => Network.post("/tunnel/list");
export const getTunnelById = (id: number) => Network.post("/tunnel/get", { id });
export const updateTunnel = (data: any) => Network.post("/tunnel/update", data);
export const deleteTunnel = (id: number) => Network.post("/tunnel/delete", { id });
export const diagnoseTunnel = (tunnelId: number) => Network.post("/tunnel/diagnose", { tunnelId });
export const diagnoseTunnelForwards = (tunnelId: number) => Network.post("/tunnel/diagnose-forwards", { tunnelId });
export const getTunnelForwardDiagnosisTask = (taskId: string) => Network.post("/tunnel/diagnose-forwards/task", { taskId });
export const cancelTunnelForwardDiagnosisTask = (taskId: string) => Network.post("/tunnel/diagnose-forwards/cancel", { taskId });

// 隧道解析域名池：仅管理用户可见入口地址，不会修改 DDNS 或节点配置。
export const getTunnelEntryDomains = (tunnelId: number) => Network.post("/tunnel/domain/list", { tunnelId });
export const createTunnelEntryDomain = (data: { tunnelId: number; domain: string; defaultDomain?: boolean }) =>
  Network.post("/tunnel/domain/create", data);
export const setDefaultTunnelEntryDomain = (id: number) => Network.post("/tunnel/domain/set-default", { id });
export const deleteTunnelEntryDomain = (id: number) => Network.post("/tunnel/domain/delete", { id });

// 用户隧道权限管理操作 - 全部使用POST请求
export const assignUserTunnel = (data: any) => Network.post("/tunnel/user/assign", data);
export const getUserTunnelList = (queryData: any = {}) => Network.post("/tunnel/user/list", queryData);
export const removeUserTunnel = (params: any) => Network.post("/tunnel/user/remove", params);
export const updateUserTunnel = (data: any) => Network.post("/tunnel/user/update", data);
export const userTunnel = () => Network.post("/tunnel/user/tunnel");

// 转发CRUD操作 - 全部使用POST请求
export const createForward = (data: any) => Network.post("/forward/create", data);
export const getForwardList = () => Network.post("/forward/list");
export const updateForward = (data: any) => Network.post("/forward/update", data);
export const deleteForward = (id: number) => Network.post("/forward/delete", { id });
export const forceDeleteForward = (id: number) => Network.post("/forward/force-delete", { id });

// 转发服务控制操作 - 通过Java后端接口
export const pauseForwardService = (forwardId: number) => Network.post("/forward/pause", { id: forwardId });
export const resumeForwardService = (forwardId: number) => Network.post("/forward/resume", { id: forwardId });

// 转发诊断操作
export const diagnoseForward = (forwardId: number) => Network.post("/forward/diagnose", { forwardId });

// 转发排序操作
export const updateForwardOrder = (data: { forwards: Array<{ id: number; inx: number }> }) => Network.post("/forward/update-order", data);

// 限速规则CRUD操作 - 全部使用POST请求
export const createSpeedLimit = (data: any) => Network.post("/speed-limit/create", data);
export const getSpeedLimitList = () => Network.post("/speed-limit/list");
export const updateSpeedLimit = (data: any) => Network.post("/speed-limit/update", data);
export const deleteSpeedLimit = (id: number) => Network.post("/speed-limit/delete", { id });

// 修改密码接口
export const updatePassword = (data: any) => Network.post("/user/updatePassword", data);

// 重置流量接口
export const resetUserFlow = (data: { id: number; type: number }) => Network.post("/user/reset", data);

// 网站配置相关接口
export const getConfigs = () => Network.post("/config/list");
export const getConfigByName = (name: string) => Network.post("/config/get", { name });
export const updateConfigs = (configMap: Record<string, string>) => Network.post("/config/update", configMap);
export const updateConfig = (name: string, value: string) => Network.post("/config/update-single", { name, value });


// 验证码相关接口
export const checkCaptcha = () => Network.post("/captcha/check");
export const generateCaptcha = () => Network.post(`/captcha/generate`);
export const verifyCaptcha = (data: { captchaId: string; trackData: string }) => Network.post("/captcha/verify", data);
