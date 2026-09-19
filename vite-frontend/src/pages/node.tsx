import { useState, useEffect, useRef } from "react";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Switch } from "@heroui/switch";
import { Spinner } from "@heroui/spinner";
import { Alert } from "@heroui/alert";
import { Progress } from "@heroui/progress";
import toast from 'react-hot-toast';
import axios from 'axios';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';


import { 
  createNode, 
  getNodeList, 
  updateNode, 
  deleteNode,
  getNodeInstallCommand
} from "@/api";

interface Node {
  id: number;
  name: string;
  ip: string;
  serverIp: string;
  portSta: number;
  portEnd: number;
  version?: string;
  http?: number; // 0 关 1 开
  tls?: number;  // 0 关 1 开
  socks?: number; // 0 关 1 开
  status: number; // 1: 在线, 0: 离线
  connectionStatus: 'online' | 'offline';
  systemInfo?: NodeSystemInfo | null;
  copyLoading?: boolean;
}

interface NodeSystemInfo {
    cpuUsage: number;
    memoryUsage: number;
    uploadTraffic: number;
    downloadTraffic: number;
    uploadSpeed: number;
    downloadSpeed: number;
    uptime: number;
    reportedAt: number;
    cpuCores: number;
    memoryTotal: number;
    diskTotal: number;
    memoryUsed?: number;
    memoryAvailable?: number;
    memoryCached?: number;
    swapUsed?: number;
    swapTotal?: number;
    agentRss?: number;
    agentHeapAlloc?: number;
    goroutines?: number;
    diskUsed?: number;
    diskUsedPercent?: number;
    load1?: number;
    tcpConnections?: number;
    udpConnections?: number;
}

interface MetricSample {
  timestamp: number;
  cpuUsage: number;
  memoryUsage: number;
  uploadSpeed: number;
  downloadSpeed: number;
  tcpConnections?: number;
  udpConnections?: number;
  memoryAvailable?: number;
  agentRss?: number;
}

function MetricSparkline({
  data,
  dataKey,
  stroke,
  ariaLabel,
}: {
  data: MetricSample[];
  dataKey: keyof Pick<MetricSample, 'cpuUsage' | 'memoryUsage' | 'uploadSpeed' | 'downloadSpeed' | 'tcpConnections' | 'udpConnections' | 'memoryAvailable' | 'agentRss'>;
  stroke: string;
  ariaLabel: string;
}) {
  if (data.length < 2) {
    return <div className="h-8 rounded bg-default-100/70" aria-label={`${ariaLabel}暂无数据`} />;
  }

  return (
    <div className="h-8" aria-label={ariaLabel}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 2, right: 0, bottom: 2, left: 0 }}>
          <Line type="monotone" dataKey={dataKey} stroke={stroke} strokeWidth={1.8} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

interface NodeForm {
  id: number | null;
  name: string;
  ipString: string;
  serverIp: string;
  portSta: number;
  portEnd: number;
  http: number; // 0 关 1 开
  tls: number;  // 0 关 1 开
  socks: number; // 0 关 1 开
}

type TcpTuningProfile = 'tiny' | 'small' | 'balanced' | 'standard';

interface DdnsInstallPreset {
  enabled: boolean;
  cfApiToken: string;
  cfRecordName: string;
  tcpTuningProfile: TcpTuningProfile;
}

const ddnsPresetStorageKey = (nodeId: number) =>
  `flux-panel:node-install:${window.location.origin}:${nodeId}`;

const readDdnsInstallPreset = (nodeId: number): DdnsInstallPreset | null => {
  try {
    const saved = window.localStorage.getItem(ddnsPresetStorageKey(nodeId));
    if (!saved) return null;
    const preset = JSON.parse(saved) as Partial<DdnsInstallPreset>;
    const validProfile = ['tiny', 'small', 'balanced', 'standard'].includes(preset.tcpTuningProfile || '')
      ? preset.tcpTuningProfile as TcpTuningProfile
      : 'balanced';
    return {
      enabled: Boolean(preset.enabled),
      cfApiToken: typeof preset.cfApiToken === 'string' ? preset.cfApiToken : '',
      cfRecordName: typeof preset.cfRecordName === 'string' ? preset.cfRecordName : '',
      tcpTuningProfile: validProfile,
    };
  } catch {
    return null;
  }
};

const saveDdnsInstallPreset = (nodeId: number, preset: DdnsInstallPreset) => {
  window.localStorage.setItem(ddnsPresetStorageKey(nodeId), JSON.stringify(preset));
};

// 浏览器页面和 API 不一定同域：移动端 WebView、反向代理或单独托管前端时，
// window.location.origin 可能不是 Agent 应连接的公开面板地址。优先复用当前
// 已成功请求 API 的地址，避免生成可打开面板却无法让节点上线的安装命令。
const resolveAgentPanelUrl = (): string => {
  try {
    const apiBaseUrl = axios.defaults.baseURL || '/api/v1/';
    const resolved = new URL(apiBaseUrl, window.location.origin);
    if (resolved.protocol === 'http:' || resolved.protocol === 'https:') {
      return resolved.origin;
    }
  } catch {
    // Fall through to the current browser origin for a malformed custom API URL.
  }
  return window.location.origin;
};

