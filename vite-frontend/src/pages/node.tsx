import { ConsoleHeading, ConsoleIcon, ConsoleSearch, ConsoleEmpty } from '@/components/console-ui';
import { useState, useEffect, useRef } from "react";
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from "@heroui/dropdown";
import { Switch } from "@heroui/switch";
import { Spinner } from "@heroui/spinner";
import { Alert } from "@heroui/alert";
import { Progress } from "@heroui/progress";
import axios from "axios";
import toast from 'react-hot-toast';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';


import { 
  createRealtimeTicket,
  createNode, 
  getNodeList, 
  updateNode, 
  deleteNode,
  getNodeInstallCommand
} from "@/api";
import { getRealtimeSocketUrl } from "@/utils/realtime-socket";
import { acceptRealtimeMetric, createRealtimeConnectionGate, mergeRealtimeNodes, shouldResetRealtimeMetrics } from "@/utils/realtime-state";
import { JwtUtil } from '@/utils/jwt';

interface Node {
  id: number;
  canManage?: boolean;
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
  connectionId?: string;
  systemInfo?: NodeSystemInfo | null;
  copyLoading?: boolean;
  tcpTuningProfile?: TcpTuningProfile;
  tcpTuningAutoEnabled?: number;
  tcpTuningProfileMin?: TcpTuningProfile;
  tcpTuningProfileMax?: TcpTuningProfile;
  ddnsEnabled?: number;
  ddnsRecordName?: string;
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
    sampledAt?: number;
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
    udpQueuedBytes?: number;
    udpQueueByteLimit?: number;
    udpQueueDrops?: number;
    muxMetricsEnabled?: boolean;
    muxSessionsCurrent?: number;
    muxStreamsCurrent?: number;
    muxSessionsCreated?: number;
    muxSessionsClosed?: number;
    tcpTuningMode?: string;
    tcpTuningMinimum?: string;
    tcpTuningMaximum?: string;
    tcpTuningCurrent?: string;
    tcpTuningReason?: string;
}

interface MetricSample {
  timestamp: number;
  cpuUsage: number;
  memoryUsage: number;
  uploadSpeed: number;
  downloadSpeed: number;
  tcpConnections?: number;
  udpConnections?: number;
  tcpTuningMode?: string;
  tcpTuningMinimum?: string;
  tcpTuningMaximum?: string;
  tcpTuningCurrent?: string;
  tcpTuningReason?: string;
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
  tcpTuningProfile: TcpTuningProfile;
  tcpTuningAutoEnabled: boolean;
  tcpTuningProfileMin: TcpTuningProfile;
  tcpTuningProfileMax: TcpTuningProfile;
  ddnsEnabled: boolean;
  cfApiToken: string;
  cfRecordName: string;
}

type TcpTuningProfile = 'tiny' | 'small' | 'balanced' | 'standard';

