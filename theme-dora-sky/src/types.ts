export interface ApiResponse<T = unknown> { code: number; msg: string; data: T }
export interface LoginPayload { username: string; password: string; captchaId: string }
export interface LoginResult { token: string; role_id: number; name: string; requirePasswordChange?: boolean }
export interface CaptchaState { enabled?: boolean; captchaEnabled?: boolean; id?: string; captchaId?: string; image?: string; backgroundImage?: string; [key: string]: unknown }
export interface NodeInfo {
  id: number; name: string; ip?: string; serverIp?: string; secret?: string; version?: string;
  status?: number; online?: boolean; portSta?: number; portEnd?: number; http?: number; tls?: number; socks?: number;
  uptime?: number; bytesReceived?: number; bytesTransmitted?: number; cpuUsage?: number; memoryUsage?: number;
  cpuCores?: number; memoryTotal?: number; memoryUsed?: number; memoryAvailable?: number; diskTotal?: number;
  diskUsed?: number; diskUsedPercent?: number; load1?: number; tcpConnections?: number; udpConnections?: number;
  agentRss?: number; goroutines?: number; timestamp?: number; [key: string]: unknown;
}
export interface TunnelInfo {
  id: number; name: string; inNodeId?: number; entryNodeIds?: number[]; entryNodes?: NodeInfo[]; inIp?: string;
  outNodeId?: number; outIp?: string; type?: number; flow?: number; protocol?: string; trafficRatio?: number;
  tcpListenAddr?: string; udpListenAddr?: string; interfaceName?: string; status?: number; [key: string]: unknown;
}
export interface ForwardInfo {
  id: number; name: string; userId?: number; userName?: string; tunnelId?: number; tunnelName?: string;
  inIp?: string; inPort?: number; remoteAddr?: string; strategy?: string; interfaceName?: string;
  status?: number; inFlow?: number; outFlow?: number; inx?: number; createdTime?: number; updatedTime?: number;
  [key: string]: unknown;
}
export interface UserInfo {
  id: number; user: string; name?: string; roleId?: number; status?: number; expTime?: number; flow?: number;
  inFlow?: number; outFlow?: number; num?: number; flowResetTime?: number; createdTime?: number; [key: string]: unknown;
}
export interface SpeedLimitInfo { id: number; name: string; speed: number; tunnelId?: number; tunnelName?: string; status?: number; [key: string]: unknown }
export interface SiteConfig { id?: number; name: string; value: string; time?: number }
export interface DashboardData { nodes: NodeInfo[]; tunnels: TunnelInfo[]; forwards: ForwardInfo[]; users: UserInfo[]; limits: SpeedLimitInfo[] }