export default function NodePage() {
  const [nodeList, setNodeList] = useState<Node[]>([]);
  const [loading, setLoading] = useState(false);
  const [dialogVisible, setDialogVisible] = useState(false);
  const [dialogTitle, setDialogTitle] = useState('');
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [nodeToDelete, setNodeToDelete] = useState<Node | null>(null);
  const [protocolDisabled, setProtocolDisabled] = useState(false);
  const [protocolDisabledReason, setProtocolDisabledReason] = useState('');
  const [metricHistory, setMetricHistory] = useState<Record<number, MetricSample[]>>({});
  const metricHistoryRef = useRef<Record<number, MetricSample[]>>({});
  const [monitorNodeId, setMonitorNodeId] = useState<number | null>(null);
  const [form, setForm] = useState<NodeForm>({
    id: null,
    name: '',
    ipString: '',
    serverIp: '',
    portSta: 1000,
    portEnd: 65535,
    http: 0,
    tls: 0,
    socks: 0
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  
  // 安装命令相关状态
  const [installSetupModal, setInstallSetupModal] = useState(false);
  const [installCommandModal, setInstallCommandModal] = useState(false);
  const [installCommand, setInstallCommand] = useState('');
  const [currentNodeName, setCurrentNodeName] = useState('');
  const [installNode, setInstallNode] = useState<Node | null>(null);
  const [installCommandLoading, setInstallCommandLoading] = useState(false);
  const [ddnsEnabled, setDdnsEnabled] = useState(false);
  const [cfApiToken, setCfApiToken] = useState('');
  const [cfRecordName, setCfRecordName] = useState('');
  const [tcpTuningProfile, setTcpTuningProfile] = useState<TcpTuningProfile>('balanced');
  
  const websocketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<NodeJS.Timeout | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const maxReconnectAttempts = 5;

  // 仅保留当前页面的最近 3 分钟实时样本。这样不需要新增数据库表，
  // 同时仍可在节点卡片和展开窗口中观察趋势。
  const appendMetricSample = (nodeId: number, sample: MetricSample) => {
    const samples = [...(metricHistoryRef.current[nodeId] || []), sample].slice(-90);
    const nextHistory = { ...metricHistoryRef.current, [nodeId]: samples };
    metricHistoryRef.current = nextHistory;
    setMetricHistory(nextHistory);
  };

  useEffect(() => {
    loadNodes();
    initWebSocket();
    
    return () => {
      closeWebSocket();
    };
  }, []);

  // 加载节点列表
  const loadNodes = async () => {
    setLoading(true);
    try {
      const res = await getNodeList();
      if (res.code === 0) {
        setNodeList(res.data.map((node: any) => ({
          ...node,
          connectionStatus: node.status === 1 ? 'online' : 'offline',
          systemInfo: null,
          copyLoading: false
        })));
      } else {
        toast.error(res.msg || '加载节点列表失败');
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setLoading(false);
    }
  };

  // 初始化WebSocket连接
  const initWebSocket = () => {
    if (websocketRef.current && 
        (websocketRef.current.readyState === WebSocket.OPEN || 
         websocketRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }
    
    if (websocketRef.current) {
      closeWebSocket();
    }
    
    // 构建WebSocket URL，使用axios的baseURL
    const baseUrl = axios.defaults.baseURL || (import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/');
    const wsUrl = baseUrl.replace(/^http/, 'ws').replace(/\/api\/v1\/$/, '') + `/system-info?type=0&secret=${localStorage.getItem('token')}`;
    
    try {
      websocketRef.current = new WebSocket(wsUrl);
      
      websocketRef.current.onopen = () => {
        reconnectAttemptsRef.current = 0;
      };
      
      websocketRef.current.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data);
          handleWebSocketMessage(data);
        } catch (error) {
          // 解析失败时不输出错误信息
        }
      };
      
      websocketRef.current.onerror = () => {
        // WebSocket错误时不输出错误信息
      };
      
      websocketRef.current.onclose = () => {
        websocketRef.current = null;
        attemptReconnect();
      };
    } catch (error) {
      attemptReconnect();
    }
  };

  // 处理WebSocket消息
  const handleWebSocketMessage = (data: any) => {
    const { id, type, data: messageData } = data;
    
    if (type === 'status') {
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            connectionStatus: messageData === 1 ? 'online' : 'offline',
            systemInfo: messageData === 0 ? null : node.systemInfo
          };
        }
        return node;
      }));
    } else if (type === 'info') {
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          try {
            let systemInfo;
            if (typeof messageData === 'string') {
              systemInfo = JSON.parse(messageData);
            } else {
              systemInfo = messageData;
            }
            
            // 统一处理数字/字符串两种上报形式，避免 NaN 传给进度条或格式化函数。
            const toFiniteNumber = (value: unknown): number => {
              const numberValue = Number(value);
              return Number.isFinite(numberValue) && numberValue >= 0 ? numberValue : 0;
            };
            // 新增指标必须区分“真实的 0”和“旧 Agent 未上报”，否则旧节点会被
            // 错误显示为 0 个连接、0 B 可用内存。
            const toOptionalNumber = (value: unknown): number | undefined => {
              if (value === undefined || value === null || value === '') return undefined;
              const numberValue = Number(value);
              return Number.isFinite(numberValue) && numberValue >= 0 ? numberValue : undefined;
            };
            const toPercentage = (value: unknown): number => Math.min(100, toFiniteNumber(value));
            const currentUpload = toFiniteNumber(systemInfo.bytes_transmitted);
            const currentDownload = toFiniteNumber(systemInfo.bytes_received);
            const currentUptime = toFiniteNumber(systemInfo.uptime);
            const cpuCores = Math.floor(toFiniteNumber(systemInfo.cpu_cores));
            const memoryTotal = toFiniteNumber(systemInfo.memory_total);
            const diskTotal = toFiniteNumber(systemInfo.disk_total);
            const reportedAt = Date.now();
            
            let uploadSpeed = 0;
            let downloadSpeed = 0;
            
            if (node.systemInfo && node.systemInfo.reportedAt) {
              // 使用实际到达时间计算速率。旧逻辑使用秒级 uptime，遇到浏览器后台
              // 节流、网络抖动或节点重启时会把速率错误显示为 0 或尖峰。
              const timeDiff = (reportedAt - node.systemInfo.reportedAt) / 1000;
              
              if (timeDiff > 0 && timeDiff <= 30) {
                const lastUpload = node.systemInfo.uploadTraffic || 0;
                const lastDownload = node.systemInfo.downloadTraffic || 0;
                
                const uploadDiff = currentUpload - lastUpload;
                const downloadDiff = currentDownload - lastDownload;
                
                const uploadReset = currentUpload < lastUpload;
                const downloadReset = currentDownload < lastDownload;
                
                if (!uploadReset && uploadDiff >= 0) {
                  uploadSpeed = uploadDiff / timeDiff;
                }
                
                if (!downloadReset && downloadDiff >= 0) {
                  downloadSpeed = downloadDiff / timeDiff;
                }
              }
            }
            
            const nextSystemInfo: NodeSystemInfo = {
              cpuUsage: toPercentage(systemInfo.cpu_usage),
              memoryUsage: toPercentage(systemInfo.memory_usage),
              uploadTraffic: currentUpload,
              downloadTraffic: currentDownload,
              uploadSpeed: uploadSpeed,
              downloadSpeed: downloadSpeed,
              uptime: currentUptime,
              reportedAt,
              cpuCores,
              memoryTotal,
              diskTotal,
              memoryUsed: toOptionalNumber(systemInfo.memory_used),
              memoryAvailable: toOptionalNumber(systemInfo.memory_available),
              memoryCached: toOptionalNumber(systemInfo.memory_cached),
              swapUsed: toOptionalNumber(systemInfo.swap_used),
              swapTotal: toOptionalNumber(systemInfo.swap_total),
              agentRss: toOptionalNumber(systemInfo.agent_rss),
              agentHeapAlloc: toOptionalNumber(systemInfo.agent_heap_alloc),
              goroutines: toOptionalNumber(systemInfo.goroutines),
              diskUsed: toOptionalNumber(systemInfo.disk_used),
              diskUsedPercent: toOptionalNumber(systemInfo.disk_used_percent),
              load1: toOptionalNumber(systemInfo.load_1),
              tcpConnections: toOptionalNumber(systemInfo.tcp_connections),
              udpConnections: toOptionalNumber(systemInfo.udp_connections),
            };

            appendMetricSample(node.id, {
              timestamp: reportedAt,
              cpuUsage: nextSystemInfo.cpuUsage,
              memoryUsage: nextSystemInfo.memoryUsage,
              uploadSpeed: nextSystemInfo.uploadSpeed,
              downloadSpeed: nextSystemInfo.downloadSpeed,
              tcpConnections: nextSystemInfo.tcpConnections,
              udpConnections: nextSystemInfo.udpConnections,
              memoryAvailable: nextSystemInfo.memoryAvailable,
              agentRss: nextSystemInfo.agentRss,
            });

            return {
              ...node,
              connectionStatus: 'online',
              systemInfo: nextSystemInfo
            };
          } catch (error) {
            return node;
          }
        }
        return node;
      }));
    }
  };

  // 尝试重新连接
  const attemptReconnect = () => {
    if (reconnectAttemptsRef.current < maxReconnectAttempts) {
      reconnectAttemptsRef.current++;
      
      reconnectTimerRef.current = setTimeout(() => {
        initWebSocket();
      }, 3000 * reconnectAttemptsRef.current);
    }
  };

  // 关闭WebSocket连接
  const closeWebSocket = () => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }
    
    reconnectAttemptsRef.current = 0;
    
    if (websocketRef.current) {
      websocketRef.current.onopen = null;
      websocketRef.current.onmessage = null;
      websocketRef.current.onerror = null;
      websocketRef.current.onclose = null;
      
      if (websocketRef.current.readyState === WebSocket.OPEN || 
          websocketRef.current.readyState === WebSocket.CONNECTING) {
        websocketRef.current.close();
      }
      
      websocketRef.current = null;
    }
    
    setNodeList(prev => prev.map(node => ({
      ...node,
      connectionStatus: 'offline',
      systemInfo: null
    })));
  };


  
  // 格式化速度
  const formatSpeed = (bytesPerSecond: number): string => {
    if (bytesPerSecond === 0) return '0 B/s';
    
    const k = 1024;
    const sizes = ['B/s', 'KB/s', 'MB/s', 'GB/s', 'TB/s'];
    const i = Math.floor(Math.log(bytesPerSecond) / Math.log(k));
    
    return parseFloat((bytesPerSecond / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  // 格式化开机时间
  const formatUptime = (seconds: number): string => {
    if (seconds === 0) return '-';
    
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    
    if (days > 0) {
      return `${days}天${hours}小时`;
    } else if (hours > 0) {
      return `${hours}小时${minutes}分钟`;
    } else {
      return `${minutes}分钟`;
    }
  };

  // 格式化流量
  const formatTraffic = (bytes: number): string => {
    if (bytes === 0) return '0 B';
    
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };

  const formatCapacity = (bytes: number): string => {
    if (!bytes) return '--';
    return formatTraffic(bytes);
  };

  const formatOptionalCapacity = (bytes?: number): string =>
    bytes === undefined ? '--' : formatTraffic(bytes);

  const formatOptionalNumber = (value?: number, digits = 0): string =>
    value === undefined ? '--' : value.toFixed(digits);

  const formatOptionalRatio = (used?: number, total?: number): string => {
    if (used === undefined || !total) return '--';
    return `${formatTraffic(used)} / ${formatTraffic(total)}`;
  };

  const formatChartTime = (timestamp: number): string =>
    new Date(timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });

  const monitorNode = monitorNodeId === null ? null : nodeList.find(node => node.id === monitorNodeId) || null;
  const monitorSamples = monitorNodeId === null ? [] : metricHistory[monitorNodeId] || [];

  // 获取进度条颜色
  const getProgressColor = (value: number, offline = false): "default" | "primary" | "secondary" | "success" | "warning" | "danger" => {
    if (offline) return "default";
    if (value <= 50) return "success";
    if (value <= 80) return "warning";
    return "danger";
  };

  // 验证IP地址格式
  const validateIp = (ip: string): boolean => {
    if (!ip || !ip.trim()) return false;
    
    const trimmedIp = ip.trim();
    
    // IPv4格式验证
    const ipv4Regex = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;
    
    // IPv6格式验证
    const ipv6Regex = /^(([0-9a-fA-F]{1,4}:){7,7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]{1,}|::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9])\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9]){0,1}[0-9]))$/;
    
    if (ipv4Regex.test(trimmedIp) || ipv6Regex.test(trimmedIp) || trimmedIp === 'localhost') {
      return true;
    }
    
    // 验证域名格式
    if (/^\d+$/.test(trimmedIp)) return false;
    
    const domainRegex = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)+$/;
    const singleLabelDomain = /^[a-zA-Z][a-zA-Z0-9\-]{0,62}$/;
    
    return domainRegex.test(trimmedIp) || singleLabelDomain.test(trimmedIp);
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};
    
    if (!form.name.trim()) {
      newErrors.name = '请输入节点名称';
    } else if (form.name.trim().length < 2) {
      newErrors.name = '节点名称长度至少2位';
    } else if (form.name.trim().length > 50) {
      newErrors.name = '节点名称长度不能超过50位';
    }
    
    if (!form.ipString.trim()) {
      newErrors.ipString = '请输入入口IP地址';
    } else {
      const ips = form.ipString.split('\n').map(ip => ip.trim()).filter(ip => ip);
      if (ips.length === 0) {
        newErrors.ipString = '请输入至少一个有效IP地址';
      } else {
        for (let i = 0; i < ips.length; i++) {
          if (!validateIp(ips[i])) {
            newErrors.ipString = `第${i + 1}行IP地址格式错误: ${ips[i]}`;
            break;
          }
        }
      }
    }
    
    if (!form.serverIp.trim()) {
      newErrors.serverIp = '请输入服务器IP地址';
    } else if (!validateIp(form.serverIp.trim())) {
      newErrors.serverIp = '请输入有效的IPv4、IPv6地址或域名';
    }
    
    if (!form.portSta || form.portSta < 1 || form.portSta > 65535) {
      newErrors.portSta = '端口范围必须在1-65535之间';
    }
    
    if (!form.portEnd || form.portEnd < 1 || form.portEnd > 65535) {
      newErrors.portEnd = '端口范围必须在1-65535之间';
    } else if (form.portEnd < form.portSta) {
      newErrors.portEnd = '结束端口不能小于起始端口';
    }
    
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增节点
  const handleAdd = () => {
    setDialogTitle('新增节点');
    setIsEdit(false);
    setDialogVisible(true);
    resetForm();
    setProtocolDisabled(true);
    setProtocolDisabledReason('节点未在线，等待节点上线后再设置');
  };

  // 编辑节点
  const handleEdit = (node: Node) => {
    setDialogTitle('编辑节点');
    setIsEdit(true);
    setForm({
      id: node.id,
      name: node.name,
      ipString: node.ip ? node.ip.split(',').map(ip => ip.trim()).join('\n') : '',
      serverIp: node.serverIp || '',
      portSta: node.portSta,
      portEnd: node.portEnd,
      http: typeof node.http === 'number' ? node.http : 1,
      tls: typeof node.tls === 'number' ? node.tls : 1,
      socks: typeof node.socks === 'number' ? node.socks : 1
    });
    const offline = node.connectionStatus !== 'online';
    setProtocolDisabled(offline);
    setProtocolDisabledReason(offline ? '节点未在线，等待节点上线后再设置' : '');
    setDialogVisible(true);
  };

  // 删除节点
  const handleDelete = (node: Node) => {
    setNodeToDelete(node);
    setDeleteModalOpen(true);
  };

  const confirmDelete = async () => {
    if (!nodeToDelete) return;
    
    setDeleteLoading(true);
    try {
      const res = await deleteNode(nodeToDelete.id);
      if (res.code === 0) {
        toast.success('删除成功');
        setNodeList(prev => prev.filter(n => n.id !== nodeToDelete.id));
        setDeleteModalOpen(false);
        setNodeToDelete(null);
      } else {
        toast.error(res.msg || '删除失败');
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setDeleteLoading(false);
    }
  };

  const openInstallSetup = (node: Node) => {
    const preset = readDdnsInstallPreset(node.id);
    setInstallNode(node);
    setCurrentNodeName(node.name);
    setDdnsEnabled(preset?.enabled ?? false);
    setCfApiToken(preset?.cfApiToken ?? '');
    setCfRecordName(preset?.cfRecordName ?? '');
    setTcpTuningProfile(preset?.tcpTuningProfile ?? 'balanced');
    setInstallSetupModal(true);
  };

  const closeInstallSetup = () => {
    if (installCommandLoading) return;
    setInstallSetupModal(false);
    setInstallNode(null);
    setCfApiToken('');
    setCfRecordName('');
  };

  // 凭据仅保存在当前管理员浏览器中，生成命令时才发送给后端拼接；不写入节点数据库。
  const handleGenerateInstallCommand = async () => {
    if (!installNode) return;
    if (ddnsEnabled && (!cfApiToken.trim() || !cfRecordName.trim())) {
      toast.error('请填写 Cloudflare API Token 和 DDNS 记录域名');
      return;
    }

    setInstallCommandLoading(true);
    try {
      const res = await getNodeInstallCommand(installNode.id, {
        panelUrl: resolveAgentPanelUrl(),
        ddnsEnabled,
        tcpTuningProfile,
        ...(ddnsEnabled ? {
          cfApiToken: cfApiToken.trim(),
          cfRecordName: cfRecordName.trim(),
        } : {}),
      });
      if (res.code === 0 && res.data) {
        saveDdnsInstallPreset(installNode.id, {
          enabled: ddnsEnabled,
          cfApiToken: cfApiToken.trim(),
          cfRecordName: cfRecordName.trim(),
          tcpTuningProfile,
        });
        setInstallCommand(res.data);
        setInstallSetupModal(false);
        setCfApiToken('');
        setCfRecordName('');
        try {
          await navigator.clipboard.writeText(res.data);
          toast.success('安装命令已生成并复制到剪贴板');
        } catch (copyError) {
          toast('安装命令已生成，请手动复制');
        }
        setInstallCommandModal(true);
      } else {
        toast.error(res.msg || '获取安装命令失败');
      }
    } catch (error) {
      toast.error('获取安装命令失败');
    } finally {
      setInstallCommandLoading(false);
    }
  };

  // 手动复制安装命令
  const handleManualCopy = async () => {
    try {
      await navigator.clipboard.writeText(installCommand);
      toast.success('安装命令已复制到剪贴板');
      setInstallCommandModal(false);
    } catch (error) {
      toast.error('复制失败，请手动选择文本复制');
    }
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;
    
    setSubmitLoading(true);
    
    try {
      const ipString = form.ipString
        .split('\n')
        .map(ip => ip.trim())
        .filter(ip => ip)
        .join(',');
        
      const submitData = {
        ...form,
        ip: ipString
      };
      delete (submitData as any).ipString;
      
      const apiCall = isEdit ? updateNode : createNode;
      const data = isEdit ? submitData : { 
        name: form.name, 
        ip: ipString,
        serverIp: form.serverIp,
        portSta: form.portSta,
        portEnd: form.portEnd,
        http: form.http,
        tls: form.tls,
        socks: form.socks
      };
      
      const res = await apiCall(data);
      if (res.code === 0) {
        toast.success(isEdit ? '更新成功' : '创建成功');
        setDialogVisible(false);
        
        if (isEdit) {
          setNodeList(prev => prev.map(n => 
            n.id === form.id ? {
              ...n,
              name: form.name,
              ip: ipString,
              serverIp: form.serverIp,
              portSta: form.portSta,
              portEnd: form.portEnd,
              http: form.http,
              tls: form.tls,
              socks: form.socks
            } : n
          ));
        } else {
          loadNodes();
        }
      } else {
        toast.error(res.msg || (isEdit ? '更新失败' : '创建失败'));
      }
    } catch (error) {
      toast.error('网络错误，请重试');
    } finally {
      setSubmitLoading(false);
    }
  };

  // 重置表单
  const resetForm = () => {
    setForm({
      id: null,
      name: '',
      ipString: '',
      serverIp: '',
      portSta: 1000,
      portEnd: 65535,
      http: 0,
      tls: 0,
      socks: 0
    });
    setErrors({});
  };

  return (
    
      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <div className="flex items-center justify-between mb-6">
        <div className="flex-1">
        </div>

        <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={handleAdd}
             
            >
              新增
            </Button>
     
        </div>

        {/* 节点列表 */}
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <div className="flex items-center gap-3">
              <Spinner size="sm" />
              <span className="text-default-600">正在加载...</span>
            </div>
          </div>
        ) : nodeList.length === 0 ? (
          <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
            <CardBody className="text-center py-16">
              <div className="flex flex-col items-center gap-4">
                <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                  <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 12h14M5 12l4-4m-4 4l4 4" />
                  </svg>
                </div>
                <div>
                  <h3 className="text-lg font-semibold text-foreground">暂无节点配置</h3>
                  <p className="text-default-500 text-sm mt-1">还没有创建任何节点配置，点击上方按钮开始创建</p>
                </div>
              </div>
            </CardBody>
          </Card>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4">
            {nodeList.map((node) => (
              <Card 
                key={node.id} 
                className="shadow-sm border border-divider hover:shadow-md transition-shadow duration-200"
              >
                <CardHeader className="pb-2">
                  <div className="flex justify-between items-start w-full">
                    <div className="flex-1 min-w-0">
                      <h3 className="font-semibold text-foreground truncate text-sm">{node.name}</h3>
                      <p className="text-xs text-default-500 truncate">{node.serverIp}</p>
                    </div>
                    <div className="flex items-center gap-1.5 ml-2">
                      <Chip 
                        color={node.connectionStatus === 'online' ? 'success' : 'danger'} 
                        variant="flat" 
                        size="sm"
                        className="text-xs"
                      >
                        {node.connectionStatus === 'online' ? '在线' : '离线'}
                      </Chip>
                    </div>
                  </div>
                </CardHeader>

                <CardBody className="pt-0 pb-3">
                  {/* 基础信息 */}
                  <div className="space-y-2 mb-4">
                    <div className="flex justify-between items-center text-sm min-w-0">
                      <span className="text-default-600 flex-shrink-0">入口IP</span>
                      <div className="text-right text-xs min-w-0 flex-1 ml-2">
                        {node.ip ? (
                          node.ip.split(',').length > 1 ? (
                            <span className="font-mono truncate block" title={node.ip.split(',')[0].trim()}>
                              {node.ip.split(',')[0].trim()} +{node.ip.split(',').length - 1}个
                            </span>
                          ) : (
                            <span className="font-mono truncate block" title={node.ip.trim()}>
                              {node.ip.trim()}
                            </span>
                          )
                        ) : '-'}
                      </div>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">端口</span>
                      <span className="text-xs">{node.portSta}-{node.portEnd}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">版本</span>
                      <span className="text-xs">{node.version || '未知'}</span>
                    </div>
                    <div className="flex justify-between text-sm">
                      <span className="text-default-600">开机时间</span>
                      <span className="text-xs">
                        {node.connectionStatus === 'online' && node.systemInfo 
                          ? formatUptime(node.systemInfo.uptime)
                          : '-'
                        }
                      </span>
                    </div>
                  </div>

                  {/* 服务器配置：由节点 Agent 只读采集，不保存或修改节点配置。 */}
                  <div className="rounded-md border border-divider p-2.5 text-xs">
                    <div className="mb-2 flex items-center justify-between">
                      <span className="font-medium text-default-700">服务器配置</span>
                      <span className="text-default-400">实时检测</span>
                    </div>
                    <div className="grid grid-cols-3 gap-2 text-center">
                      <div className="rounded bg-default-50 px-1.5 py-1.5 dark:bg-default-100">
                        <div className="text-default-500">CPU</div>
                        <div className="mt-0.5 font-mono text-foreground">
                          {node.systemInfo?.cpuCores ? `${node.systemInfo.cpuCores} 核` : '--'}
                        </div>
                      </div>
                      <div className="rounded bg-default-50 px-1.5 py-1.5 dark:bg-default-100">
                        <div className="text-default-500">内存</div>
                        <div className="mt-0.5 font-mono text-foreground">{formatCapacity(node.systemInfo?.memoryTotal || 0)}</div>
                      </div>
                      <div className="rounded bg-default-50 px-1.5 py-1.5 dark:bg-default-100">
                        <div className="text-default-500">硬盘</div>
                        <div className="mt-0.5 font-mono text-foreground">
                          {node.systemInfo?.diskUsed === undefined
                            ? formatCapacity(node.systemInfo?.diskTotal || 0)
                            : formatOptionalRatio(node.systemInfo.diskUsed, node.systemInfo.diskTotal)}
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* 系统监控 */}
                  <div className="space-y-3 mb-4">
                    <div className="grid grid-cols-2 gap-3">
                      <div>
                        <div className="flex justify-between text-xs mb-1">
                          <span>CPU</span>
                          <span className="font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo 
                              ? `${node.systemInfo.cpuUsage.toFixed(1)}%` 
                              : '-'
                            }
                          </span>
                        </div>
                        <Progress
                          value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0}
                          color={getProgressColor(
                            node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.cpuUsage : 0,
                            node.connectionStatus !== 'online'
                          )}
                          size="sm"
                          aria-label="CPU使用率"
                        />
                      </div>
                      <div>
                        <div className="flex justify-between text-xs mb-1">
                          <span>内存</span>
                          <span className="font-mono">
                            {node.connectionStatus === 'online' && node.systemInfo 
                              ? `${node.systemInfo.memoryUsage.toFixed(1)}%` 
                              : '-'
                            }
                          </span>
                        </div>
                        <Progress
                          value={node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0}
                          color={getProgressColor(
                            node.connectionStatus === 'online' && node.systemInfo ? node.systemInfo.memoryUsage : 0,
                            node.connectionStatus !== 'online'
                          )}
                          size="sm"
                          aria-label="内存使用率"
                        />
                      </div>
                    </div>

                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="text-center p-2 bg-default-50 dark:bg-default-100 rounded">
                        <div className="text-default-600 mb-0.5">上传</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatSpeed(node.systemInfo.uploadSpeed) 
                            : '-'
                          }
                        </div>
                      </div>
                      <div className="text-center p-2 bg-default-50 dark:bg-default-100 rounded">
                        <div className="text-default-600 mb-0.5">下载</div>
                        <div className="font-mono">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatSpeed(node.systemInfo.downloadSpeed) 
                            : '-'
                          }
                        </div>
                      </div>
                    </div>

                    {/* 流量统计 */}
                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="text-center p-2 bg-primary-50 dark:bg-primary-100/20 rounded border border-primary-200 dark:border-primary-300/20">
                        <div className="text-primary-600 dark:text-primary-400 mb-0.5">↑ 上行流量</div>
                        <div className="font-mono text-primary-700 dark:text-primary-300">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatTraffic(node.systemInfo.uploadTraffic) 
                            : '-'
                          }
                        </div>
                      </div>
                      <div className="text-center p-2 bg-success-50 dark:bg-success-100/20 rounded border border-success-200 dark:border-success-300/20">
                        <div className="text-success-600 dark:text-success-400 mb-0.5">↓ 下行流量</div>
                        <div className="font-mono text-success-700 dark:text-success-300">
                          {node.connectionStatus === 'online' && node.systemInfo 
                            ? formatTraffic(node.systemInfo.downloadTraffic) 
                            : '-'
                          }
                        </div>
                      </div>
                    </div>

                    <div className="grid grid-cols-2 gap-2 text-xs">
                      <div className="rounded bg-default-50 p-2 text-center dark:bg-default-100">
                        <div className="text-default-600">TCP 已建立</div>
                        <div className="mt-0.5 font-mono text-foreground">
                          {node.connectionStatus === 'online' ? formatOptionalNumber(node.systemInfo?.tcpConnections) : '-'}
                        </div>
                      </div>
                      <div className="rounded bg-default-50 p-2 text-center dark:bg-default-100">
                        <div className="text-default-600">UDP 活动套接字</div>
                        <div className="mt-0.5 font-mono text-foreground">
                          {node.connectionStatus === 'online' ? formatOptionalNumber(node.systemInfo?.udpConnections) : '-'}
                        </div>
                      </div>
                    </div>

                    {/* 小型实时曲线。完整曲线在“查看曲线”中展开。 */}
                    <div className="rounded-md border border-divider p-2.5">
                      <div className="mb-2 flex items-center justify-between text-xs">
                        <span className="font-medium text-default-700">实时趋势</span>
                        <Button
                          size="sm"
                          variant="light"
                          color="primary"
                          className="h-6 min-w-0 px-1.5 text-xs"
                          onPress={() => setMonitorNodeId(node.id)}
                        >
                          查看曲线
                        </Button>
                      </div>
                      <div className="grid grid-cols-2 gap-x-3 gap-y-2">
                        <div>
                          <div className="mb-0.5 flex justify-between text-[11px] text-default-500"><span>CPU</span><span>{node.systemInfo ? `${node.systemInfo.cpuUsage.toFixed(1)}%` : '--'}</span></div>
                          <MetricSparkline data={metricHistory[node.id] || []} dataKey="cpuUsage" stroke="#3b82f6" ariaLabel={`${node.name} CPU趋势`} />
                        </div>
                        <div>
                          <div className="mb-0.5 flex justify-between text-[11px] text-default-500"><span>内存</span><span>{node.systemInfo ? `${node.systemInfo.memoryUsage.toFixed(1)}%` : '--'}</span></div>
                          <MetricSparkline data={metricHistory[node.id] || []} dataKey="memoryUsage" stroke="#8b5cf6" ariaLabel={`${node.name} 内存趋势`} />
                        </div>
                        <div>
                          <div className="mb-0.5 text-[11px] text-default-500">上行速率</div>
                          <MetricSparkline data={metricHistory[node.id] || []} dataKey="uploadSpeed" stroke="#f59e0b" ariaLabel={`${node.name} 上行速率趋势`} />
                        </div>
                        <div>
                          <div className="mb-0.5 text-[11px] text-default-500">下行速率</div>
                          <MetricSparkline data={metricHistory[node.id] || []} dataKey="downloadSpeed" stroke="#10b981" ariaLabel={`${node.name} 下行速率趋势`} />
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* 操作按钮 */}
                  <div className="space-y-1.5">
                    <div className="flex gap-1.5">
                      <Button
                        size="sm"
                        variant="flat"
                        color="success"
                        onPress={() => openInstallSetup(node)}
                        className="flex-1 min-h-8"
                      >
                        安装
                      </Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="primary"
                        onPress={() => handleEdit(node)}
                        className="flex-1 min-h-8"
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="flat"
                        color="danger"
                        onPress={() => handleDelete(node)}
                        className="flex-1 min-h-8"
                      >
                        删除
                      </Button>
                    </div>
                  </div>
                </CardBody>
              </Card>
            ))}
          </div>
        )}

        {/* 展开后的实时曲线，不写入数据库；保存当前页面会话内最近 3 分钟。 */}
        <Modal
          isOpen={monitorNodeId !== null}
          onClose={() => setMonitorNodeId(null)}
          size="5xl"
          scrollBehavior="inside"
          backdrop="blur"
        >
          <ModalContent>
            <ModalHeader>{monitorNode ? `${monitorNode.name} · 实时监控` : '实时监控'}</ModalHeader>
            <ModalBody>
              {monitorNode?.systemInfo && (
                <>
                  <div className="grid grid-cols-3 gap-3 rounded-lg bg-default-50 p-3 text-center text-sm dark:bg-default-100">
                    <div><div className="text-default-500">CPU</div><div className="mt-1 font-mono">{monitorNode.systemInfo.cpuCores ? `${monitorNode.systemInfo.cpuCores} 核` : '--'}</div></div>
                    <div><div className="text-default-500">内存</div><div className="mt-1 font-mono">{formatCapacity(monitorNode.systemInfo.memoryTotal)}</div></div>
                    <div><div className="text-default-500">硬盘</div><div className="mt-1 font-mono">{monitorNode.systemInfo.diskUsed === undefined ? formatCapacity(monitorNode.systemInfo.diskTotal) : formatOptionalRatio(monitorNode.systemInfo.diskUsed, monitorNode.systemInfo.diskTotal)}</div></div>
                  </div>
                  <div className="grid grid-cols-2 gap-3 rounded-lg border border-divider p-3 text-center text-sm md:grid-cols-4">
                    <div><div className="text-default-500">TCP 已建立</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.tcpConnections)}</div></div>
                    <div><div className="text-default-500">UDP 活动套接字</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.udpConnections)}</div></div>
                    <div><div className="text-default-500">内存可用</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.memoryAvailable)}</div></div>
                    <div><div className="text-default-500">缓存</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.memoryCached)}</div></div>
                    <div><div className="text-default-500">Swap 已用</div><div className="mt-1 font-mono">{formatOptionalRatio(monitorNode.systemInfo.swapUsed, monitorNode.systemInfo.swapTotal)}</div></div>
                    <div><div className="text-default-500">Agent RSS</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.agentRss)}</div></div>
                    <div><div className="text-default-500">Go 堆</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.agentHeapAlloc)}</div></div>
                    <div><div className="text-default-500">goroutine / 负载</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.goroutines)} / {formatOptionalNumber(monitorNode.systemInfo.load1, 2)}</div></div>
                  </div>
                </>
              )}

              {monitorSamples.length < 2 ? (
                <div className="py-16 text-center text-default-500">正在等待节点上报至少两组实时数据…</div>
              ) : (
                <div className="space-y-6">
                  <div>
                    <div className="mb-2 text-sm font-medium text-foreground">CPU 与内存使用率</div>
                    <div className="h-64 w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <LineChart data={monitorSamples} margin={{ top: 8, right: 18, bottom: 4, left: 0 }}>
                          <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
                          <XAxis dataKey="timestamp" tickFormatter={formatChartTime} minTickGap={48} tick={{ fontSize: 11 }} />
                          <YAxis domain={[0, 100]} unit="%" tick={{ fontSize: 11 }} width={42} />
                          <Tooltip
                            labelFormatter={(value) => `时间：${formatChartTime(Number(value))}`}
                            formatter={(value, name) => [`${Number(value).toFixed(1)}%`, name]}
                          />
                          <Line type="monotone" dataKey="cpuUsage" name="CPU" stroke="#3b82f6" strokeWidth={2} dot={false} isAnimationActive={false} />
                          <Line type="monotone" dataKey="memoryUsage" name="内存" stroke="#8b5cf6" strokeWidth={2} dot={false} isAnimationActive={false} />
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  </div>

                  <div>
                    <div className="mb-2 text-sm font-medium text-foreground">网络实时速率</div>
                    <div className="h-64 w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <LineChart data={monitorSamples} margin={{ top: 8, right: 18, bottom: 4, left: 0 }}>
                          <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
                          <XAxis dataKey="timestamp" tickFormatter={formatChartTime} minTickGap={48} tick={{ fontSize: 11 }} />
                          <YAxis tickFormatter={(value) => formatSpeed(Number(value))} tick={{ fontSize: 11 }} width={68} />
                          <Tooltip
                            labelFormatter={(value) => `时间：${formatChartTime(Number(value))}`}
                            formatter={(value, name) => [formatSpeed(Number(value)), name]}
                          />
                          <Line type="monotone" dataKey="uploadSpeed" name="上行" stroke="#f59e0b" strokeWidth={2} dot={false} isAnimationActive={false} />
                          <Line type="monotone" dataKey="downloadSpeed" name="下行" stroke="#10b981" strokeWidth={2} dot={false} isAnimationActive={false} />
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  </div>

                  <div>
                    <div className="mb-2 text-sm font-medium text-foreground">Agent 连接数</div>
                    <div className="h-56 w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <LineChart data={monitorSamples} margin={{ top: 8, right: 18, bottom: 4, left: 0 }}>
                          <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
                          <XAxis dataKey="timestamp" tickFormatter={formatChartTime} minTickGap={48} tick={{ fontSize: 11 }} />
                          <YAxis allowDecimals={false} tick={{ fontSize: 11 }} width={42} />
                          <Tooltip
                            labelFormatter={(value) => `时间：${formatChartTime(Number(value))}`}
                            formatter={(value, name) => [Number(value).toFixed(0), name]}
                          />
                          <Line connectNulls type="monotone" dataKey="tcpConnections" name="TCP 已建立" stroke="#f97316" strokeWidth={2} dot={false} isAnimationActive={false} />
                          <Line connectNulls type="monotone" dataKey="udpConnections" name="UDP 活动套接字" stroke="#06b6d4" strokeWidth={2} dot={false} isAnimationActive={false} />
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  </div>

                  <div>
                    <div className="mb-2 text-sm font-medium text-foreground">内存可用量与 Agent RSS</div>
                    <div className="h-56 w-full">
                      <ResponsiveContainer width="100%" height="100%">
                        <LineChart data={monitorSamples} margin={{ top: 8, right: 18, bottom: 4, left: 0 }}>
                          <CartesianGrid strokeDasharray="3 3" className="opacity-30" />
                          <XAxis dataKey="timestamp" tickFormatter={formatChartTime} minTickGap={48} tick={{ fontSize: 11 }} />
                          <YAxis tickFormatter={(value) => formatTraffic(Number(value))} tick={{ fontSize: 11 }} width={68} />
                          <Tooltip
                            labelFormatter={(value) => `时间：${formatChartTime(Number(value))}`}
                            formatter={(value, name) => [formatTraffic(Number(value)), name]}
                          />
                          <Line connectNulls type="monotone" dataKey="memoryAvailable" name="可用内存" stroke="#22c55e" strokeWidth={2} dot={false} isAnimationActive={false} />
                          <Line connectNulls type="monotone" dataKey="agentRss" name="Agent RSS" stroke="#ec4899" strokeWidth={2} dot={false} isAnimationActive={false} />
                        </LineChart>
                      </ResponsiveContainer>
                    </div>
                  </div>
                </div>
              )}
              <p className="text-xs text-default-400">曲线保留当前页面最近约 3 分钟的实时样本；刷新页面后重新开始采集。TCP/UDP 统计为 Agent/GOST 进程套接字，不等同于账号在线人数。</p>
            </ModalBody>
            <ModalFooter>
              <Button variant="flat" onPress={() => setMonitorNodeId(null)}>关闭</Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 新增/编辑节点对话框 */}
        <Modal 
          isOpen={dialogVisible} 
          onClose={() => setDialogVisible(false)}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            <ModalHeader>{dialogTitle}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <Input
                  label="节点名称"
                  placeholder="请输入节点名称"
                  value={form.name}
                  onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                  isInvalid={!!errors.name}
                  errorMessage={errors.name}
                  variant="bordered"
                />

                <Input
                  label="服务器IP"
                  placeholder="请输入服务器IP地址，如: 192.168.1.100 或 example.com"
                  value={form.serverIp}
                  onChange={(e) => setForm(prev => ({ ...prev, serverIp: e.target.value }))}
                  isInvalid={!!errors.serverIp}
                  errorMessage={errors.serverIp}
                  variant="bordered"
                />

                <Textarea
                  label="入口IP"
                  placeholder="一行一个IP地址或域名，例如:&#10;192.168.1.100&#10;example.com"
                  value={form.ipString}
                  onChange={(e) => setForm(prev => ({ ...prev, ipString: e.target.value }))}
                  isInvalid={!!errors.ipString}
                  errorMessage={errors.ipString}
                  variant="bordered"
                  minRows={3}
                  maxRows={5}
                  description="支持多个IP，每行一个地址"
                />

                <div className="grid grid-cols-2 gap-4">
                  <Input
                    label="起始端口"
                    type="number"
                    placeholder="1000"
                    value={form.portSta.toString()}
                    onChange={(e) => setForm(prev => ({ ...prev, portSta: parseInt(e.target.value) || 1000 }))}
                    isInvalid={!!errors.portSta}
                    errorMessage={errors.portSta}
                    variant="bordered"
                    min={1}
                    max={65535}
                  />

                  <Input
                    label="结束端口"
                    type="number"
                    placeholder="65535"
                    value={form.portEnd.toString()}
                    onChange={(e) => setForm(prev => ({ ...prev, portEnd: parseInt(e.target.value) || 65535 }))}
                    isInvalid={!!errors.portEnd}
                    errorMessage={errors.portEnd}
                    variant="bordered"
                    min={1}
                    max={65535}
                  />
                </div>

                {/* 屏蔽协议 */}
                <div className="mt-1">
                  <div className="text-sm font-medium text-default-700">屏蔽协议</div>
                  <div className="text-xs text-default-500 mb-2">开启开关以屏蔽对应协议</div>
                  {protocolDisabled && (
                    <Alert
                      color="warning"
                      variant="flat"
                      description={protocolDisabledReason || '等待节点上线后再设置'}
                      className="mb-2"
                    />
                  )}
                  <div className={`grid grid-cols-1 sm:grid-cols-3 gap-3 bg-default-50 dark:bg-default-100 p-3 rounded-md border border-default-200 dark:border-default-100/30 ${protocolDisabled ? 'opacity-70' : ''}`}>
                    {/* HTTP tile */}
                    <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                      <div className="flex items-center gap-2 mb-2">
                        <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="M2 10h20"/></svg>
                        <div className="text-sm font-medium text-default-700">HTTP</div>
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="text-xs text-default-500">禁用/启用</div>
                        <Switch
                          size="sm"
                          isSelected={form.http === 1}
                          isDisabled={protocolDisabled}
                          onValueChange={(v) => setForm(prev => ({ ...prev, http: v ? 1 : 0 }))}
                        />
                      </div>
                      <div className="mt-1 text-xs text-default-400">{form.http === 1 ? '已开启' : '已关闭'}</div>
                    </div>

                    {/* TLS tile */}
                    <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                      <div className="flex items-center gap-2 mb-2">
                        <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M6 10V7a6 6 0 1 1 12 0v3"/><rect x="4" y="10" width="16" height="10" rx="2"/></svg>
                        <div className="text-sm font-medium text-default-700">TLS</div>
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="text-xs text-default-500">禁用/启用</div>
                        <Switch
                          size="sm"
                          isSelected={form.tls === 1}
                          isDisabled={protocolDisabled}
                          onValueChange={(v) => setForm(prev => ({ ...prev, tls: v ? 1 : 0 }))}
                        />
                      </div>
                      <div className="mt-1 text-xs text-default-400">{form.tls === 1 ? '已开启' : '已关闭'}</div>
                    </div>

                    {/* SOCKS tile */}
                    <div className="px-3 py-3 rounded-lg bg-white dark:bg-default-50 border border-default-200 dark:border-default-100/30 hover:border-primary-200 transition-colors">
                      <div className="flex items-center gap-2 mb-2">
                        <svg className="w-4 h-4 text-default-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                        <div className="text-sm font-medium text-default-700">SOCKS</div>
                      </div>
                      <div className="flex items-center justify-between">
                        <div className="text-xs text-default-500">禁用/启用</div>
                        <Switch
                          size="sm"
                          isSelected={form.socks === 1}
                          isDisabled={protocolDisabled}
                          onValueChange={(v) => setForm(prev => ({ ...prev, socks: v ? 1 : 0 }))}
                        />
                      </div>
                      <div className="mt-1 text-xs text-default-400">{form.socks === 1 ? '已开启' : '已关闭'}</div>
                    </div>
                  </div>
                </div>



                <Alert
                        color="danger"
                        variant="flat"
                        description="请不要在出口节点执行屏蔽协议，否则可能影响转发；屏蔽协议仅需在入口节点执行。"
                        className="mt-3"
                      />
                
                <Alert
                        color="primary"
                        variant="flat"
                        description="服务器ip是你要添加的服务器的ip地址，不是面板的ip地址。入口ip是用于展示在转发页面，面向用户的访问地址。实在理解不到说明你没这个需求，都填节点的服务器ip就行！"
                        className="mt-4"
                      />
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setDialogVisible(false)}
              >
                取消
              </Button>
              <Button
                color="primary"
                onPress={handleSubmit}
                isLoading={submitLoading}
              >
                {submitLoading ? '提交中...' : '确定'}
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 删除确认模态框 */}
        <Modal 
          isOpen={deleteModalOpen}
          onOpenChange={setDeleteModalOpen}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">确认删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p>确定要删除节点 <strong>"{nodeToDelete?.name}"</strong> 吗？</p>
                  <p className="text-small text-default-500">此操作不可恢复，请谨慎操作。</p>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button 
                    color="danger" 
                    onPress={confirmDelete}
                    isLoading={deleteLoading}
                  >
                    {deleteLoading ? '删除中...' : '确认删除'}
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 节点安装选项 */}
        <Modal
          isOpen={installSetupModal}
          onClose={closeInstallSetup}
          size="lg"
          backdrop="blur"
          placement="center"
          isDismissable={!installCommandLoading}
        >
          <ModalContent>
            <ModalHeader>安装节点 - {currentNodeName}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <p className="text-sm text-default-600">
                  生成的命令可直接在节点执行；重装或 AWS 自动换机时重跑同一条命令即可恢复配置。
                </p>
                <div className="rounded-lg border border-default-200 bg-default-50 p-3">
                  <label className="block text-sm font-medium text-default-700" htmlFor="tcp-tuning-profile">
                    TCP 调优档位
                  </label>
                  <select
                    id="tcp-tuning-profile"
                    value={tcpTuningProfile}
                    onChange={(event) => setTcpTuningProfile(event.target.value as TcpTuningProfile)}
                    disabled={installCommandLoading}
                    className="mt-2 w-full rounded-lg border border-default-300 bg-background px-3 py-2 text-sm text-foreground outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <option value="tiny">轻量：适合 512 MB 以下（缓存上限 4 MB）</option>
                    <option value="small">小型：适合 512 MB–1 GB（缓存上限 8 MB）</option>
                    <option value="balanced">均衡：适合 1–2 GB 或单核（缓存上限 12 MB）</option>
                    <option value="standard">高性能：建议 2 GB+ 且双核以上（缓存上限 16 MB）</option>
                  </select>
                  <p className="mt-1 text-xs text-default-500">
                    脚本仍会检测 CPU、内存和内核能力；BBR/FQ 不受档位影响，内核不支持时会安全回退。
                  </p>
                </div>
                <div className="rounded-lg border border-default-200 bg-default-50 p-3">
                  <Switch
                    isSelected={ddnsEnabled}
                    onValueChange={setDdnsEnabled}
                    isDisabled={installCommandLoading}
                  >
                    <span className="font-medium">启用 Cloudflare DDNS</span>
                  </Switch>
                  <p className="mt-1 text-xs text-default-500">
                    安装后及每次开机首次任务都会同步；运行中每 1 分钟检查公网 IPv4/IPv6，地址变化时才更新 A/AAAA 记录。
                  </p>
                </div>

                {ddnsEnabled && (
                  <div className="space-y-3">
                    <Input
                      label="Cloudflare API Token"
                      type="password"
                      autoComplete="off"
                      value={cfApiToken}
                      onValueChange={setCfApiToken}
                      isDisabled={installCommandLoading}
                      description="Token 需要 Zone:Read 和 DNS:Edit 权限；生成成功后会仅保存在本浏览器此节点的安装预设中，不写入面板数据库。"
                    />
                    <Input
                      label="DDNS 记录域名"
                      placeholder="node.example.com"
                      value={cfRecordName}
                      onValueChange={setCfRecordName}
                      isDisabled={installCommandLoading}
                      description="填写完整域名。已有 A/AAAA 记录会更新，不存在时自动创建；下次为该节点生成命令会自动带回。"
                    />
                    <Alert
                      color="warning"
                      variant="flat"
                      title="令牌会写入安装命令"
                      description="执行后仅保存到该节点的 root 专用配置文件（权限 600）。请不要把生成的命令发给无关人员。"
                    />
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button variant="flat" onPress={closeInstallSetup} isDisabled={installCommandLoading}>
                取消
              </Button>
              <Button color="primary" onPress={handleGenerateInstallCommand} isLoading={installCommandLoading}>
                生成并复制命令
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 安装命令模态框 */}
        <Modal 
          isOpen={installCommandModal} 
          onClose={() => setInstallCommandModal(false)}
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader>安装命令 - {currentNodeName}</ModalHeader>
            <ModalBody>
              <div className="space-y-4">
                <p className="text-sm text-default-600">
                  请复制以下安装命令到服务器上执行：
                </p>
                <div className="relative">
                  <Textarea
                    value={installCommand}
                    readOnly
                    variant="bordered"
                    minRows={6}
                    maxRows={10}
                    className="font-mono text-sm"
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />
                  <Button
                    size="sm"
                    color="primary"
                    variant="flat"
                    className="absolute top-2 right-2"
                    onPress={handleManualCopy}
                  >
                    复制
                  </Button>
                </div>
                <div className="text-xs text-default-500">
                  💡 提示：如果复制按钮失效，请手动选择上方文本进行复制
                </div>
              </div>
            </ModalBody>
            <ModalFooter>
              <Button
                variant="flat"
                onPress={() => setInstallCommandModal(false)}
              >
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>
      </div>
    
  );
}