export default function NodePage() {
  const [nodeList, setNodeList] = useState<Node[]>([]);
  const [nodeSearch, setNodeSearch] = useState('');
  const [nodeFilter, setNodeFilter] = useState('all');
  const isAdministrator = JwtUtil.getRoleIdFromToken() === 0;
  const canManageNode = (node: Node) => isAdministrator || node.canManage === true;
  const visibleNodes = nodeList.filter(node => {
    const online = node.connectionStatus === 'online';
    if (nodeFilter === 'online' && !online || nodeFilter === 'offline' && online) return false;
    return [node.name, canManageNode(node) ? node.serverIp : '', canManageNode(node) ? node.ip : '', node.version].join(' ').toLowerCase().includes(nodeSearch.trim().toLowerCase());
  });
  const clearNodeFilters = () => { setNodeSearch(''); setNodeFilter('all'); };

  const [loading, setLoading] = useState(false);
  const [nodeLoadError, setNodeLoadError] = useState('');
  const [socketStatus, setSocketStatus] = useState<'connecting' | 'connected' | 'reconnecting'>('connecting');
  const [dialogVisible, setDialogVisible] = useState(false);
  const [dialogTitle, setDialogTitle] = useState('');
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [nodeToDelete, setNodeToDelete] = useState<Node | null>(null);
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
    socks: 0,
    tcpTuningProfile: 'standard',
    tcpTuningAutoEnabled: true,
    tcpTuningProfileMin: 'tiny',
    tcpTuningProfileMax: 'standard',
    ddnsEnabled: false,
    cfApiToken: '',
    cfRecordName: ''
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  
  // 安装命令相关状态
  const [installCommandModal, setInstallCommandModal] = useState(false);
  const [installCommand, setInstallCommand] = useState('');
  const [currentNodeName, setCurrentNodeName] = useState('');
  const [installCommandLoading, setInstallCommandLoading] = useState(false);
  
  const websocketRef = useRef<WebSocket | null>(null);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const shouldMaintainSocketRef = useRef(true);
  const connectionGateRef = useRef(createRealtimeConnectionGate());
  const connectionTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const metricSequenceRef = useRef(new Map<number, number>());
  const listRequestRef = useRef(0);
  const nodeEventVersionsRef = useRef(new Map<number, number>());
  const localEventVersionRef = useRef(0);

  // 仅保留当前页面的最近 3 分钟实时样本。这样不需要新增数据库表，
  // 同时仍可在节点卡片和展开窗口中观察趋势。
  const appendMetricSample = (nodeId: number, sample: MetricSample) => {
    const samples = [...(metricHistoryRef.current[nodeId] || []), sample].slice(-90);
    const nextHistory = { ...metricHistoryRef.current, [nodeId]: samples };
    metricHistoryRef.current = nextHistory;
    setMetricHistory(nextHistory);
  };

  useEffect(() => {
    shouldMaintainSocketRef.current = true;
    const startMonitoring = async () => {
      // 先有节点列表再处理上报，避免移动网络较快时首批实时数据被空列表丢弃。
      await loadNodes();
      initWebSocket();
    };
    startMonitoring();

    const reconnectWhenForeground = () => {
      if (document.hidden || !shouldMaintainSocketRef.current) return;
      reconnectAttemptsRef.current = 0;
      if (reconnectTimerRef.current) {
        clearTimeout(reconnectTimerRef.current);
        reconnectTimerRef.current = null;
      }
      closeWebSocket(false);
      initWebSocket();
    };
    document.addEventListener('visibilitychange', reconnectWhenForeground);
    
    return () => {
      shouldMaintainSocketRef.current = false;
      listRequestRef.current++;
      document.removeEventListener('visibilitychange', reconnectWhenForeground);
      closeWebSocket(true);
    };
  }, []);

  // 加载节点列表
  const loadNodes = async () => {
    const request = ++listRequestRef.current;
    const eventVersionsAtStart = new Map(nodeEventVersionsRef.current);
    setLoading(true);
    setNodeLoadError('');
    try {
      const res = await getNodeList();
      if (!shouldMaintainSocketRef.current || request !== listRequestRef.current) return;
      if (res.code === 0) {
        setNodeList(previous => mergeRealtimeNodes(res.data, previous, eventVersionsAtStart, nodeEventVersionsRef.current) as Node[]);
      } else {
        setNodeLoadError(res.msg || '加载节点列表失败');
      }
    } catch (error) {
      if (shouldMaintainSocketRef.current && request === listRequestRef.current) setNodeLoadError('节点列表暂时加载失败，请重试');
    } finally {
      if (shouldMaintainSocketRef.current && request === listRequestRef.current) setLoading(false);
    }
  };

  // 初始化WebSocket连接
  const initWebSocket = async () => {
    if (!shouldMaintainSocketRef.current) return;
    if (websocketRef.current && 
        (websocketRef.current.readyState === WebSocket.OPEN || 
         websocketRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }
    
    if (websocketRef.current) {
      closeWebSocket(false);
    }

    const attempt = connectionGateRef.current.begin();
    if (attempt === undefined) return;
    setSocketStatus(reconnectAttemptsRef.current > 0 ? 'reconnecting' : 'connecting');
    
    try {
      const ticketResponse = await createRealtimeTicket();
      if (!shouldMaintainSocketRef.current || !connectionGateRef.current.settle(attempt)) return;
      if (ticketResponse?.code !== 0 || !ticketResponse.data?.ticket) {
        attemptReconnect();
        return;
      }
      const socket = new WebSocket(getRealtimeSocketUrl(ticketResponse.data.ticket, 'detail'));
      websocketRef.current = socket;
      metricSequenceRef.current.clear();
      const isCurrent = () => shouldMaintainSocketRef.current && connectionGateRef.current.current(attempt)
        && websocketRef.current === socket;
      connectionTimeoutRef.current = setTimeout(() => {
        if (isCurrent()) socket.close();
      }, 12_000);
      
      socket.onopen = () => {
        if (!isCurrent()) return;
        if (connectionTimeoutRef.current) clearTimeout(connectionTimeoutRef.current);
        connectionTimeoutRef.current = null;
        reconnectAttemptsRef.current = 0;
        setSocketStatus('connected');
      };
      
      socket.onmessage = (event) => {
        if (!isCurrent()) return;
        try {
          const data = JSON.parse(event.data);
          handleWebSocketMessage(data);
        } catch (error) {
          // 解析失败时不输出错误信息
        }
      };
      
      socket.onerror = () => { if (isCurrent()) socket.close(); };
      
      socket.onclose = () => {
        if (!isCurrent()) return;
        if (connectionTimeoutRef.current) clearTimeout(connectionTimeoutRef.current);
        connectionTimeoutRef.current = null;
        websocketRef.current = null;
        attemptReconnect();
      };
    } catch (error) {
      if (!connectionGateRef.current.settle(attempt) || !shouldMaintainSocketRef.current) return;
      attemptReconnect();
    }
  };

  // 处理WebSocket消息
  const handleWebSocketMessage = (data: any) => {
    const { id, type, data: messageData } = data;

    const nodeId = Number(id);
    const accepted = type === 'info' || type === 'status'
      ? acceptRealtimeMetric(data, metricSequenceRef.current.get(nodeId)) : undefined;
    if ((type === 'info' || type === 'status') && !accepted) return;
    if (accepted?.sequence !== undefined) metricSequenceRef.current.set(nodeId, accepted.sequence);
    if (accepted) nodeEventVersionsRef.current.set(nodeId, ++localEventVersionRef.current);
    
    if (type === 'status') {
      setNodeList(prev => prev.map(node => {
        if (node.id == id) {
          return {
            ...node,
            connectionStatus: messageData === 1 ? 'online' : 'offline',
            systemInfo: messageData === 0 || shouldResetRealtimeMetrics(node.connectionId, data.connectionId) ? null : node.systemInfo,
            connectionId: typeof data.connectionId === 'string' ? data.connectionId : node.connectionId,
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
            const toOptionalString = (value: unknown): string | undefined => {
              if (typeof value !== 'string') return undefined;
              const trimmed = value.trim();
              return trimmed ? trimmed : undefined;
            };
            const toPercentage = (value: unknown): number => Math.min(100, toFiniteNumber(value));
            const currentUpload = toFiniteNumber(systemInfo.bytes_transmitted);
            const currentDownload = toFiniteNumber(systemInfo.bytes_received);
            const currentUptime = toFiniteNumber(systemInfo.uptime);
            const previousInfo = shouldResetRealtimeMetrics(node.connectionId, data.connectionId)
              ? undefined : node.systemInfo || undefined;
            // Only lightweight summaries may retain earlier mux metrics. A
            // detailed/legacy report without this capability clears stale data.
            const previousMuxInfo = systemInfo.metrics_level === 'summary' ? previousInfo : undefined;
            const keepPreviousNumber = (value: unknown, fallback = 0): number => {
              const parsed = toOptionalNumber(value);
              return parsed === undefined ? fallback : parsed;
            };
            const keepPreviousOptionalNumber = (value: unknown, fallback?: number): number | undefined => {
              const parsed = toOptionalNumber(value);
              return parsed === undefined ? fallback : parsed;
            };
            const keepPreviousOptionalString = (value: unknown, fallback?: string): string | undefined => {
              const parsed = toOptionalString(value);
              return parsed === undefined ? fallback : parsed;
            };
            const cpuCores = Math.floor(keepPreviousNumber(systemInfo.cpu_cores, previousInfo?.cpuCores || 0));
            const memoryTotal = keepPreviousNumber(systemInfo.memory_total, previousInfo?.memoryTotal || 0);
            const diskTotal = keepPreviousNumber(systemInfo.disk_total, previousInfo?.diskTotal || 0);
            const reportedAt = accepted!.reportedAt;
            if (data.snapshot && previousInfo?.sampledAt !== undefined && accepted!.sampledAt !== undefined
                && accepted!.sampledAt <= previousInfo.sampledAt) return node;
            
            let uploadSpeed = 0;
            let downloadSpeed = 0;
            
            if (previousInfo && previousInfo.reportedAt) {
              // 使用实际到达时间计算速率。旧逻辑使用秒级 uptime，遇到浏览器后台
              // 节流、网络抖动或节点重启时会把速率错误显示为 0 或尖峰。
              const timeDiff = (accepted!.sampledAt !== undefined && previousInfo.sampledAt !== undefined
                ? accepted!.sampledAt - previousInfo.sampledAt : reportedAt - previousInfo.reportedAt) / 1000;
              
              if (timeDiff > 0 && timeDiff <= 30) {
                const lastUpload = previousInfo.uploadTraffic || 0;
                const lastDownload = previousInfo.downloadTraffic || 0;
                
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
              sampledAt: accepted!.sampledAt,
              cpuCores,
              memoryTotal,
              diskTotal,
              memoryUsed: keepPreviousOptionalNumber(systemInfo.memory_used, previousInfo?.memoryUsed),
              memoryAvailable: keepPreviousOptionalNumber(systemInfo.memory_available, previousInfo?.memoryAvailable),
              memoryCached: keepPreviousOptionalNumber(systemInfo.memory_cached, previousInfo?.memoryCached),
              swapUsed: keepPreviousOptionalNumber(systemInfo.swap_used, previousInfo?.swapUsed),
              swapTotal: keepPreviousOptionalNumber(systemInfo.swap_total, previousInfo?.swapTotal),
              agentRss: keepPreviousOptionalNumber(systemInfo.agent_rss, previousInfo?.agentRss),
              agentHeapAlloc: keepPreviousOptionalNumber(systemInfo.agent_heap_alloc, previousInfo?.agentHeapAlloc),
              goroutines: keepPreviousOptionalNumber(systemInfo.goroutines, previousInfo?.goroutines),
              diskUsed: keepPreviousOptionalNumber(systemInfo.disk_used, previousInfo?.diskUsed),
              diskUsedPercent: keepPreviousOptionalNumber(systemInfo.disk_used_percent, previousInfo?.diskUsedPercent),
              load1: keepPreviousOptionalNumber(systemInfo.load_1, previousInfo?.load1),
              tcpConnections: keepPreviousOptionalNumber(systemInfo.tcp_connections, previousInfo?.tcpConnections),
              udpConnections: keepPreviousOptionalNumber(systemInfo.udp_connections, previousInfo?.udpConnections),
              udpQueuedBytes: keepPreviousOptionalNumber(systemInfo.udp_queued_bytes, previousInfo?.udpQueuedBytes),
              udpQueueByteLimit: keepPreviousOptionalNumber(systemInfo.udp_queue_byte_limit, previousInfo?.udpQueueByteLimit),
              udpQueueDrops: keepPreviousOptionalNumber(systemInfo.udp_queue_drops, previousInfo?.udpQueueDrops),
              muxMetricsEnabled: typeof systemInfo.mux_metrics_enabled === 'boolean' ? systemInfo.mux_metrics_enabled : previousMuxInfo?.muxMetricsEnabled,
              muxSessionsCurrent: systemInfo.mux_metrics_enabled === false ? undefined : keepPreviousOptionalNumber(systemInfo.mux_sessions_current, previousMuxInfo?.muxSessionsCurrent),
              muxStreamsCurrent: systemInfo.mux_metrics_enabled === false ? undefined : keepPreviousOptionalNumber(systemInfo.mux_streams_current, previousMuxInfo?.muxStreamsCurrent),
              muxSessionsCreated: systemInfo.mux_metrics_enabled === false ? undefined : keepPreviousOptionalNumber(systemInfo.mux_sessions_created, previousMuxInfo?.muxSessionsCreated),
              muxSessionsClosed: systemInfo.mux_metrics_enabled === false ? undefined : keepPreviousOptionalNumber(systemInfo.mux_sessions_closed, previousMuxInfo?.muxSessionsClosed),
              tcpTuningMode: keepPreviousOptionalString(systemInfo.tcp_tuning_mode, previousInfo?.tcpTuningMode),
              tcpTuningMinimum: keepPreviousOptionalString(systemInfo.tcp_tuning_minimum, previousInfo?.tcpTuningMinimum),
              tcpTuningMaximum: keepPreviousOptionalString(systemInfo.tcp_tuning_maximum, previousInfo?.tcpTuningMaximum),
              tcpTuningCurrent: keepPreviousOptionalString(systemInfo.tcp_tuning_current, previousInfo?.tcpTuningCurrent),
              tcpTuningReason: keepPreviousOptionalString(systemInfo.tcp_tuning_reason, previousInfo?.tcpTuningReason),
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
              connectionId: typeof data.connectionId === 'string' ? data.connectionId : node.connectionId,
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
    if (!shouldMaintainSocketRef.current || reconnectTimerRef.current) return;
    setSocketStatus('reconnecting');
    reconnectAttemptsRef.current++;
    // 移动浏览器后台会暂停或销毁 WebSocket；不设次数上限，并把退避控制在
    // 15 秒以内，用户回到页面后也会立即重新建立连接。
    const delay = Math.min(3_000 * reconnectAttemptsRef.current, 15_000);
    reconnectTimerRef.current = setTimeout(() => {
      reconnectTimerRef.current = null;
      initWebSocket();
    }, delay);
  };

  // 关闭WebSocket连接
  const closeWebSocket = (clearMonitoringState = true) => {
    connectionGateRef.current.invalidate();
    if (connectionTimeoutRef.current) clearTimeout(connectionTimeoutRef.current);
    connectionTimeoutRef.current = null;
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
    
    if (clearMonitoringState) {
      setNodeList(prev => prev.map(node => ({
        ...node,
        connectionStatus: 'offline',
        systemInfo: null
      })));
    }
  };

  const retryMonitoring = async () => {
    closeWebSocket(false);
    await loadNodes();
    initWebSocket();
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

    if (form.tcpTuningAutoEnabled) {
      const profileRank: Record<TcpTuningProfile, number> = { tiny: 1, small: 2, balanced: 3, standard: 4 };
      if (profileRank[form.tcpTuningProfileMin] > profileRank[form.tcpTuningProfileMax]) {
        newErrors.tcpTuning = '动态 TCP 调优的最低档位不能高于最高档位';
      }
    }

    if (form.ddnsEnabled) {
      if (!form.cfRecordName.trim()) {
        newErrors.cfRecordName = '启用 DDNS 时请填写记录域名';
      }
      if (!isEdit && !form.cfApiToken.trim()) {
        newErrors.cfApiToken = '新节点启用 DDNS 时请填写 Cloudflare API Token';
      }
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
      socks: typeof node.socks === 'number' ? node.socks : 1,
      tcpTuningProfile: node.tcpTuningProfileMax || node.tcpTuningProfile || 'balanced',
      tcpTuningAutoEnabled: node.tcpTuningAutoEnabled === 1,
      tcpTuningProfileMin: node.tcpTuningProfileMin || 'tiny',
      tcpTuningProfileMax: node.tcpTuningProfileMax || node.tcpTuningProfile || 'standard',
      ddnsEnabled: node.ddnsEnabled === 1,
      // 出于安全考虑，后端从不回传令牌。留空保存会保留已加密的旧令牌。
      cfApiToken: '',
      cfRecordName: node.ddnsRecordName || ''
    });
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
        toast.success(typeof res.data === 'string' ? res.data : '节点及关联隧道、转发已删除');
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

  const resolveAgentPanelUrl = (): string => {
    const apiBaseUrl = axios.defaults.baseURL || import.meta.env.VITE_API_BASE || window.location.origin;
    try {
      const parsed = new URL(apiBaseUrl, window.location.origin);
      return `${parsed.protocol}//${parsed.host}`;
    } catch {
      return window.location.origin;
    }
  };

  // 安装参数在新增/编辑节点时已保存；这里仅生成与复制命令，不再要求重复填写。
  const openInstallCommand = async (node: Node) => {
    if (installCommandLoading) return;
    setCurrentNodeName(node.name);
    setInstallCommandLoading(true);
    try {
      const res = await getNodeInstallCommand(node.id, {
        panelUrl: resolveAgentPanelUrl(),
      });
      if (res.code === 0 && res.data) {
        setInstallCommand(res.data);
        try {
          await navigator.clipboard.writeText(res.data);
          toast.success('安装命令已复制，永久有效，可重复执行');
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
      toast.success('安装命令已复制，永久有效，可重复执行');
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
      socks: form.socks,
      tcpTuningProfile: form.tcpTuningProfile,
      tcpTuningAutoEnabled: form.tcpTuningAutoEnabled,
      tcpTuningProfileMin: form.tcpTuningAutoEnabled ? form.tcpTuningProfileMin : undefined,
      tcpTuningProfileMax: form.tcpTuningAutoEnabled ? form.tcpTuningProfileMax : undefined,
      ddnsEnabled: form.ddnsEnabled,
      cfApiToken: form.cfApiToken.trim(),
      cfRecordName: form.cfRecordName.trim()
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
              socks: form.socks,
              tcpTuningProfile: form.tcpTuningProfile,
              tcpTuningAutoEnabled: form.tcpTuningAutoEnabled ? 1 : 0,
              tcpTuningProfileMin: form.tcpTuningAutoEnabled ? form.tcpTuningProfileMin : undefined,
              tcpTuningProfileMax: form.tcpTuningAutoEnabled ? form.tcpTuningProfileMax : undefined,
              ddnsEnabled: form.ddnsEnabled ? 1 : 0,
              ddnsRecordName: form.ddnsEnabled ? form.cfRecordName.trim() : ''
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
      socks: 0,
      tcpTuningProfile: 'standard',
      tcpTuningAutoEnabled: true,
      tcpTuningProfileMin: 'tiny',
      tcpTuningProfileMax: 'standard',
      ddnsEnabled: false,
      cfApiToken: '',
      cfRecordName: ''
    });
    setErrors({});
  };

  return (
    
      <div className="px-3 py-3 lg:px-6 lg:py-8">
        <ConsoleHeading title="节点监控" description="资源使用、网络速率与连接状态，一览掌握。">
          <Button size="sm" variant="bordered" isDisabled={loading} onPress={retryMonitoring} startContent={<ConsoleIcon name="refresh" size={16}/>}>重新获取数据</Button>
          <Button size="sm" color="primary" onPress={handleAdd} startContent={<ConsoleIcon name="plus" size={17}/>}>新增节点</Button>
        </ConsoleHeading>
        <div className="monitor-overview" aria-label="节点概览">
          {[
            {label: '全部节点', value: nodeList.length, tone: ''},
            {label: '在线节点', value: nodeList.filter(node => node.connectionStatus === 'online').length, tone: 'success'},
            {label: '离线节点', value: nodeList.filter(node => node.connectionStatus !== 'online').length, tone: 'warning'},
            {label: '可管理', value: nodeList.filter(canManageNode).length, tone: ''},
          ].map(item => <div key={item.label}><span>{item.label}</span><strong className={`console-tone-${item.tone}`}>{item.value}</strong></div>)}
        </div>
        <div className={`monitor-connection ${socketStatus === 'connected' ? '' : 'is-pending'}`} role="status" aria-live="polite">
          <span className={`console-status-dot ${socketStatus === 'connected' ? 'status-success' : 'status-warning'}`}/>
          <span>{socketStatus === 'connecting' ? '正在连接实时监控…' : socketStatus === 'reconnecting'
            ? '实时监控连接中断，正在自动重连；已显示数据可能不是最新。'
            : nodeList.some(node => node.connectionStatus === 'online' && !node.systemInfo)
              ? '实时监控已连接，部分在线节点正在等待首次上报。' : '实时监控已连接'}</span>
          <span className="monitor-connection-hint">点击节点查看详细监控</span>
        </div>
        {nodeLoadError && <div role="alert" className="mb-4 rounded-xl border border-danger-200 bg-danger-50 px-3 py-2 text-sm text-danger-700">{nodeLoadError}，点击“重新获取数据”重试。</div>}
        <div className="console-toolbar monitor-toolbar">
          <ConsoleSearch value={nodeSearch} onChange={setNodeSearch} placeholder="搜索节点名称、IP 或版本…"/>
          <select className="console-filter-select" aria-label="按节点状态筛选" value={nodeFilter} onChange={event => setNodeFilter(event.target.value)}><option value="all">全部状态</option><option value="online">在线</option><option value="offline">离线</option></select>
          <div className="console-toolbar-tail">{visibleNodes.length} 个节点</div>
        </div>
        {loading ? <div className="flex items-center justify-center gap-3 py-20"><Spinner size="sm"/><span className="text-default-500 text-sm">正在加载…</span></div>
          : nodeList.length === 0 && !nodeLoadError ? <Card><CardBody className="items-center gap-3 py-16"><ConsoleIcon name="node" size={32}/><h2>暂无节点配置</h2><p className="text-sm text-default-500">点击“新增节点”开始创建</p></CardBody></Card>
          : visibleNodes.length === 0 ? <ConsoleEmpty onClear={clearNodeFilters}/>
          : <div className="monitor-list" role="table" aria-label="节点实时监控">
            <div className="monitor-list-header" role="row">{['节点 / 状态', 'CPU', '内存', '实时速率', '连接数', '网络趋势', '操作'].map(label => <div role="columnheader" key={label}>{label}</div>)}</div>
            <div role="rowgroup">{visibleNodes.map(node => {
              const online = node.connectionStatus === 'online';
              const info = online ? node.systemInfo : null;
              return <div className="monitor-row" role="row" key={node.id}>
                <div className="monitor-identity" role="cell">
                  <div className="monitor-name-line"><button onClick={() => setMonitorNodeId(node.id)} title={node.name}>{node.name}</button><span className={`monitor-status ${online ? 'is-online' : ''}`}><span className={`console-status-dot ${online ? 'status-success' : ''}`}/>{online ? '在线' : '离线'}</span></div>
                  {canManageNode(node) && <span className="monitor-address" title={node.serverIp}>{node.serverIp || '未设置服务器地址'}</span>}
                  <span className="monitor-version">{node.version ? `v${node.version.replace(/^v/, '')}` : '版本未知'}{online && !info ? ' · 等待上报' : ''}</span>
                </div>
                <div className="monitor-resource" role="cell" data-label="CPU"><strong>{info ? `${info.cpuUsage.toFixed(1)}%` : '—'}</strong><Progress aria-label={`${node.name} CPU 使用率`} value={info?.cpuUsage || 0} color={getProgressColor(info?.cpuUsage || 0, !info)} size="sm" className="monitor-progress"/><small>{info?.cpuCores ? `${info.cpuCores} 核` : '—'}</small></div>
                <div className="monitor-resource" role="cell" data-label="内存"><strong>{info ? `${info.memoryUsage.toFixed(1)}%` : '—'}</strong><Progress aria-label={`${node.name} 内存使用率`} value={info?.memoryUsage || 0} color={getProgressColor(info?.memoryUsage || 0, !info)} size="sm" className="monitor-progress"/><small>{info?.memoryTotal ? formatCapacity(info.memoryTotal) : '—'}</small></div>
                <div className="monitor-rates" role="cell" data-label="实时速率"><span><ConsoleIcon name="upload" size={14}/><span className="sr-only">上传</span>{info ? formatSpeed(info.uploadSpeed) : '—'}</span><span><ConsoleIcon name="download" size={14}/><span className="sr-only">下载</span>{info ? formatSpeed(info.downloadSpeed) : '—'}</span></div>
                <div className="monitor-connections" role="cell" data-label="连接数"><span><small>TCP</small>{info ? formatOptionalNumber(info.tcpConnections) : '—'}</span><span><small>UDP</small>{info ? formatOptionalNumber(info.udpConnections) : '—'}</span></div>
                <div className="monitor-trend" role="cell" data-label="网络趋势">{info && (metricHistory[node.id]?.length || 0) >= 2 ? <><MetricSparkline data={metricHistory[node.id]} dataKey="uploadSpeed" stroke="#397bf6" ariaLabel={`${node.name} 上传速率趋势`}/><MetricSparkline data={metricHistory[node.id]} dataKey="downloadSpeed" stroke="#16a36b" ariaLabel={`${node.name} 下载速率趋势`}/></> : <span>{online ? '等待采样' : '暂无实时数据'}</span>}</div>
                <div className="monitor-actions" role="cell">
                  <Button size="sm" variant="light" color="primary" onPress={() => setMonitorNodeId(node.id)} aria-label={`监控 ${node.name}`} startContent={<ConsoleIcon name="activity" size={16}/>}>监控</Button>
                  {canManageNode(node) && <Dropdown><DropdownTrigger><Button isIconOnly size="sm" variant="light" aria-label={`管理 ${node.name}`}><ConsoleIcon name="more"/></Button></DropdownTrigger><DropdownMenu aria-label={`${node.name} 节点管理`} onAction={key => { if (key === 'install') openInstallCommand(node); if (key === 'edit') handleEdit(node); if (key === 'delete') handleDelete(node); }}><DropdownItem key="install" startContent={<ConsoleIcon name="vps" size={16}/>}>安装</DropdownItem><DropdownItem key="edit" startContent={<ConsoleIcon name="edit" size={16}/>}>编辑</DropdownItem><DropdownItem key="delete" color="danger" className="text-danger" startContent={<ConsoleIcon name="close" size={16}/>}>删除</DropdownItem></DropdownMenu></Dropdown>}
                </div>
              </div>;
            })}</div>
          </div>}

        {/* 展开后的实时曲线，不写入数据库；保存当前页面会话内最近 3 分钟。 */}
        <Modal
          isOpen={monitorNodeId !== null}
          onClose={() => setMonitorNodeId(null)}
          size="5xl"
          scrollBehavior="inside"
          backdrop="blur"
        >
          <ModalContent className="monitor-dialog">
            <ModalHeader>{monitorNode ? `${monitorNode.name} · 实时监控` : '实时监控'}</ModalHeader>
            <ModalBody>
              {monitorNode && <div className="monitor-detail-facts">
                {canManageNode(monitorNode) && <><div><span>服务器地址</span><strong>{monitorNode.serverIp || '—'}</strong></div>
                <div><span>入口 IP</span><strong>{monitorNode.ip || '—'}</strong></div></>}
                <div><span>端口范围</span><strong>{monitorNode.portSta} – {monitorNode.portEnd}</strong></div>
                <div><span>Agent 版本</span><strong>{monitorNode.version || '未知'}</strong></div>
                <div><span>运行时长</span><strong>{monitorNode.systemInfo ? formatUptime(monitorNode.systemInfo.uptime) : '—'}</strong></div>
                <div><span>累计上传 / 下载</span><strong>{monitorNode.systemInfo ? `${formatTraffic(monitorNode.systemInfo.uploadTraffic)} / ${formatTraffic(monitorNode.systemInfo.downloadTraffic)}` : '—'}</strong></div>
              </div>}
              {monitorNode?.connectionStatus !== 'online' && <p className="text-sm text-warning-600">节点当前离线，已保存的样本仅供参考。</p>}

              {monitorNode?.systemInfo && (
                <>
                  <div className="grid grid-cols-3 gap-3 rounded-lg bg-default-50 p-3 text-center text-sm dark:bg-default-100">
                    <div><div className="text-default-500">CPU</div><div className="mt-1 font-mono">{monitorNode.systemInfo.cpuCores ? `${monitorNode.systemInfo.cpuCores} 核` : '--'}</div></div>
                    <div><div className="text-default-500">内存</div><div className="mt-1 font-mono">{formatCapacity(monitorNode.systemInfo.memoryTotal)}</div></div>
                    <div><div className="text-default-500">硬盘</div><div className="mt-1 font-mono">{monitorNode.systemInfo.diskUsed === undefined ? formatCapacity(monitorNode.systemInfo.diskTotal) : formatOptionalRatio(monitorNode.systemInfo.diskUsed, monitorNode.systemInfo.diskTotal)}</div></div>
                  </div>
                </>
              )}

              {monitorSamples.length < 2 ? (
                <div className="py-16 text-center text-default-500">正在等待节点上报至少两组实时数据…</div>
              ) : (
                <div className="monitor-chart-grid">
                  <div>
                    <div className="mb-2 text-sm font-medium text-foreground">CPU 与内存使用率</div>
                    <div className="monitor-chart">
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
                    <div className="monitor-chart">
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
                    <div className="monitor-chart">
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
                    <div className="monitor-chart">
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
              {monitorNode?.systemInfo && <details className="monitor-diagnostics"><summary>连接、内存与链路诊断<span>展开详细指标</span></summary><div className="space-y-3 pt-4">
                  <div className="grid grid-cols-2 gap-3 rounded-lg border border-divider p-3 text-center text-sm md:grid-cols-4">
                    <div><div className="text-default-500">TCP 已建立</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.tcpConnections)}</div></div>
                    <div><div className="text-default-500">UDP 活动套接字</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.udpConnections)}</div></div>
                    <div><div className="text-default-500">UDP 排队内存 / 预算</div><div className="mt-1 font-mono">{formatOptionalRatio(monitorNode.systemInfo.udpQueuedBytes, monitorNode.systemInfo.udpQueueByteLimit)}</div></div>
                    <div><div className="text-default-500">UDP 累计丢包</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.udpQueueDrops)}</div></div>
                    <div><div className="text-default-500">复用隧道会话</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.muxSessionsCurrent)}</div></div>
                    <div><div className="text-default-500">复用逻辑流</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.muxStreamsCurrent)}</div></div>
                    <div><div className="text-default-500">复用会话累计创建 / 关闭</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.muxSessionsCreated)} / {formatOptionalNumber(monitorNode.systemInfo.muxSessionsClosed)}</div></div>
                    <div><div className="text-default-500">内存可用</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.memoryAvailable)}</div></div>
                    <div><div className="text-default-500">缓存</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.memoryCached)}</div></div>
                    <div><div className="text-default-500">Swap 已用</div><div className="mt-1 font-mono">{formatOptionalRatio(monitorNode.systemInfo.swapUsed, monitorNode.systemInfo.swapTotal)}</div></div>
                    <div><div className="text-default-500">Agent RSS</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.agentRss)}</div></div>
                    <div><div className="text-default-500">Go 堆</div><div className="mt-1 font-mono">{formatOptionalCapacity(monitorNode.systemInfo.agentHeapAlloc)}</div></div>
                    <div><div className="text-default-500">goroutine / 负载</div><div className="mt-1 font-mono">{formatOptionalNumber(monitorNode.systemInfo.goroutines)} / {formatOptionalNumber(monitorNode.systemInfo.load1, 2)}</div></div>
                  </div>
                  <p className="text-xs text-default-500">
                    复用指标统计 GOST 复用隧道的物理会话和逻辑流，不代表在线用户数，也不包含普通 TCP 或 QUIC 会话。
                    累计创建与关闭在 Agent 重启后清零；诊断未启用或旧版 Agent 未上报时显示 --。
                  </p>
                  {monitorNode.systemInfo.tcpTuningMode && (
                    <div className="rounded-lg border border-primary-200 bg-primary-50/50 p-3 text-sm dark:border-primary-700 dark:bg-primary-900/20">
                      <div className="font-medium text-primary-700 dark:text-primary-300">
                        动态 TCP 内存保护 · {monitorNode.systemInfo.tcpTuningCurrent || '--'}
                      </div>
                      <div className="mt-1 text-default-600">
                        范围：{monitorNode.systemInfo.tcpTuningMinimum || '--'} – {monitorNode.systemInfo.tcpTuningMaximum || '--'}
                      </div>
                      {monitorNode.systemInfo.tcpTuningReason && <div className="mt-1 text-xs text-default-500">{monitorNode.systemInfo.tcpTuningReason}</div>}
                    </div>
                  )}
              </div></details>}
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

                <div className="rounded-lg border border-default-200 bg-default-50 p-3">
                  <Switch
                    isSelected={form.tcpTuningAutoEnabled}
                    onValueChange={(value) => setForm(prev => ({ ...prev, tcpTuningAutoEnabled: value }))}
                  >
                    <span className="font-medium">动态 TCP 内存保护</span>
                  </Switch>
                  <p className="mt-1 text-xs text-default-500">
                    启用后，节点每分钟只读取一次可用内存；内存持续紧张时在你指定范围内降档，稳定恢复后再逐级升档。不会重启 Agent，也不会主动断开转发。
                  </p>

                  {form.tcpTuningAutoEnabled ? (
                    <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                      <label className="text-sm text-default-700" htmlFor="node-tcp-tuning-min">
                        最低档位
                        <select
                          id="node-tcp-tuning-min"
                          value={form.tcpTuningProfileMin}
                          onChange={(event) => setForm(prev => ({ ...prev, tcpTuningProfileMin: event.target.value as TcpTuningProfile }))}
                          className="mt-1.5 w-full rounded-lg border border-default-300 bg-background px-3 py-2 text-sm text-foreground outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                        >
                          <option value="tiny">轻量（4 MB）</option>
                          <option value="small">小型（8 MB）</option>
                          <option value="balanced">均衡（12 MB）</option>
                          <option value="standard">高性能（16 MB）</option>
                        </select>
                      </label>
                      <label className="text-sm text-default-700" htmlFor="node-tcp-tuning-max">
                        最高档位
                        <select
                          id="node-tcp-tuning-max"
                          value={form.tcpTuningProfileMax}
                          onChange={(event) => setForm(prev => ({
                            ...prev,
                            tcpTuningProfileMax: event.target.value as TcpTuningProfile,
                            tcpTuningProfile: event.target.value as TcpTuningProfile
                          }))}
                          className="mt-1.5 w-full rounded-lg border border-default-300 bg-background px-3 py-2 text-sm text-foreground outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                        >
                          <option value="tiny">轻量（4 MB）</option>
                          <option value="small">小型（8 MB）</option>
                          <option value="balanced">均衡（12 MB）</option>
                          <option value="standard">高性能（16 MB）</option>
                        </select>
                      </label>
                    </div>
                  ) : (
                    <>
                      <label className="mt-3 block text-sm font-medium text-default-700" htmlFor="node-tcp-tuning-profile">
                        固定 TCP 调优档位
                      </label>
                      <select
                        id="node-tcp-tuning-profile"
                        value={form.tcpTuningProfile}
                        onChange={(event) => setForm(prev => ({ ...prev, tcpTuningProfile: event.target.value as TcpTuningProfile }))}
                        className="mt-2 w-full rounded-lg border border-default-300 bg-background px-3 py-2 text-sm text-foreground outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                      >
                        <option value="tiny">轻量：适合 512 MB 以下（缓存上限 4 MB）</option>
                        <option value="small">小型：适合 512 MB–1 GB（缓存上限 8 MB）</option>
                        <option value="balanced">均衡：适合 1–2 GB 或单核（缓存上限 12 MB）</option>
                        <option value="standard">高性能：建议 2 GB+ 且双核以上（缓存上限 16 MB）</option>
                      </select>
                    </>
                  )}
                  <p className="mt-2 text-xs text-default-500">
                    安装脚本会按本机 CPU、内存和内核能力限制最高档位。1 GB 节点若最低档设为“小型”，实际可能只剩单一档位；选“轻量”才能在压力下继续降档。BBR/FQ 不参与动态切换。
                  </p>
                  <p className="mt-2 text-xs text-warning-600">修改 TCP 档位或动态保护范围后，请在节点服务器上重新执行同一固定安装命令，使新的系统参数生效。</p>
                  {errors.tcpTuning && <p className="mt-1 text-xs text-danger">{errors.tcpTuning}</p>}
                </div>

                <div className="rounded-lg border border-default-200 bg-default-50 p-3">
                  <Switch
                    isSelected={form.ddnsEnabled}
                    onValueChange={(value) => setForm(prev => ({ ...prev, ddnsEnabled: value }))}
                  >
                    <span className="font-medium">启用 Cloudflare DDNS</span>
                  </Switch>
                  <p className="mt-1 text-xs text-default-500">
                    配置保存在面板中。1.4.15 及以上节点会在保存后同步域名和 API Token，无需重装或替换安装命令；离线节点恢复连接后同步。旧版节点需先执行固定安装命令升级一次。
                  </p>

                  {form.ddnsEnabled && (
                    <div className="mt-3 space-y-3">
                      <Input
                        label="Cloudflare API Token"
                        type="password"
                        autoComplete="off"
                        value={form.cfApiToken}
                        onValueChange={(value) => setForm(prev => ({ ...prev, cfApiToken: value }))}
                        isInvalid={!!errors.cfApiToken}
                        errorMessage={errors.cfApiToken}
                        description={isEdit ? '留空并保存会保留当前令牌；填写新值会替换。' : '需要 Zone:Read 和 DNS:Edit 权限。'}
                      />
                      <Input
                        label="DDNS 记录域名"
                        placeholder="node.example.com"
                        value={form.cfRecordName}
                        onValueChange={(value) => setForm(prev => ({ ...prev, cfRecordName: value }))}
                        isInvalid={!!errors.cfRecordName}
                        errorMessage={errors.cfRecordName}
                        description="填写完整域名；IPv6 可用时会自动维护 AAAA 记录。"
                      />
                    </div>
                  )}
                </div>

                <Alert color="primary" variant="flat"
                  description="TLS 和 UDP 限制请在用户管理 → 隧道授权中设置，可为每个用户分别配置。管理员账号不受限制。"
                  className="mt-3" />

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
                  <p className="text-small text-danger">该节点关联的全部隧道、隧道内的转发规则及用户授权将一起删除，包括使用其他入口节点的同一隧道。</p>
                  <p className="text-small text-default-500">节点离线也可以删除。其他节点上的残留配置会在连接可用时清理。此操作不可恢复。</p>
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
                <p className="text-sm text-default-600">这是该节点的固定安装命令，可永久保存并重复执行。它从 Git 下载后端安装程序，再从面板获取最新配置并保存在服务器上；修改 DDNS 域名、API Token 或安装设置无需更换命令。</p>
                <p className="text-xs text-default-500">1.4.15 及以上节点会自动同步 DDNS 修改；TCP 调优参数需重新执行同一命令才生效。命令中的密钥请妥善保管；删除节点或主动轮换节点密钥后，旧命令失效。</p>
                <div className="relative">
                  <Textarea
                    value={installCommand}
                    readOnly
                    variant="bordered"
                    minRows={3}
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
