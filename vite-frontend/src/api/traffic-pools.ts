import Network from './network';

export interface NodeGroup { id:string; name:string; revision:number; nodeIds:number[];hostIds:number[] }
export interface PoolMember { nodeId:number; name:string; enabled:boolean; serverIp?:string; quotaBytes:number; usedBytes:number; status:string; lastReportAt:number; addresses:{ip:string;eligible:boolean;failures:number;successes:number}[] }
export interface TrafficPool { id:string;name:string;revision:number;mode:'individual'|'shared';direction:'in'|'out'|'both';quotaBytes:number;resetDay:number;domain:string;zoneId:string;hasToken:boolean;dnsEnabled:boolean;dnsStatus:string;probePort:number;failureThreshold:number;recoveryThreshold:number;ttl:number;keepLastAddress:boolean;checkedAt:number;lastSyncedAt:number;tunnelIds:number[];members:PoolMember[];usedBytes:number;totalBytes:number;remainingBytes:number;desiredAddresses:string[];accounting:'estimate' }
export type PoolInput=Pick<TrafficPool,'id'|'name'|'revision'|'mode'|'direction'|'quotaBytes'|'resetDay'|'domain'|'zoneId'|'dnsEnabled'|'probePort'|'failureThreshold'|'recoveryThreshold'|'ttl'|'keepLastAddress'|'tunnelIds'> & {token:string;dnsAcknowledgement:string;members:{nodeId:number;enabled:boolean;quotaBytes:number;usedBytes?:number}[]};
export const listTrafficPools=()=>Network.post<TrafficPool[]>('/traffic-pool/list');
export const saveTrafficPool=(data:PoolInput)=>Network.post<TrafficPool>('/traffic-pool/save',data);
export const previewPoolDns=(id:string)=>Network.post<{domain:string;changes:{action:string;ip:string}[];records:{id:string;content:string;type:string;comment:string}[];revision:number;fingerprint:string;blockedReason:string;canAdopt:boolean}>('/traffic-pool/dns-preview',{id});
export const adoptPoolDns=(id:string,revision:number,fingerprint:string,acknowledgement:string)=>Network.post('/traffic-pool/dns-adopt',{id,revision,fingerprint,acknowledgement});
export const getPoolEvents=(id:string)=>Network.post<{happened_at:number;kind:string;detail:string}[]>('/traffic-pool/events',{id});
export const listNodeGroups=()=>Network.post<NodeGroup[]>('/traffic-pool/groups');
export const saveNodeGroup=(data:{id:string;name:string;revision:number})=>Network.post<NodeGroup[]>('/traffic-pool/group/save',data);
export const deleteNodeGroup=(id:string,revision:number)=>Network.post('/traffic-pool/group/delete',{id,revision});
export const assignNodeGroup=(groupId:string,nodeIds:number[])=>Network.post('/traffic-pool/group/assign',{groupId,nodeIds});
export const assignHostGroup=(groupId:string,nodeIds:number[])=>Network.post('/traffic-pool/group/assign-hosts',{groupId,nodeIds});

export interface ManagedPoolServer {
  id: number;
  ip: string;
  port: number;
  status: string;
  message: string | null;
  usedBytes: number;
  quotaBytes: number;
}

export interface ManagedPool {
  revision: number;
  domain: string;
  hasToken: boolean;
  dnsStatus: string;
  tunnelId: number | null;
  servers: ManagedPoolServer[];
}

export const getManagedPool = () => Network.post<ManagedPool>('/traffic-pool/managed');
export const saveManagedPool = (settings: { revision: number; domain: string; token: string }) =>
  Network.post<ManagedPool>('/traffic-pool/managed/settings', settings);
export const enrollManagedServer = (server: {
  ip: string; port: number; password: string; panelOrigin: string; usedBytes: number; quotaBytes: number;
}) => Network.post('/traffic-pool/managed/enroll', server);
export interface ConfiguredPool {
  id: string;
  name: string;
  revision: number;
  domain: string;
  zoneId: string;
  hasToken: boolean;
  dnsStatus: string;
  tunnelId: number | null;
  probePort: number;
}

export type ConfiguredServer = Omit<ManagedPoolServer, 'port'> & {
  port: number | null;
  name: string;
  published: boolean | null;
};

export interface PoolSettings {
  id: string;
  name: string;
  revision: number;
  domain: string;
  zoneId: string;
  token: string;
  tunnelId: number | null;
}

export interface PoolServerInput {
  ip: string;
  port: number;
  username: string;
  password: string;
  panelOrigin: string;
  usedBytes: number;
  quotaBytes: number;
}

export const listConfiguredPools = () => Network.post<ConfiguredPool[]>('/traffic-pool/configured');
export const getConfiguredPool = (id: string) =>
  Network.post<{ pool: ConfiguredPool; servers: ConfiguredServer[] }>('/traffic-pool/detail', { id });
export const configurePool = (settings: PoolSettings) =>
  Network.post<ConfiguredPool>('/traffic-pool/configure', settings);
export const enrollPoolServers = (id: string, servers: PoolServerInput[]) =>
  Network.post<{ index: number; accepted: boolean; message: string | null }[]>('/traffic-pool/enroll-batch', { id, servers });
export const changePoolServerAddress = (data: { id: string; nodeId: number; ip: string; username: string; password: string }) =>
  Network.post('/traffic-pool/server/address', data);
