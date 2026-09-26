import { useState, useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Button } from "@heroui/button";
import { Input } from "@heroui/input";
import { Textarea } from "@heroui/input";
import { Select, SelectItem } from "@heroui/select";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter } from "@heroui/modal";
import { Chip } from "@heroui/chip";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import { Alert } from "@heroui/alert";
import { Accordion, AccordionItem } from "@heroui/accordion";
import toast from 'react-hot-toast';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragEndEvent,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  rectSortingStrategy,
} from '@dnd-kit/sortable';
import {
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';


import { 
  createForward, 
  getForwardList, 
  updateForward, 
  deleteForward,
  forceDeleteForward,
  userTunnel, 
  pauseForwardService,
  resumeForwardService,
  diagnoseForward,
  diagnoseTunnelForwards,
  getTunnelForwardDiagnosisTask,
  cancelTunnelForwardDiagnosisTask,
  updateForwardOrder,
  getVpsHosts
} from "@/api";
import { JwtUtil } from "@/utils/jwt";

interface Forward {
  id: number;
  name: string;
  tunnelId: number;
  tunnelName: string;
  inIp: string;
  inPort: number;
  remoteAddr: string;
  interfaceName?: string;
  strategy: string;
  status: number;
  inFlow: number;
  outFlow: number;
  serviceRunning: boolean;
  createdTime: string;
  userName?: string;
  userId?: number;
  vpsHostId?: number;
  vpsHostName?: string;
  vpsHostOrigin?: 'USER' | 'ADMIN';
  vpsHostStatus?: number;
  /** User-specific ingress domain supplied by the forward query. */
  entryDomain?: string;
  inx?: number;
  syncOperation?: 'pause' | 'resume' | 'delete';
  syncState?: 'syncing' | 'partial';
  syncError?: string;
}

interface Tunnel {
  id: number;
  name: string;
  /** Effective address returned for the current user, or the raw address for admins. */
  ip?: string;
  originalIp?: string;
  entryAddressMode?: 'NONE' | 'DEFAULT' | 'CUSTOM';
  entryDomain?: string;
  inNodePortSta?: number;
  inNodePortEnd?: number;
}

interface ForwardForm {
  id?: number;
  userId?: number;
  name: string;
  tunnelId: number | null;
  inPort: number | null;
  remoteAddr: string;
  vpsHostId: number | null;
  clearVpsHost: boolean;
  vpsHostName?: string;
  vpsHostOrigin?: 'USER' | 'ADMIN';
  interfaceName?: string;
  strategy: string;
}

interface VpsHost {
  id: number;
  name: string;
  host: string;
  origin: 'USER' | 'ADMIN';
  ownerUserName?: string;
  assignedUserName?: string;
  canOperate: boolean;
}

interface AddressItem {
  id: number;
  address: string;
  copying: boolean;
}

interface DiagnosisResult {
  forwardName: string;
  timestamp: number;
  results: Array<{
    success: boolean;
    description: string;
    nodeName: string;
    nodeId: string;
    targetIp: string;
    targetPort?: number;
    message?: string;
    averageTime?: number;
    packetLoss?: number;
  }>;
}

// 添加分组接口
interface UserGroup {
  userId: number | null;
  userName: string;
  tunnelGroups: TunnelGroup[];
}

interface TunnelGroup {
  tunnelId: number;
  tunnelName: string;
  forwards: Forward[];
}

interface TunnelPingForwardReport {
  forwardId: number;
  forwardName: string;
  remoteAddress: string;
  success: boolean;
  message?: string;
  results: DiagnosisResult['results'];
}

interface TunnelPingSummary {
  taskId?: string;
  status?: 'queued' | 'running' | 'cancelling' | 'completed' | 'cancelled' | 'timed_out' | 'failed';
  completedForwards?: number;
  message?: string;
  tunnelId: number;
  tunnelName: string;
  totalForwards: number;
  successfulForwards: number;
  failedForwards: number;
  forwards: TunnelPingForwardReport[];
}

const formatVpsTargetAddress = (host: VpsHost) => {
  const address = host.host.trim();
  if (!address) return '';
  return address.includes(':') && !address.startsWith('[') ? `[${address}]:` : `${address}:`;
};

const vpsOriginLabel = (origin?: VpsHost['origin']) => origin === 'ADMIN' ? '管理员托管' : '用户托管';

export default function ForwardPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [forwards, setForwards] = useState<Forward[]>([]);
  const [tunnels, setTunnels] = useState<Tunnel[]>([]);
  const [vpsHosts, setVpsHosts] = useState<VpsHost[]>([]);
  const handledVpsLaunchRef = useRef<string | null>(null);
  
  // 检测是否为移动端
  const [isMobile, setIsMobile] = useState(false);
  
  useEffect(() => {
    const checkMobile = () => {
      setIsMobile(window.innerWidth < 768);
    };
    
    checkMobile();
    window.addEventListener('resize', checkMobile);
    
    return () => window.removeEventListener('resize', checkMobile);
  }, []);
  
  // 管理员通常需要平铺浏览；普通用户默认按隧道浏览，方便从具体隧道发起一键 PING。
  const [viewMode, setViewMode] = useState<'grouped' | 'direct'>(() => {
    try {
      const savedMode = localStorage.getItem('forward-view-mode');
      if (savedMode === 'grouped' || savedMode === 'direct') return savedMode;
      return JwtUtil.getRoleIdFromToken() === 0 ? 'direct' : 'grouped';
    } catch {
      return JwtUtil.getRoleIdFromToken() === 0 ? 'direct' : 'grouped';
    }
  });
  const [adminOnlyMyForwards, setAdminOnlyMyForwards] = useState(() => {
    try {
      return localStorage.getItem('forward-admin-only-mine') === 'true';
    } catch {
      return false;
    }
  });
  
  // 拖拽排序相关状态
  const [forwardOrder, setForwardOrder] = useState<number[]>([]);
  
  // 模态框状态
  const [modalOpen, setModalOpen] = useState(false);
  const [deleteModalOpen, setDeleteModalOpen] = useState(false);
  const [addressModalOpen, setAddressModalOpen] = useState(false);
  const [diagnosisModalOpen, setDiagnosisModalOpen] = useState(false);
  const [isEdit, setIsEdit] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [deleteLoading, setDeleteLoading] = useState(false);
  const [operatingForwardIds, setOperatingForwardIds] = useState<Set<number>>(() => new Set());
  const [diagnosisLoading, setDiagnosisLoading] = useState(false);
  const [forwardToDelete, setForwardToDelete] = useState<Forward | null>(null);
  const [currentDiagnosisForward, setCurrentDiagnosisForward] = useState<Forward | null>(null);
  const [diagnosisResult, setDiagnosisResult] = useState<DiagnosisResult | null>(null);
  const [tunnelPingModalOpen, setTunnelPingModalOpen] = useState(false);
  const [tunnelPingLoading, setTunnelPingLoading] = useState(false);
  const [tunnelPingSummary, setTunnelPingSummary] = useState<TunnelPingSummary | null>(null);
  const [tunnelPingTaskId, setTunnelPingTaskId] = useState<string | null>(null);
  const [addressModalTitle, setAddressModalTitle] = useState('');
  const [addressList, setAddressList] = useState<AddressItem[]>([]);
  
  // 导出相关状态
  const [exportModalOpen, setExportModalOpen] = useState(false);
  const [exportData, setExportData] = useState('');
  const [exportLoading, setExportLoading] = useState(false);
  const [selectedTunnelForExport, setSelectedTunnelForExport] = useState<number | null>(null);
  
  // 导入相关状态
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importData, setImportData] = useState('');
  const [importLoading, setImportLoading] = useState(false);
  const [selectedTunnelForImport, setSelectedTunnelForImport] = useState<number | null>(null);
  const [importResults, setImportResults] = useState<Array<{
    line: string;
    success: boolean;
    message: string;
    forwardName?: string;
  }>>([]);
  
  // 表单状态
  const [form, setForm] = useState<ForwardForm>({
    name: '',
    tunnelId: null,
    inPort: null,
    remoteAddr: '',
    vpsHostId: null,
    clearVpsHost: false,
    interfaceName: '',
    strategy: 'fifo'
  });
  
  // 表单验证错误
  const [errors, setErrors] = useState<{[key: string]: string}>({});
  const [selectedTunnel, setSelectedTunnel] = useState<Tunnel | null>(null);

  const isAdministrator = JwtUtil.getRoleIdFromToken() === 0;
  const selectedVpsHost = form.vpsHostId === null
    ? undefined
    : vpsHosts.find((host) => host.id === form.vpsHostId);
  const vpsOptions: VpsHost[] = [{
    id: 0,
    name: '不关联托管 VPS',
    host: '',
    origin: 'USER',
    canOperate: true,
  }, ...(form.vpsHostId !== null && !selectedVpsHost
    ? [...vpsHosts, {
      id: form.vpsHostId,
      name: form.vpsHostName ? `${form.vpsHostName}（历史关联）` : '已移除 VPS（历史关联）',
      host: '',
      origin: form.vpsHostOrigin || 'USER',
      canOperate: false,
    }]
    : vpsHosts)];

  const getDirectVisibleForwards = (source: Forward[]) => {
    const currentUserId = JwtUtil.getUserIdFromToken();
    const needsOwnerFilter = !isAdministrator || adminOnlyMyForwards;
    if (needsOwnerFilter && currentUserId !== null) {
      return source.filter(forward => forward.userId === currentUserId);
    }
    return source;
  };

  /**
   * The forward query resolves the owner-specific ingress assignment, so the
   * administrator sees the same domain assigned to that particular owner.
   */
  const getForwardEntryAddress = (forward: Forward): string => {
    if (forward.entryDomain) return forward.entryDomain;
    if (isAdministrator) return forward.inIp;
    return tunnels.find(tunnel => tunnel.id === forward.tunnelId)?.ip || forward.inIp;
  };

  useEffect(() => {
    loadData();
  }, []);

  // Durable node operations continue after the request returns. Refresh only
  // while a forward is in that transient state so the card can move from
  // “同步中/部分失败” to its confirmed final status without a manual reload.
  useEffect(() => {
    if (!forwards.some(forward => forward.status === 2 || forward.status === 3)) return;
    const timer = window.setTimeout(() => loadData(false), 3000);
    return () => window.clearTimeout(timer);
  }, [forwards]);

  // Tunnel diagnostics run as a bounded server-side task. Polling is short
  // and cancellable, so a tunnel with many forwards never has to fit inside a
  // single browser/API timeout.
  useEffect(() => {
    if (!tunnelPingTaskId) return;
    let disposed = false;
    const terminalStates = new Set(['completed', 'cancelled', 'timed_out', 'failed']);
    const refreshTask = async () => {
      try {
        const response: any = await getTunnelForwardDiagnosisTask(tunnelPingTaskId);
        if (disposed) return;
        if (response?.code === 0 && response.data) {
          const summary = response.data as TunnelPingSummary;
          setTunnelPingSummary(summary);
          if (terminalStates.has(summary.status || '')) {
            setTunnelPingLoading(false);
            setTunnelPingTaskId(null);
          }
        }
      } catch {
        // Keep the task running and retry on the next poll. A temporary
        // browser/network failure must not imply that node commands stopped.
      }
    };
    void refreshTask();
    const timer = window.setInterval(() => void refreshTask(), 1200);
    return () => {
      disposed = true;
      window.clearInterval(timer);
    };
  }, [tunnelPingTaskId]);

  // 切换显示模式并保存到localStorage
  const handleViewModeChange = () => {
    const newMode = viewMode === 'grouped' ? 'direct' : 'grouped';
    setViewMode(newMode);
    try {
      localStorage.setItem('forward-view-mode', newMode);
      
      // 切换到直接显示模式时，初始化拖拽排序顺序
      if (newMode === 'direct') {
        // Drag ordering always uses precisely the records currently visible in
        // flat view: all records for an admin, or their own records after the
        // explicit “只看我的” filter is enabled.
        const userForwards = getDirectVisibleForwards(forwards);
        
        // 检查数据库中是否有排序信息
        const hasDbOrdering = userForwards.some((f: Forward) => f.inx !== undefined && f.inx !== 0);
        
        if (hasDbOrdering) {
          // 使用数据库中的排序信息
          const dbOrder = userForwards
            .sort((a: Forward, b: Forward) => (a.inx ?? 0) - (b.inx ?? 0))
            .map((f: Forward) => f.id);
          setForwardOrder(dbOrder);
          
          // 同步到localStorage
          try {
            localStorage.setItem('forward-order', JSON.stringify(dbOrder));
          } catch (error) {
            console.warn('无法保存排序到localStorage:', error);
          }
        } else {
          // 使用本地存储的顺序
          const savedOrder = localStorage.getItem('forward-order');
          if (savedOrder) {
            try {
              const orderIds = JSON.parse(savedOrder);
              const validOrder = orderIds.filter((id: number) => 
                userForwards.some((f: Forward) => f.id === id)
              );
              userForwards.forEach((forward: Forward) => {
                if (!validOrder.includes(forward.id)) {
                  validOrder.push(forward.id);
                }
              });
              setForwardOrder(validOrder);
            } catch {
              setForwardOrder(userForwards.map((f: Forward) => f.id));
            }
          } else {
            setForwardOrder(userForwards.map((f: Forward) => f.id));
          }
        }
      }
    } catch (error) {
      console.warn('无法保存显示模式到localStorage:', error);
    }
  };

  // 加载所有数据
  const loadData = async (lod = true) => {
    setLoading(lod);
    try {
      const [forwardsRes, tunnelsRes, vpsHostsRes] = await Promise.all([
        getForwardList(),
        userTunnel(),
        getVpsHosts()
      ]);
      
      if (forwardsRes.code === 0) {
        const forwardsData = forwardsRes.data?.map((forward: any) => ({
          ...forward,
          serviceRunning: forward.status === 1
        })) || [];
        setForwards(forwardsData);
        
        // 初始化拖拽排序顺序
        if (viewMode === 'direct') {
          const userForwards = getDirectVisibleForwards(forwardsData);
          
          // 检查数据库中是否有排序信息
          const hasDbOrdering = userForwards.some((f: Forward) => f.inx !== undefined && f.inx !== 0);
          
          if (hasDbOrdering) {
            // 使用数据库中的排序信息
            const dbOrder = userForwards
              .sort((a: Forward, b: Forward) => (a.inx ?? 0) - (b.inx ?? 0))
              .map((f: Forward) => f.id);
            setForwardOrder(dbOrder);
            
            // 同步到localStorage
            try {
              localStorage.setItem('forward-order', JSON.stringify(dbOrder));
            } catch (error) {
              console.warn('无法保存排序到localStorage:', error);
            }
          } else {
            // 使用本地存储的顺序
            const savedOrder = localStorage.getItem('forward-order');
            if (savedOrder) {
              try {
                const orderIds = JSON.parse(savedOrder);
                // 验证保存的顺序是否仍然有效（只包含当前用户的转发）
                const validOrder = orderIds.filter((id: number) => 
                  userForwards.some((f: Forward) => f.id === id)
                );
                // 添加新的转发ID（如果存在）
                userForwards.forEach((forward: Forward) => {
                  if (!validOrder.includes(forward.id)) {
                    validOrder.push(forward.id);
                  }
                });
                setForwardOrder(validOrder);
              } catch {
                setForwardOrder(userForwards.map((f: Forward) => f.id));
              }
            } else {
              setForwardOrder(userForwards.map((f: Forward) => f.id));
            }
          }
        }
      } else {
        toast.error(forwardsRes.msg || '获取转发列表失败');
      }
      
      if (tunnelsRes.code === 0) {
        setTunnels(tunnelsRes.data || []);
      } else {
        console.warn('获取隧道列表失败:', tunnelsRes.msg);
      }

      if (vpsHostsRes.code === 0) {
        setVpsHosts(Array.isArray(vpsHostsRes.data) ? vpsHostsRes.data : []);
      } else {
        console.warn('获取 VPS 列表失败:', vpsHostsRes.msg);
      }
    } catch (error) {
      console.error('加载数据失败:', error);
      toast.error('加载数据失败');
    } finally {
      setLoading(false);
    }
  };

  // 按用户和隧道分组转发数据
  const groupForwardsByUserAndTunnel = (): UserGroup[] => {
    const userMap = new Map<string, UserGroup>();
    
    // 获取排序后的转发列表
    const sortedForwards = getSortedForwards();
    
    sortedForwards.forEach(forward => {
      const userKey = forward.userId ? forward.userId.toString() : 'unknown';
      const userName = forward.userName || '未知用户';
      
      if (!userMap.has(userKey)) {
        userMap.set(userKey, {
          userId: forward.userId || null,
          userName,
          tunnelGroups: []
        });
      }
      
      const userGroup = userMap.get(userKey)!;
      let tunnelGroup = userGroup.tunnelGroups.find(tg => tg.tunnelId === forward.tunnelId);
      
      if (!tunnelGroup) {
        tunnelGroup = {
          tunnelId: forward.tunnelId,
          tunnelName: forward.tunnelName,
          forwards: []
        };
        userGroup.tunnelGroups.push(tunnelGroup);
      }
      
      tunnelGroup.forwards.push(forward);
    });
    
    // 排序：先按用户名，再按隧道名
    const result = Array.from(userMap.values());
    result.sort((a, b) => a.userName.localeCompare(b.userName));
    result.forEach(userGroup => {
      userGroup.tunnelGroups.sort((a, b) => a.tunnelName.localeCompare(b.tunnelName));
    });
    
    return result;
  };

  // 表单验证
  const validateForm = (): boolean => {
    const newErrors: {[key: string]: string} = {};
    
    if (!form.name.trim()) {
      newErrors.name = '请输入转发名称';
    } else if (form.name.length < 2 || form.name.length > 50) {
      newErrors.name = '转发名称长度应在2-50个字符之间';
    }
    
    if (!form.tunnelId) {
      newErrors.tunnelId = '请选择关联隧道';
    }
    
    if (!form.remoteAddr.trim()) {
      newErrors.remoteAddr = '请输入远程地址';
    } else {
      // 验证地址格式
      const addresses = form.remoteAddr.split('\n').map(addr => addr.trim()).filter(addr => addr);
      const ipv4Pattern = /^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?):\d+$/;
      const ipv6FullPattern = /^\[((([0-9a-fA-F]{1,4}:){7}([0-9a-fA-F]{1,4}|:))|(([0-9a-fA-F]{1,4}:){6}(:[0-9a-fA-F]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-fA-F]{1,4}:){5}(((:[0-9a-fA-F]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9a-fA-F]{1,4}:){4}(((:[0-9a-fA-F]{1,4}){1,3})|((:[0-9a-fA-F]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){3}(((:[0-9a-fA-F]{1,4}){1,4})|((:[0-9a-fA-F]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){2}(((:[0-9a-fA-F]{1,4}){1,5})|((:[0-9a-fA-F]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9a-fA-F]{1,4}:){1}(((:[0-9a-fA-F]{1,4}){1,6})|((:[0-9a-fA-F]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9a-fA-F]{1,4}){1,7})|((:[0-9a-fA-F]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))\]:\d+$/;
      const domainPattern = /^[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?(\.[a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?)*:\d+$/;
      
      for (let i = 0; i < addresses.length; i++) {
        const addr = addresses[i];
        if (!ipv4Pattern.test(addr) && !ipv6FullPattern.test(addr) && !domainPattern.test(addr)) {
          newErrors.remoteAddr = `第${i + 1}行地址格式错误`;
          break;
        }
      }
    }
    
    if (form.inPort !== null && (form.inPort < 1 || form.inPort > 65535)) {
      newErrors.inPort = '端口号必须在1-65535之间';
    }
    
    if (selectedTunnel && selectedTunnel.inNodePortSta && selectedTunnel.inNodePortEnd && form.inPort) {
      if (form.inPort < selectedTunnel.inNodePortSta || form.inPort > selectedTunnel.inNodePortEnd) {
        newErrors.inPort = `端口号必须在${selectedTunnel.inNodePortSta}-${selectedTunnel.inNodePortEnd}范围内`;
      }
    }
    
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  // 新增转发
  const handleAdd = (sourceHost?: VpsHost) => {
    setIsEdit(false);
    setForm({
      name: '',
      tunnelId: null,
      inPort: null,
      remoteAddr: sourceHost ? formatVpsTargetAddress(sourceHost) : '',
      vpsHostId: sourceHost?.id ?? null,
      clearVpsHost: false,
      vpsHostName: sourceHost?.name,
      vpsHostOrigin: sourceHost?.origin,
      interfaceName: '',
      strategy: 'fifo'
    });
    setSelectedTunnel(null);
    setErrors({});
    setModalOpen(true);
  };

  // 编辑转发
  const handleEdit = (forward: Forward) => {
    // API payloads may encode IDs as either numbers or strings. Normalize the
    // selected key so HeroUI can find the matching SelectItem reliably.
    const tunnelId = Number(forward.tunnelId);
    const vpsHostId = forward.vpsHostId === undefined || forward.vpsHostId === null
      ? null
      : Number(forward.vpsHostId);
    const selected = tunnels.find((tunnel) => String(tunnel.id) === String(forward.tunnelId));
    setIsEdit(true);
    setForm({
      id: forward.id,
      userId: forward.userId,
      name: forward.name,
      tunnelId: Number.isInteger(tunnelId) ? tunnelId : null,
      inPort: forward.inPort,
      remoteAddr: forward.remoteAddr.split(',').join('\n'),
      vpsHostId: Number.isInteger(vpsHostId) ? vpsHostId : null,
      clearVpsHost: false,
      vpsHostName: forward.vpsHostName,
      vpsHostOrigin: forward.vpsHostOrigin,
      interfaceName: forward.interfaceName || '',
      strategy: forward.strategy || 'fifo'
    });
    setSelectedTunnel(selected || null);
    setErrors({});
    setModalOpen(true);
  };

  // The VPS page sends a concrete host ID instead of trusting browser state.
  // The list endpoint is already permission-filtered, so only an operable host
  // can open a pre-filled forwarding form for the current account.
  useEffect(() => {
    const requestedId = new URLSearchParams(location.search).get('vpsHostId');
    if (!requestedId || handledVpsLaunchRef.current === requestedId || loading) return;
    handledVpsLaunchRef.current = requestedId;
    const host = vpsHosts.find((item) => String(item.id) === requestedId);
    if (!host || !host.canOperate) {
      toast.error('该 VPS 不存在或你没有使用权限');
    } else {
      handleAdd(host);
    }
    const remainingParams = new URLSearchParams(location.search);
    remainingParams.delete('vpsHostId');
    const remainingSearch = remainingParams.toString();
    navigate({ pathname: '/forward', search: remainingSearch ? `?${remainingSearch}` : '' }, { replace: true });
  }, [loading, location.search, navigate, vpsHosts]);

  // 显示删除确认
  const handleDelete = (forward: Forward) => {
    setForwardToDelete(forward);
    setDeleteModalOpen(true);
  };

  // 确认删除转发
  const confirmDelete = async () => {
    if (!forwardToDelete) return;
    
    setDeleteLoading(true);
    try {
      const res = await deleteForward(forwardToDelete.id);
      if (res.code === 0) {
        toast.success(res.msg || '已进入删除同步队列');
        setDeleteModalOpen(false);
        loadData();
      } else {
        // 兼容旧入口：它现在同样走持久化节点清理，绝不会直接删库。
        const confirmed = window.confirm(`删除请求失败：${res.msg || '删除失败'}\n\n是否重新提交删除同步任务？`);
        if (confirmed) {
          const forceRes = await forceDeleteForward(forwardToDelete.id);
          if (forceRes.code === 0) {
            toast.success(forceRes.msg || '已进入删除同步队列');
            setDeleteModalOpen(false);
            loadData();
          } else {
            toast.error(forceRes.msg || '强制删除失败');
          }
        }
      }
    } catch (error) {
      console.error('删除失败:', error);
      toast.error('删除失败');
    } finally {
      setDeleteLoading(false);
    }
  };

  // 处理隧道选择变化
  const handleTunnelChange = (tunnelKey: string) => {
    const tunnelId = Number(tunnelKey);
    if (!Number.isInteger(tunnelId)) return;
    const tunnel = tunnels.find((item) => String(item.id) === tunnelKey);
    setSelectedTunnel(tunnel || null);
    setForm(prev => ({ ...prev, tunnelId }));
  };

  // 提交表单
  const handleSubmit = async () => {
    if (!validateForm()) return;
    
    setSubmitLoading(true);
    try {
      const processedRemoteAddr = form.remoteAddr
        .split('\n')
        .map(addr => addr.trim())
        .filter(addr => addr)
        .join(',');

      const addressCount = processedRemoteAddr.split(',').length;
      
      let res;
      if (isEdit) {
        // 更新时确保包含必要字段
        const updateData = {
          id: form.id,
          userId: form.userId,
          name: form.name,
          tunnelId: form.tunnelId,
          inPort: form.inPort,
          remoteAddr: processedRemoteAddr,
          vpsHostId: form.vpsHostId,
          clearVpsHost: form.clearVpsHost,
          interfaceName: form.interfaceName,
          strategy: addressCount > 1 ? form.strategy : 'fifo'
        };
        res = await updateForward(updateData);
      } else {
        // 创建时不需要id和userId（后端会自动设置）
        const createData = {
          name: form.name,
          tunnelId: form.tunnelId,
          inPort: form.inPort,
          remoteAddr: processedRemoteAddr,
          vpsHostId: form.vpsHostId,
          interfaceName: form.interfaceName,
          strategy: addressCount > 1 ? form.strategy : 'fifo'
        };
        res = await createForward(createData);
      }
      
      if (res.code === 0) {
        toast.success(isEdit ? '修改成功' : '创建成功');
        setModalOpen(false);
        loadData();
      } else {
        toast.error(res.msg || '操作失败');
      }
    } catch (error) {
      console.error('提交失败:', error);
      toast.error('操作失败');
    } finally {
      setSubmitLoading(false);
    }
  };

  // 处理服务开关
  const handleServiceToggle = async (forward: Forward) => {
    if (forward.status !== 1 && forward.status !== 0) {
      toast.error('转发正在同步或状态异常，暂不能操作');
      return;
    }
    if (operatingForwardIds.has(forward.id)) return;

    const targetState = !forward.serviceRunning;
    setOperatingForwardIds(prev => new Set(prev).add(forward.id));
    try {
      let res;
      if (targetState) {
        res = await resumeForwardService(forward.id);
      } else {
        res = await pauseForwardService(forward.id);
      }
      
      if (res.code === 0) {
        toast.success(res.msg || (targetState ? '恢复同步已开始' : '暂停同步已开始'));
        // A command timeout can still execute on a node. Always render the
        // persisted aggregate result instead of guessing locally.
        await loadData(false);
      } else {
        toast.error(res.msg || '操作失败');
      }
    } catch (error) {
      console.error('服务开关操作失败:', error);
      toast.error('网络错误，操作失败');
    } finally {
      setOperatingForwardIds(prev => {
        const next = new Set(prev);
        next.delete(forward.id);
        return next;
      });
    }
  };

  // 诊断转发
  const handleDiagnose = async (forward: Forward) => {
    setCurrentDiagnosisForward(forward);
    setDiagnosisModalOpen(true);
    setDiagnosisLoading(true);
    setDiagnosisResult(null);

    try {
      const response = await diagnoseForward(forward.id);
      if (response.code === 0) {
        setDiagnosisResult(response.data);
      } else {
        toast.error(response.msg || '诊断失败');
        setDiagnosisResult({
          forwardName: forward.name,
          timestamp: Date.now(),
          results: [{
            success: false,
            description: '诊断失败',
            nodeName: '-',
            nodeId: '-',
            targetIp: forward.remoteAddr.split(',')[0] || '-',
            message: response.msg || '诊断过程中发生错误'
          }]
        });
      }
    } catch (error) {
      console.error('诊断失败:', error);
      toast.error('网络错误，请重试');
      setDiagnosisResult({
        forwardName: forward.name,
        timestamp: Date.now(),
        results: [{
          success: false,
          description: '网络错误',
          nodeName: '-',
          nodeId: '-',
          targetIp: forward.remoteAddr.split(',')[0] || '-',
          message: '无法连接到服务器'
        }]
      });
    } finally {
      setDiagnosisLoading(false);
    }
  };

  // 一键 PING 以受限后台任务运行，避免浏览器 30 秒请求超时。
  const handleDiagnoseTunnelForwards = async (tunnelGroup: TunnelGroup) => {
    if (tunnelGroup.forwards.length === 0) {
      toast.error('该隧道暂无可检测的转发');
      return;
    }

    setTunnelPingModalOpen(true);
    setTunnelPingLoading(true);
    setTunnelPingTaskId(null);
    setTunnelPingSummary({
      tunnelId: tunnelGroup.tunnelId,
      tunnelName: tunnelGroup.tunnelName,
      totalForwards: tunnelGroup.forwards.length,
      successfulForwards: 0,
      failedForwards: 0,
      forwards: []
    });

    try {
      const response: any = await diagnoseTunnelForwards(tunnelGroup.tunnelId);
      if (response?.code === 0 && response.data) {
        const summary = response.data as TunnelPingSummary;
        setTunnelPingSummary(summary);
        if (summary.taskId) {
          setTunnelPingTaskId(summary.taskId);
        } else {
          setTunnelPingLoading(false);
        }
      } else {
        toast.error(response?.msg || '一键 PING 失败');
        setTunnelPingSummary({
          tunnelId: tunnelGroup.tunnelId,
          tunnelName: tunnelGroup.tunnelName,
          totalForwards: 0,
          successfulForwards: 0,
          failedForwards: 1,
          forwards: [{
            forwardId: 0,
            forwardName: '批量检测失败',
            remoteAddress: '',
            success: false,
            message: response?.msg || '无法开始检测',
            results: []
          }]
        });
        setTunnelPingLoading(false);
      }
    } catch {
      toast.error('网络错误，请重试');
      setTunnelPingSummary({
        tunnelId: tunnelGroup.tunnelId,
        tunnelName: tunnelGroup.tunnelName,
        totalForwards: 0,
        successfulForwards: 0,
        failedForwards: 1,
        forwards: [{
          forwardId: 0,
          forwardName: '网络错误',
          remoteAddress: '',
          success: false,
          message: '无法连接到服务器',
          results: []
          }]
        });
      setTunnelPingLoading(false);
    }
  };

  const closeTunnelPingModal = () => {
    const activeTaskId = tunnelPingTaskId;
    if (activeTaskId && tunnelPingLoading) {
      void cancelTunnelForwardDiagnosisTask(activeTaskId);
    }
    setTunnelPingTaskId(null);
    setTunnelPingLoading(false);
    setTunnelPingModalOpen(false);
  };

  // 获取连接质量
  const getQualityDisplay = (averageTime?: number, packetLoss?: number) => {
    if (averageTime === undefined || packetLoss === undefined) return null;
    
    if (averageTime < 30 && packetLoss === 0) return { text: '🚀 优秀', color: 'success' };
    if (averageTime < 50 && packetLoss === 0) return { text: '✨ 很好', color: 'success' };
    if (averageTime < 100 && packetLoss < 1) return { text: '👍 良好', color: 'primary' };
    if (averageTime < 150 && packetLoss < 2) return { text: '😐 一般', color: 'warning' };
    if (averageTime < 200 && packetLoss < 5) return { text: '😟 较差', color: 'warning' };
    return { text: '😵 很差', color: 'danger' };
  };

  // 格式化流量
  const formatFlow = (value: number): string => {
    if (value === 0) return '0 B';
    if (value < 1024) return value + ' B';
    if (value < 1024 * 1024) return (value / 1024).toFixed(2) + ' KB';
    if (value < 1024 * 1024 * 1024) return (value / (1024 * 1024)).toFixed(2) + ' MB';
    return (value / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
  };

  // 格式化入口地址
  const formatInAddress = (ipString: string, port: number): string => {
    if (!ipString || !port) return '';
    
    const ips = ipString.split(',').map(ip => ip.trim()).filter(ip => ip);
    if (ips.length === 0) return '';
    
    if (ips.length === 1) {
      const ip = ips[0];
      if (ip.includes(':') && !ip.startsWith('[')) {
        return `[${ip}]:${port}`;
      } else {
        return `${ip}:${port}`;
      }
    }
    
    const firstIp = ips[0];
    let formattedFirstIp;
    if (firstIp.includes(':') && !firstIp.startsWith('[')) {
      formattedFirstIp = `[${firstIp}]`;
    } else {
      formattedFirstIp = firstIp;
    }
    
    return `${formattedFirstIp}:${port} (+${ips.length - 1})`;
  };

  // 格式化远程地址
  const formatRemoteAddress = (addressString: string): string => {
    if (!addressString) return '';
    
    const addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
    if (addresses.length === 0) return '';
    if (addresses.length === 1) return addresses[0];
    
    return `${addresses[0]} (+${addresses.length - 1})`;
  };

  // 检查是否有多个地址
  const hasMultipleAddresses = (addressString: string): boolean => {
    if (!addressString) return false;
    const addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
    return addresses.length > 1;
  };

  // 显示地址列表弹窗
  const showAddressModal = (addressString: string, port: number | null, title: string) => {
    if (!addressString) return;
    
    let addresses: string[];
    if (port !== null) {
      // 入口地址处理
      const ips = addressString.split(',').map(ip => ip.trim()).filter(ip => ip);
      if (ips.length <= 1) {
        copyToClipboard(formatInAddress(addressString, port), title);
        return;
      }
      addresses = ips.map(ip => {
        if (ip.includes(':') && !ip.startsWith('[')) {
          return `[${ip}]:${port}`;
        } else {
          return `${ip}:${port}`;
        }
      });
    } else {
      // 远程地址处理
      addresses = addressString.split(',').map(addr => addr.trim()).filter(addr => addr);
      if (addresses.length <= 1) {
        copyToClipboard(addressString, title);
        return;
      }
    }
    
    setAddressList(addresses.map((address, index) => ({
      id: index,
      address,
      copying: false
    })));
    setAddressModalTitle(`${title} (${addresses.length}个)`);
    setAddressModalOpen(true);
  };

  // 复制到剪贴板
  const copyToClipboard = async (text: string, label: string = '内容') => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`已复制${label}`);
    } catch (error) {
      toast.error('复制失败');
    }
  };

  // 复制地址
  const copyAddress = async (addressItem: AddressItem) => {
    try {
      setAddressList(prev => prev.map(item => 
        item.id === addressItem.id ? { ...item, copying: true } : item
      ));
      await copyToClipboard(addressItem.address, '地址');
    } catch (error) {
      toast.error('复制失败');
    } finally {
      setAddressList(prev => prev.map(item => 
        item.id === addressItem.id ? { ...item, copying: false } : item
      ));
    }
  };

  // 复制所有地址
  const copyAllAddresses = async () => {
    if (addressList.length === 0) return;
    const allAddresses = addressList.map(item => item.address).join('\n');
    await copyToClipboard(allAddresses, '所有地址');
  };

    // 导出转发数据
  const handleExport = () => {
    setSelectedTunnelForExport(null);
    setExportData('');
    setExportModalOpen(true);
  };

  // 执行导出
  const executeExport = () => {
    if (!selectedTunnelForExport) {
      toast.error('请选择要导出的隧道');
      return;
    }

    setExportLoading(true);
    
    try {
      // 根据当前显示模式获取要导出的转发列表
      let forwardsToExport: Forward[] = [];
      
      if (viewMode === 'grouped') {
        // 分组模式下，获取指定隧道的转发
        const userGroups = groupForwardsByUserAndTunnel();
        forwardsToExport = userGroups.flatMap(userGroup => 
          userGroup.tunnelGroups
            .filter(tunnelGroup => tunnelGroup.tunnelId === selectedTunnelForExport)
            .flatMap(tunnelGroup => tunnelGroup.forwards)
        );
      } else {
        // 直接显示模式下，过滤指定隧道的转发
        forwardsToExport = getSortedForwards().filter(forward => forward.tunnelId === selectedTunnelForExport);
      }
      
      if (forwardsToExport.length === 0) {
        toast.error('所选隧道没有转发数据');
        setExportLoading(false);
        return;
      }
      
      // Versioned JSON keeps every setting needed to recreate a forward.
      // The importer continues to accept the original pipe-delimited format
      // below so users can still restore their older exports.
      const exportBundle = {
        format: 'flux-panel-forward-export',
        version: 2,
        exportedAt: new Date().toISOString(),
        tunnelId: selectedTunnelForExport,
        forwards: forwardsToExport.map(forward => ({
          name: forward.name,
          remoteAddr: forward.remoteAddr,
          inPort: forward.inPort ?? null,
          strategy: forward.strategy || 'fifo'
        }))
      };
      setExportData(JSON.stringify(exportBundle, null, 2));
    } catch (error) {
      console.error('导出失败:', error);
      toast.error('导出失败');
    } finally {
      setExportLoading(false);
    }
  };

  // 复制导出数据
  const copyExportData = async () => {
    await copyToClipboard(exportData, '转发数据');
  };

  // 导入转发数据
  const handleImport = () => {
    setImportData('');
    setImportResults([]);
    setSelectedTunnelForImport(null);
    setImportModalOpen(true);
  };

  // 执行导入
  const executeImport = async () => {
    if (!importData.trim()) {
      toast.error('请输入要导入的数据');
      return;
    }

    if (!selectedTunnelForImport) {
      toast.error('请选择要导入的隧道');
      return;
    }

    setImportLoading(true);
    setImportResults([]); // 清空之前的结果

    try {
      type ImportItem = {
        line: string;
        name?: unknown;
        remoteAddr?: unknown;
        inPort?: unknown;
        strategy?: unknown;
      };
      let items: ImportItem[];
      const raw = importData.trim();
      if (raw.startsWith('{')) {
        const bundle = JSON.parse(raw);
        if (bundle?.format !== 'flux-panel-forward-export' || bundle?.version !== 2 || !Array.isArray(bundle.forwards)) {
          throw new Error('JSON 导入文件不是受支持的 v2 转发导出格式');
        }
        items = bundle.forwards.map((item: unknown) => {
          const value = item && typeof item === 'object' ? item as Record<string, unknown> : {};
          return {
            line: JSON.stringify(value),
            name: value.name,
            remoteAddr: value.remoteAddr,
            inPort: value.inPort,
            strategy: value.strategy
          };
        });
      } else {
        // Legacy: 目标地址|转发名称|入口端口|展示入口地址。第 4 列仍只用于展示。
        items = raw.split('\n').filter(line => line.trim()).map(line => {
          const [remoteAddr, name, inPort] = line.trim().split('|');
          return { line: line.trim(), remoteAddr, name, inPort, strategy: 'fifo' };
        });
      }

      if (items.length === 0) {
        throw new Error('没有可导入的转发记录');
      }

      const results: Array<{ line: string; success: boolean; message: string; forwardName?: string }> = [];
      const normalizeRemoteAddress = (value: string) => value.replace(/\s+/g, '').toLowerCase();
      const duplicateKeys = new Set(
        forwards
          .filter(forward => forward.tunnelId === selectedTunnelForImport)
          .map(forward => `${forward.name.trim().toLowerCase()}\u0000${normalizeRemoteAddress(forward.remoteAddr)}`)
      );
      const isValidTargetAddress = (value: string) => {
        const address = value.trim();
        let host = '';
        let rawPort = '';
        if (address.startsWith('[')) {
          const close = address.indexOf(']');
          if (close <= 1 || address.charAt(close + 1) !== ':') return false;
          host = address.slice(1, close);
          rawPort = address.slice(close + 2);
        } else {
          const separator = address.lastIndexOf(':');
          if (separator <= 0) return false;
          host = address.slice(0, separator);
          rawPort = address.slice(separator + 1);
        }
        const port = Number(rawPort);
        return Boolean(host.trim()) && /^\d+$/.test(rawPort) && Number.isInteger(port) && port >= 1 && port <= 65535;
      };

      for (const item of items) {
        const line = item.line;
        const name = typeof item.name === 'string' ? item.name.trim() : '';
        const remoteAddr = typeof item.remoteAddr === 'string' ? item.remoteAddr.trim() : '';
        if (!name || !remoteAddr) {
          results.push({ line, success: false, message: '目标地址和转发名称不能为空' });
          continue;
        }
        if (!remoteAddr.split(',').every(address => isValidTargetAddress(address))) {
          results.push({ line, success: false, message: '目标地址格式错误；支持域名/IPv4:端口及 [IPv6]:端口，多个地址用逗号分隔' });
          continue;
        }

        let portNumber: number | null = null;
        if (item.inPort !== null && item.inPort !== undefined && String(item.inPort).trim() !== '') {
          const port = Number(item.inPort);
          if (!Number.isInteger(port) || port < 1 || port > 65535) {
            results.push({ line, success: false, message: '入口端口格式错误，应为 1–65535 的整数' });
            continue;
          }
          portNumber = port;
        }

        const duplicateKey = `${name.toLowerCase()}\u0000${normalizeRemoteAddress(remoteAddr)}`;
        if (duplicateKeys.has(duplicateKey)) {
          results.push({ line, success: false, message: '已存在相同名称和目标地址的转发，已跳过' });
          continue;
        }
        const strategy = item.strategy === 'round' || item.strategy === 'rand' || item.strategy === 'fifo'
          ? item.strategy : 'fifo';
        try {
          const response = await createForward({
            name,
            tunnelId: selectedTunnelForImport,
            inPort: portNumber,
            remoteAddr,
            strategy
          });
          if (response.code === 0) {
            duplicateKeys.add(duplicateKey);
            results.push({ line, success: true, message: '创建成功', forwardName: name });
          } else {
            results.push({ line, success: false, message: response.msg || '创建失败' });
          }
        } catch {
          results.push({ line, success: false, message: '网络错误，创建失败' });
        }
      }

      setImportResults(results);
      const successful = results.filter(result => result.success).length;
      const failed = results.length - successful;
      if (successful > 0) {
        toast.success(`导入完成：成功 ${successful} 条${failed ? `，失败或跳过 ${failed} 条` : ''}`);
        await loadData(false);
      } else {
        toast.error(`导入未创建任何转发（${failed} 条失败或跳过）`);
      }
    } catch (error) {
      console.error('导入失败:', error);
      const message = error instanceof Error ? error.message : '导入过程中发生错误';
      toast.error(message);
      setImportResults([{ line: '导入文件', success: false, message }]);
    } finally {
      setImportLoading(false);
    }
  };

  // 获取状态显示
  const getStatusDisplay = (status: number, syncState?: Forward['syncState']) => {
    switch (status) {
      case 1:
        return { color: 'success', text: '正常' };
      case 0:
        return { color: 'warning', text: '暂停' };
      case -1:
        return { color: 'danger', text: '异常' };
      case 2:
        return { color: syncState === 'partial' ? 'warning' : 'primary', text: syncState === 'partial' ? '部分失败，重试中' : '同步中' };
      case 3:
        return { color: syncState === 'partial' ? 'warning' : 'danger', text: syncState === 'partial' ? '删除部分失败，重试中' : '删除同步中' };
      default:
        return { color: 'default', text: '未知' };
    }
  };

  // 获取策略显示
  const getStrategyDisplay = (strategy: string) => {
    switch (strategy) {
      case 'fifo':
        return { color: 'primary', text: '主备' };
      case 'round':
        return { color: 'success', text: '轮询' };
      case 'rand':
        return { color: 'warning', text: '随机' };
      default:
        return { color: 'default', text: '未知' };
    }
  };

  // 获取地址数量
  const getAddressCount = (addressString: string): number => {
    if (!addressString) return 0;
    const addresses = addressString.split('\n').map(addr => addr.trim()).filter(addr => addr);
    return addresses.length;
  };

  // 处理拖拽结束
  const handleDragEnd = async (event: DragEndEvent) => {
    const { active, over } = event;
    
    if (!active || !over || active.id === over.id) return;
    
    // 确保 forwardOrder 存在且有效
    if (!forwardOrder || forwardOrder.length === 0) return;
    
    const activeId = Number(active.id);
    const overId = Number(over.id);
    
    // 检查 ID 是否有效
    if (isNaN(activeId) || isNaN(overId)) return;
    
    const oldIndex = forwardOrder.indexOf(activeId);
    const newIndex = forwardOrder.indexOf(overId);
    
    if (oldIndex !== -1 && newIndex !== -1 && oldIndex !== newIndex) {
      const newOrder = arrayMove(forwardOrder, oldIndex, newIndex);
      setForwardOrder(newOrder);
      
      // 保存到localStorage
      try {
        localStorage.setItem('forward-order', JSON.stringify(newOrder));
      } catch (error) {
        console.warn('无法保存排序到localStorage:', error);
      }
      
      // 持久化到数据库
      try {
        const forwardsToUpdate = newOrder.map((id, index) => ({
          id,
          inx: index
        }));
        
        const response = await updateForwardOrder({ forwards: forwardsToUpdate });
        if (response.code === 0) {
          // 更新本地数据中的 inx 字段
          setForwards(prev => prev.map(forward => {
            const updatedForward = forwardsToUpdate.find(f => f.id === forward.id);
            if (updatedForward) {
              return { ...forward, inx: updatedForward.inx };
            }
            return forward;
          }));
        } else {
          toast.error('保存排序失败：' + (response.msg || '未知错误'));
        }
      } catch (error) {
        console.error('保存排序到数据库失败:', error);
        toast.error('保存排序失败，请重试');
      }
    }
  };

  // 传感器配置 - 使用默认配置避免错误
  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    })
  );

  // 根据排序顺序获取转发列表
  const getSortedForwards = (): Forward[] => {
    // 确保 forwards 数组存在且有效
    if (!forwards || forwards.length === 0) {
      return [];
    }
    
    const filteredForwards = viewMode === 'direct'
      ? getDirectVisibleForwards(forwards)
      : forwards;
    
    // 确保过滤后的转发列表有效
    if (!filteredForwards || filteredForwards.length === 0) {
      return [];
    }
    
    // 优先使用数据库中的 inx 字段进行排序
    const sortedForwards = [...filteredForwards].sort((a, b) => {
      const aInx = a.inx ?? 0;
      const bInx = b.inx ?? 0;
      return aInx - bInx;
    });
    
    // 如果数据库中没有排序信息，则使用本地存储的顺序
    if (forwardOrder && forwardOrder.length > 0 && sortedForwards.every(f => f.inx === undefined || f.inx === 0)) {
      const forwardMap = new Map(filteredForwards.map(f => [f.id, f]));
      const localSortedForwards: Forward[] = [];
      
      forwardOrder.forEach(id => {
        const forward = forwardMap.get(id);
        if (forward) {
          localSortedForwards.push(forward);
        }
      });
      
      // 添加不在排序列表中的转发（新添加的）
      filteredForwards.forEach(forward => {
        if (!forwardOrder.includes(forward.id)) {
          localSortedForwards.push(forward);
        }
      });
      
      return localSortedForwards;
    }
    
    return sortedForwards;
  };

  // 可拖拽的转发卡片组件
  const SortableForwardCard = ({ forward }: { forward: Forward }) => {
    // 确保 forward 对象有效
    if (!forward || !forward.id) {
      return null;
    }

    const {
      attributes,
      listeners,
      setNodeRef,
      transform,
      transition,
      isDragging,
    } = useSortable({ id: forward.id });

    const style = {
      transform: transform ? CSS.Transform.toString(transform) : undefined,
      transition: transition || undefined,
      opacity: isDragging ? 0.5 : 1,
    };

    return (
      <div ref={setNodeRef} style={style} {...attributes}>
        {renderForwardCard(forward, listeners)}
      </div>
    );
  };

  // 渲染转发卡片
  const renderForwardCard = (forward: Forward, listeners?: any) => {
    const statusDisplay = getStatusDisplay(forward.status, forward.syncState);
    const strategyDisplay = getStrategyDisplay(forward.strategy);
    const entryAddress = getForwardEntryAddress(forward);
    
    return (
      <Card key={forward.id} className="group shadow-sm border border-divider hover:shadow-md transition-shadow duration-200">
        <CardHeader className="pb-2">
          <div className="flex justify-between items-start w-full">
            <div className="flex-1 min-w-0">
              <h3 className="font-semibold text-foreground truncate text-sm">{forward.name}</h3>
              <p className="text-xs text-default-500 truncate">{forward.tunnelName}</p>
            </div>
            <div className="flex items-center gap-1.5 ml-2">
              {viewMode === 'direct' && (
                <div 
                  className={`cursor-grab active:cursor-grabbing p-2 text-default-400 hover:text-default-600 transition-colors touch-manipulation ${
                    isMobile 
                      ? 'opacity-100' // 移动端始终显示
                      : 'opacity-0 group-hover:opacity-100 sm:opacity-0 sm:group-hover:opacity-100'
                  }`}
                  {...listeners}
                  title={isMobile ? "长按拖拽排序" : "拖拽排序"}
                  style={{ touchAction: 'none' }}
                >
                  <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                    <path d="M7 2a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 2zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 8zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 7 14zm6-8a2 2 0 1 1-.001-4.001A2 2 0 0 1 13 6zm0 2a2 2 0 1 1 .001 4.001A2 2 0 0 1 13 8zm0 6a2 2 0 1 1 .001 4.001A2 2 0 0 1 13 14z" />
                  </svg>
                </div>
              )}
              <Switch
                size="sm"
                isSelected={forward.serviceRunning}
                onValueChange={() => handleServiceToggle(forward)}
                isDisabled={(forward.status !== 1 && forward.status !== 0) || operatingForwardIds.has(forward.id)}
              />
              <Chip 
                color={statusDisplay.color as any} 
                variant="flat" 
                size="sm"
                className="text-xs"
              >
                {statusDisplay.text}
              </Chip>
            </div>
          </div>
        </CardHeader>
        
        <CardBody className="pt-0 pb-3">
          <div className="space-y-2">
            {forward.syncError && (
              <Alert color="warning" variant="flat" className="py-1 text-xs">
                {forward.syncError}
              </Alert>
            )}
            {/* 地址信息 */}
            <div className="space-y-1">
              <div 
                className={`cursor-pointer px-2 py-1 bg-default-50 dark:bg-default-100/50 rounded border border-default-200 dark:border-default-300 transition-colors duration-200 ${
                  hasMultipleAddresses(entryAddress) ? 'hover:bg-default-100 dark:hover:bg-default-200/50' : ''
                }`}
                onClick={() => showAddressModal(entryAddress, forward.inPort, '入口端口')}
                title={formatInAddress(entryAddress, forward.inPort)}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 min-w-0 flex-1">
                    <span className="text-xs font-medium text-default-600 flex-shrink-0">入口:</span>
                    <code className="text-xs font-mono text-foreground truncate min-w-0">
                      {formatInAddress(entryAddress, forward.inPort)}
                    </code>
                  </div>
                  {hasMultipleAddresses(entryAddress) && (
                    <svg className="w-3 h-3 text-default-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                    </svg>
                  )}
                </div>
              </div>
              
              <div 
                className={`cursor-pointer px-2 py-1 bg-default-50 dark:bg-default-100/50 rounded border border-default-200 dark:border-default-300 transition-colors duration-200 ${
                  hasMultipleAddresses(forward.remoteAddr) ? 'hover:bg-default-100 dark:hover:bg-default-200/50' : ''
                }`}
                onClick={() => showAddressModal(forward.remoteAddr, null, '目标地址')}
                title={formatRemoteAddress(forward.remoteAddr)}
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-1.5 min-w-0 flex-1">
                    <span className="text-xs font-medium text-default-600 flex-shrink-0">目标:</span>
                    <code className="text-xs font-mono text-foreground truncate min-w-0">
                      {formatRemoteAddress(forward.remoteAddr)}
                    </code>
                  </div>
                  {hasMultipleAddresses(forward.remoteAddr) && (
                    <svg className="w-3 h-3 text-default-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
                    </svg>
                  )}
                </div>
              </div>
            </div>

            <div className="flex flex-wrap gap-1.5 border-t border-divider pt-2">
              <Chip variant="flat" size="sm" className="max-w-full text-xs" color={isAdministrator ? 'secondary' : 'primary'}>
                {isAdministrator ? `创建 / 归属：${forward.userName || '未知用户'}` : `归属：${forward.userName || '我的账号'}`}
              </Chip>
              <Chip variant="flat" size="sm" className="max-w-full text-xs" color={forward.vpsHostId ? 'success' : 'default'}>
                {forward.vpsHostId
                  ? forward.vpsHostName && forward.vpsHostStatus !== 0
                    ? `关联 VPS：${forward.vpsHostName} · ${vpsOriginLabel(forward.vpsHostOrigin)}`
                    : `关联 VPS：${forward.vpsHostName || '已移除 VPS'}（历史记录）`
                  : '目标：手动填写'}
              </Chip>
            </div>

            {/* 统计信息 */}
            <div className="flex items-center justify-between pt-2 border-t border-divider">
              <Chip color={strategyDisplay.color as any} variant="flat" size="sm" className="text-xs">
                {strategyDisplay.text}
              </Chip>
              <div className="flex items-center gap-1">
                <Chip variant="flat" size="sm" className="text-xs" color="primary">
                  ↑{formatFlow(forward.inFlow || 0)}
                </Chip>
               
              </div>
              <Chip variant="flat" size="sm" className="text-xs" color="success">
                  ↓{formatFlow(forward.outFlow || 0)}
                </Chip>
            </div>
          </div>
          
          <div className="flex gap-1.5 mt-3">
            <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={() => handleEdit(forward)}
              isDisabled={forward.status === 2 || forward.status === 3}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
                </svg>
              }
            >
              编辑
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={() => handleDiagnose(forward)}
              isDisabled={forward.status === 3}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
                </svg>
              }
            >
              PING
            </Button>
            <Button
              size="sm"
              variant="flat"
              color="danger"
              onPress={() => handleDelete(forward)}
              isDisabled={forward.status === 3}
              className="flex-1 min-h-8"
              startContent={
                <svg className="w-3 h-3" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M9 2a1 1 0 000 2h2a1 1 0 100-2H9z" clipRule="evenodd" />
                  <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8 7a1 1 0 012 0v4a1 1 0 11-2 0V7zM12 7a1 1 0 012 0v4a1 1 0 11-2 0V7z" clipRule="evenodd" />
                </svg>
              }
            >
              删除
            </Button>
          </div>
        </CardBody>
      </Card>
    );
  };

  if (loading) {
    return (
      
        <div className="flex items-center justify-center h-64">
          <div className="flex items-center gap-3">
            <Spinner size="sm" />
            <span className="text-default-600">正在加载...</span>
          </div>
        </div>
      
    );
  }

  const userGroups = groupForwardsByUserAndTunnel();

  return (
    
      <div className="px-3 lg:px-6 py-8">
        {/* 页面头部 */}
        <div className="flex items-center justify-between mb-6">
          <div className="flex-1">
            <h1 className="text-xl font-bold text-foreground sm:text-2xl">转发管理</h1>
            <p className="mt-1 text-sm text-default-500">
              {isAdministrator
                ? '可查看并管理所有用户创建的转发；规则会标明创建/归属用户和关联的托管 VPS。'
                : '可为自己托管或管理员分配的 VPS 创建转发，并选择管理员已授权给你的隧道。'}
            </p>
          </div>
          <div className="flex items-center gap-3">
            {isAdministrator && viewMode === 'direct' && (
              <Button
                size="sm"
                variant="flat"
                color={adminOnlyMyForwards ? 'primary' : 'default'}
                onPress={() => {
                  const next = !adminOnlyMyForwards;
                  setAdminOnlyMyForwards(next);
                  try {
                    localStorage.setItem('forward-admin-only-mine', String(next));
                  } catch {
                    // The filter remains usable even if storage is blocked.
                  }
                }}
              >
                {adminOnlyMyForwards ? '仅看我的' : '全部用户'}
              </Button>
            )}
            {/* 显示模式切换按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="default"
              onPress={handleViewModeChange}
              isIconOnly
              className="text-sm"
              title={viewMode === 'grouped' ? '切换到直接显示' : '切换到分类显示'}
            >
              {viewMode === 'grouped' ? (
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2zM3 16a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1v-2z" clipRule="evenodd" />
                </svg>
              ) : (
                <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                  <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
                </svg>
              )}
            </Button>
            
            {/* 导入按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="warning"
              onPress={handleImport}
            >
              导入
            </Button>
            
            {/* 导出按钮 */}
            <Button
              size="sm"
              variant="flat"
              color="success"
              onPress={handleExport}
              isLoading={exportLoading}
          
            >
              导出
            </Button>

            <Button
              size="sm"
              variant="flat"
              color="primary"
              onPress={() => handleAdd()}
             
            >
              新增
            </Button>
            
        
          </div>
        </div>


        {/* 根据显示模式渲染不同内容 */}
        {viewMode === 'grouped' ? (
          /* 按用户和隧道分组的转发列表 */
          userGroups.length > 0 ? (
            <div className="space-y-6">
              {userGroups.map((userGroup) => (
                <Card key={userGroup.userId || 'unknown'} className="shadow-sm border border-divider w-full overflow-hidden">
                  <CardHeader className="pb-3">
                    <div className="flex items-center justify-between w-full min-w-0">
                      <div className="flex items-center gap-3 min-w-0 flex-1">
                        <div className="w-10 h-10 bg-primary-100 dark:bg-primary-900/30 rounded-full flex items-center justify-center flex-shrink-0">
                          <svg className="w-5 h-5 text-primary" fill="currentColor" viewBox="0 0 20 20">
                            <path fillRule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clipRule="evenodd" />
                          </svg>
                        </div>
                        <div className="min-w-0 flex-1">
                          <h2 className="text-base font-medium text-foreground truncate max-w-[150px] sm:max-w-[250px] md:max-w-[350px] lg:max-w-[450px]">{userGroup.userName}</h2>
                          <p className="text-xs text-default-500 truncate max-w-[150px] sm:max-w-[250px] md:max-w-[350px] lg:max-w-[450px]">
                            {userGroup.tunnelGroups.length} 个隧道，
                            {userGroup.tunnelGroups.reduce((total, tg) => total + tg.forwards.length, 0)} 个转发
                          </p>
                        </div>
                      </div>
                      <Chip color="primary" variant="flat" size="sm" className="text-xs flex-shrink-0 ml-2">
                        创建 / 归属用户
                      </Chip>
                    </div>
                  </CardHeader>
                  
                  <CardBody className="pt-0">
                    <Accordion variant="splitted" className="px-0">
                      {userGroup.tunnelGroups.map((tunnelGroup) => (
                        <AccordionItem
                          key={tunnelGroup.tunnelId}
                          aria-label={tunnelGroup.tunnelName}
                          title={
                            <div className="flex items-center justify-between w-full min-w-0 pr-4">
                              <div className="flex items-center gap-3 min-w-0 flex-1">
                                <div className="w-8 h-8 bg-success-100 dark:bg-success-900/30 rounded-lg flex items-center justify-center flex-shrink-0">
                                  <svg className="w-4 h-4 text-success" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                                  </svg>
                                </div>
                                <div className="min-w-0 flex-1">
                                  <h3 className="text-sm font-medium text-foreground truncate max-w-[120px] sm:max-w-[200px] md:max-w-[300px] lg:max-w-[400px]">{tunnelGroup.tunnelName}</h3>
                                </div>
                              </div>
                              <div className="flex items-center gap-2 flex-shrink-0 ml-2">
                                <Chip variant="flat" size="sm" className="text-xs">
                                  {tunnelGroup.forwards.filter(f => f.serviceRunning).length}/{tunnelGroup.forwards.length}
                                </Chip>
                              </div>
                            </div>
                          }
                          className="shadow-none border border-divider"
                        >
                          <div className="flex flex-col gap-2 border-b border-divider px-4 pb-3 pt-1 sm:flex-row sm:items-center sm:justify-between">
                            <p className="text-xs text-default-500">检测此隧道下全部转发的完整 TCP 链路（最多 20 条，30 秒冷却）</p>
                            <Button
                              size="sm"
                              color="primary"
                              variant="flat"
                              className="min-h-9 flex-shrink-0"
                              onPress={() => handleDiagnoseTunnelForwards(tunnelGroup)}
                            >
                              一键 PING
                            </Button>
                          </div>
                          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4 p-4">
                            {tunnelGroup.forwards.map((forward) => renderForwardCard(forward, undefined))}
                          </div>
                        </AccordionItem>
                      ))}
                    </Accordion>
                  </CardBody>
                </Card>
              ))}
            </div>
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        ) : (
          /* 直接显示模式 */
          forwards.length > 0 ? (
            <>
              {!isAdministrator && userGroups.flatMap((group) => group.tunnelGroups).length > 0 && (
                <Card className="mb-4 border border-primary-200 bg-primary-50/50 shadow-none dark:border-primary-300/20 dark:bg-primary-100/10">
                  <CardBody className="gap-3 p-3 sm:flex-row sm:items-center sm:justify-between">
                    <p className="text-sm text-default-700 dark:text-default-300">按隧道检测全部转发链路</p>
                    <div className="flex flex-wrap gap-2">
                      {userGroups.flatMap((group) => group.tunnelGroups).map((tunnelGroup) => (
                        <Button
                          key={tunnelGroup.tunnelId}
                          size="sm"
                          color="primary"
                          variant="flat"
                          onPress={() => handleDiagnoseTunnelForwards(tunnelGroup)}
                        >
                          {tunnelGroup.tunnelName} · 一键 PING
                        </Button>
                      ))}
                    </div>
                  </CardBody>
                </Card>
              )}
              <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                onDragEnd={handleDragEnd}
                onDragStart={() => {}} // 添加空的 onDragStart 处理器
              >
                <SortableContext
                  items={getSortedForwards().map(f => f.id || 0).filter(id => id > 0)}
                  strategy={rectSortingStrategy}
                >
                  <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-4">
                    {getSortedForwards().map((forward) => (
                      forward && forward.id ? (
                        <SortableForwardCard key={forward.id} forward={forward} />
                      ) : null
                    ))}
                  </div>
                </SortableContext>
              </DndContext>
            </>
          ) : (
            /* 空状态 */
            <Card className="shadow-sm border border-gray-200 dark:border-gray-700">
              <CardBody className="text-center py-16">
                <div className="flex flex-col items-center gap-4">
                  <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center">
                    <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 9l4-4 4 4m0 6l-4 4-4-4" />
                    </svg>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold text-foreground">暂无转发配置</h3>
                    <p className="text-default-500 text-sm mt-1">还没有创建任何转发配置，点击上方按钮开始创建</p>
                  </div>
                </div>
              </CardBody>
            </Card>
          )
        )}

        {/* 新增/编辑模态框 */}
        <Modal 
          isOpen={modalOpen}
          onOpenChange={setModalOpen}
          size="2xl"
          scrollBehavior="outside"
          backdrop="blur"
          placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">
                    {isEdit ? '编辑转发' : '新增转发'}
                  </h2>
                  <p className="text-small text-default-500">
                    {isEdit ? '修改现有转发配置的信息' : '创建新的转发配置'}
                  </p>
                </ModalHeader>
                <ModalBody>
                  <div className="space-y-4 pb-4">
                    <Input
                      label="转发名称"
                      placeholder="请输入转发名称"
                      value={form.name}
                      onChange={(e) => setForm(prev => ({ ...prev, name: e.target.value }))}
                      isInvalid={!!errors.name}
                      errorMessage={errors.name}
                      variant="bordered"
                    />
                    
                    <Select
                      label="关联托管 VPS（可选）"
                      placeholder="选择目标服务所在的 VPS"
                      selectedKeys={form.vpsHostId !== null ? [String(form.vpsHostId)] : ['0']}
                      onSelectionChange={(keys) => {
                        if (keys === 'all') return;
                        const selectedKey = Array.from(keys)[0];
                        if (selectedKey === undefined) return;
                        if (String(selectedKey) === '0') {
                          setForm((previous) => ({
                            ...previous,
                            vpsHostId: null,
                            clearVpsHost: true,
                            vpsHostName: undefined,
                            vpsHostOrigin: undefined,
                          }));
                          return;
                        }
                        const host = vpsOptions.find((item) => String(item.id) === String(selectedKey));
                        if (!host) return;
                        setForm((previous) => ({
                          ...previous,
                          vpsHostId: host.id,
                          clearVpsHost: false,
                          vpsHostName: host.name.replace('（历史关联）', ''),
                          vpsHostOrigin: host.origin,
                          remoteAddr: previous.remoteAddr.trim() ? previous.remoteAddr : formatVpsTargetAddress(host),
                        }));
                      }}
                      variant="bordered"
                      description={
                        selectedVpsHost
                          ? `已关联 ${selectedVpsHost.name}；请在目标地址中补全该 VPS 上服务的端口。`
                          : form.vpsHostId !== null
                            ? '该 VPS 已从托管列表移除，历史关联会保留；可改选可用 VPS 或选择不关联。'
                            : '可选择自己托管或管理员分配的 VPS。关联会记录在转发中，管理员可统一查看和管理。'
                      }
                    >
                      {vpsOptions.map((host) => (
                        <SelectItem key={String(host.id)} textValue={host.id === 0 ? host.name : `${host.name} · ${vpsOriginLabel(host.origin)}`}>
                          {host.id === 0 ? host.name : <>{host.name} · {vpsOriginLabel(host.origin)}{host.host ? ` · ${host.host}` : ''}</>}
                        </SelectItem>
                      ))}
                    </Select>

                    <Select
                      label={isAdministrator ? '选择隧道' : '选择已授权隧道'}
                      placeholder="请选择关联的隧道"
                      selectedKeys={form.tunnelId !== null ? [String(form.tunnelId)] : []}
                      onSelectionChange={(keys) => {
                        if (keys !== 'all') {
                          const selectedKey = Array.from(keys)[0];
                          if (selectedKey !== undefined) {
                            handleTunnelChange(String(selectedKey));
                          }
                        }
                      }}
                      isInvalid={!!errors.tunnelId}
                      errorMessage={errors.tunnelId}
                      variant="bordered"
                      description={
                        isAdministrator
                          ? '管理员可管理任意隧道；保存时仍会按照该转发归属用户的权限、配额和限速进行校验。'
                          : '这里只显示管理员已分配给你的隧道；入口地址、端口范围、流量配额和限速均按该授权执行。'
                      }
                    >
                      {tunnels.map((tunnel) => (
                        <SelectItem key={String(tunnel.id)} textValue={`${tunnel.name}${tunnel.ip ? ` · ${tunnel.ip}` : ''}`}>
                          {tunnel.name}{tunnel.ip ? ` · ${tunnel.ip}` : ''}
                        </SelectItem>
                      ))}
                    </Select>

                    {selectedTunnel && (
                      <Input
                        label="你的入口地址"
                        value={selectedTunnel.ip || '原始入口地址'}
                        isReadOnly
                        variant="flat"
                        description={
                          selectedTunnel.entryAddressMode === 'CUSTOM'
                            ? '管理员为你指定的解析域名；新建后的转发会使用同一入口端口。'
                            : selectedTunnel.entryAddressMode === 'DEFAULT'
                              ? '该隧道的默认解析域名；新建后的转发会使用同一入口端口。'
                              : '管理员未分配解析域名，使用隧道原始入口地址。'
                        }
                      />
                    )}
                    
                    <Input
                      label="入口端口"
                      placeholder="留空随机分配"
                      type="number"
                      value={form.inPort?.toString() || ''}
                      onChange={(e) => setForm(prev => ({ 
                        ...prev, 
                        inPort: e.target.value ? parseInt(e.target.value) : null 
                      }))}
                      isInvalid={!!errors.inPort}
                      errorMessage={errors.inPort}
                      variant="bordered"
                      description={
                        selectedTunnel && selectedTunnel.inNodePortSta && selectedTunnel.inNodePortEnd
                          ? `允许范围: ${selectedTunnel.inNodePortSta}-${selectedTunnel.inNodePortEnd}`
                          : '留空将从节点端口范围随机分配未占用端口'
                      }
                    />
                    
                    <Textarea
                      label={selectedVpsHost ? `目标地址 · ${selectedVpsHost.name}` : '目标地址'}
                      placeholder="请输入远程地址，多个地址用换行分隔&#10;例如:&#10;192.168.1.100:8080&#10;example.com:3000"
                      value={form.remoteAddr}
                      onChange={(e) => setForm(prev => ({ ...prev, remoteAddr: e.target.value }))}
                      isInvalid={!!errors.remoteAddr}
                      errorMessage={errors.remoteAddr}
                      variant="bordered"
                      description={
                        selectedVpsHost
                          ? `已关联 ${selectedVpsHost.name}：填写该 VPS 上实际服务的 IP/域名和端口；支持多个地址（每行一个）。`
                          : '格式: IP:端口 或 域名:端口，支持多个地址（每行一个）'
                      }
                      minRows={3}
                      maxRows={6}
                    />
                    
                    <Input
                      label="出口网卡名或IP"
                      placeholder="请输入出口网卡名或IP"
                      value={form.interfaceName}
                      onChange={(e) => setForm(prev => ({ ...prev, interfaceName: e.target.value }))}
                      isInvalid={!!errors.interfaceName}
                      errorMessage={errors.interfaceName}
                      variant="bordered"
                      description="用于多IP服务器指定使用那个IP请求远程地址，不懂的默认为空就行"
                    />
                    
                    {getAddressCount(form.remoteAddr) > 1 && (
                      <Select
                        label="负载策略"
                        placeholder="请选择负载均衡策略"
                        selectedKeys={[form.strategy]}
                        onSelectionChange={(keys) => {
                          const selectedKey = Array.from(keys)[0] as string;
                          setForm(prev => ({ ...prev, strategy: selectedKey }));
                        }}
                        variant="bordered"
                        description="多个目标地址的负载均衡策略"
                      >
                        <SelectItem key="fifo" >主备模式 - 自上而下</SelectItem>
                        <SelectItem key="round" >轮询模式 - 依次轮换</SelectItem>
                        <SelectItem key="rand" >随机模式 - 随机选择</SelectItem>
                        <SelectItem key="hash" >哈希模式 - IP哈希</SelectItem>
                      </Select>
                    )}
                  </div>
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    取消
                  </Button>
                  <Button 
                    color="primary" 
                    onPress={handleSubmit}
                    isLoading={submitLoading}
                  >
                    {isEdit ? '保存修改' : '创建转发'}
                  </Button>
                </ModalFooter>
              </>
            )}
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
                  <h2 className="text-lg font-bold text-danger">确认删除</h2>
                </ModalHeader>
                <ModalBody>
                  <p className="text-default-600">
                    确定要删除转发 <span className="font-semibold text-foreground">"{forwardToDelete?.name}"</span> 吗？
                  </p>
                  <p className="text-small text-default-500 mt-2">
                    此操作无法撤销，删除后该转发将永久消失。
                  </p>
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
                    确认删除
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 地址列表弹窗 */}
        <Modal isOpen={addressModalOpen} onClose={() => setAddressModalOpen(false)} size="lg" scrollBehavior="outside">
          <ModalContent>
            <ModalHeader className="text-base">{addressModalTitle}</ModalHeader>
            <ModalBody className="pb-6">
              <div className="mb-4 text-right">
                <Button size="sm" onClick={copyAllAddresses}>
                  复制
                </Button>
              </div>
              
              <div className="space-y-2 max-h-60 overflow-y-auto">
                {addressList.map((item) => (
                  <div key={item.id} className="flex justify-between items-center p-3 border border-default-200 dark:border-default-100 rounded-lg">
                    <code className="text-sm flex-1 mr-3 text-foreground">{item.address}</code>
                    <Button
                      size="sm"
                      variant="light"
                      isLoading={item.copying}
                      onClick={() => copyAddress(item)}
                    >
                      复制
                    </Button>
                  </div>
                ))}
              </div>
            </ModalBody>
          </ModalContent>
        </Modal>

        {/* 导出数据模态框 */}
        <Modal 
          isOpen={exportModalOpen} 
          onClose={() => {
            setExportModalOpen(false);
            setSelectedTunnelForExport(null);
            setExportData('');
          }} 
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导出转发数据</h2>
              <p className="text-small text-default-500">
                格式：目标地址|转发名称|入口端口|当前用户入口地址
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                {/* 隧道选择 */}
                <div>
                  <Select
                    label="选择导出隧道"
                    placeholder="请选择要导出的隧道"
                    selectedKeys={selectedTunnelForExport ? [selectedTunnelForExport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForExport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                </div>

                {/* 导出按钮和数据 */}
                {exportData && (
                  <div className="flex justify-between items-center">
                    <Button 
                      color="primary" 
                      size="sm" 
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={!selectedTunnelForExport}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      重新生成
                    </Button>
                    <Button 
                      color="secondary" 
                      size="sm" 
                      onPress={copyExportData}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path d="M8 3a1 1 0 011-1h2a1 1 0 110 2H9a1 1 0 01-1-1z" />
                          <path d="M6 3a2 2 0 00-2 2v11a2 2 0 002 2h8a2 2 0 002-2V5a2 2 0 00-2-2 3 3 0 01-3 3H9a3 3 0 01-3-3z" />
                        </svg>
                      }
                    >
                      复制
                    </Button>
                  </div>
                )}

                {/* 初始导出按钮 */}
                {!exportData && (
                  <div className="text-right">
                    <Button 
                      color="primary" 
                      size="sm" 
                      onPress={executeExport}
                      isLoading={exportLoading}
                      isDisabled={!selectedTunnelForExport}
                      startContent={
                        <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zM6.293 6.707a1 1 0 010-1.414l3-3a1 1 0 011.414 0l3 3a1 1 0 01-1.414 1.414L11 5.414V13a1 1 0 11-2 0V5.414L7.707 6.707a1 1 0 01-1.414 0z" clipRule="evenodd" />
                        </svg>
                      }
                    >
                      生成导出数据
                    </Button>
                  </div>
                )}

                {/* 导出数据显示 */}
                {exportData && (
                  <div className="relative">
                    <Textarea
                      value={exportData}
                      readOnly
                      variant="bordered"
                      minRows={10}
                      maxRows={20}
                      className="font-mono text-sm"
                      classNames={{
                        input: "font-mono text-sm"
                      }}
                      placeholder="暂无数据"
                    />
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button 
                variant="light" 
                onPress={() => setExportModalOpen(false)}
              >
                关闭
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 导入数据模态框 */}
        <Modal 
          isOpen={importModalOpen} 
          onClose={() => setImportModalOpen(false)} 
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            <ModalHeader className="flex flex-col gap-1">
              <h2 className="text-xl font-bold">导入转发数据</h2>
              <p className="text-small text-default-500">
                推荐使用本面板导出的 v2 JSON 文件；仍兼容旧格式：目标地址|转发名称|入口端口，每行一个
              </p>
              <p className="text-small text-default-400">
                v2 会保留负载策略；旧格式的第 4 列仍会忽略。目标地址支持域名/IPv4:端口及 [IPv6]:端口，多个地址可用逗号分隔；重复记录会被安全跳过。
              </p>
            </ModalHeader>
            <ModalBody className="pb-6">
              <div className="space-y-4">
                {/* 隧道选择 */}
                <div>
                  <Select
                    label="选择导入隧道"
                    placeholder="请选择要导入的隧道"
                    selectedKeys={selectedTunnelForImport ? [selectedTunnelForImport.toString()] : []}
                    onSelectionChange={(keys) => {
                      const selectedKey = Array.from(keys)[0] as string;
                      setSelectedTunnelForImport(selectedKey ? parseInt(selectedKey) : null);
                    }}
                    variant="bordered"
                    isRequired
                  >
                    {tunnels.map((tunnel) => (
                      <SelectItem key={tunnel.id.toString()} textValue={tunnel.name}>
                        {tunnel.name}
                      </SelectItem>
                    ))}
                  </Select>
                </div>

                {/* 输入区域 */}
                <div>
                  <Textarea
                    label="导入数据"
                    placeholder="粘贴 v2 JSON，或旧格式：目标地址|转发名称|入口端口"
                    value={importData}
                    onChange={(e) => setImportData(e.target.value)}
                    variant="flat"
                    minRows={8}
                    maxRows={12}
                    classNames={{
                      input: "font-mono text-sm"
                    }}
                  />

                
                </div>

                {/* 导入结果 */}
                {importResults.length > 0 && (
                  <div>
                    <div className="flex items-center justify-between mb-2">
                      <h3 className="text-base font-semibold">导入结果</h3>
                      <div className="flex items-center gap-2">
                        <span className="text-xs text-default-500">
                          成功：{importResults.filter(r => r.success).length} / 
                          总计：{importResults.length}
                        </span>
                      </div>
                    </div>
                    
                    <div className="max-h-40 overflow-y-auto space-y-1" style={{
                      scrollbarWidth: 'thin',
                      scrollbarColor: 'rgb(156 163 175) transparent'
                    }}>
                      {importResults.map((result, index) => (
                        <div 
                          key={index} 
                          className={`p-2 rounded border ${
                            result.success 
                              ? 'bg-success-50 dark:bg-success-100/10 border-success-200 dark:border-success-300/20' 
                              : 'bg-danger-50 dark:bg-danger-100/10 border-danger-200 dark:border-danger-300/20'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            {result.success ? (
                              <svg className="w-3 h-3 text-success-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
                              </svg>
                            ) : (
                              <svg className="w-3 h-3 text-danger-600 flex-shrink-0" fill="currentColor" viewBox="0 0 20 20">
                                <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
                              </svg>
                            )}
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-0.5">
                                <span className={`text-xs font-medium ${
                                  result.success ? 'text-success-700 dark:text-success-300' : 'text-danger-700 dark:text-danger-300'
                                }`}>
                                  {result.success ? '成功' : '失败'}
                                </span>
                                <span className="text-xs text-default-500">|</span>
                                <code className="text-xs font-mono text-default-600 truncate">{result.line}</code>
                              </div>
                              <div className={`text-xs ${
                                result.success ? 'text-success-600 dark:text-success-400' : 'text-danger-600 dark:text-danger-400'
                              }`}>
                                {result.message}
                              </div>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button 
                variant="light" 
                onPress={() => setImportModalOpen(false)}
              >
                关闭
              </Button>
              <Button 
                color="warning" 
                onPress={executeImport}
                isLoading={importLoading}
                isDisabled={!importData.trim() || !selectedTunnelForImport}
              >
                开始导入
              </Button>
            </ModalFooter>
          </ModalContent>
        </Modal>

        {/* 隧道一键 PING 结果：只从具体隧道的展开区发起 */}
        <Modal
          isOpen={tunnelPingModalOpen}
          onOpenChange={(open) => {
            if (open) {
              setTunnelPingModalOpen(true);
            } else {
              closeTunnelPingModal();
            }
          }}
          size="3xl"
          scrollBehavior="inside"
          backdrop="blur"
          placement="center"
          isDismissable
          isKeyboardDismissDisabled={false}
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">隧道一键 PING</h2>
                  <p className="text-small font-normal text-default-500">
                    {tunnelPingSummary?.tunnelName || '正在准备检测'}
                  </p>
                </ModalHeader>
                <ModalBody>
                  {tunnelPingSummary && (
                    <div className="grid grid-cols-3 gap-2">
                      <Card className="border border-divider shadow-none">
                        <CardBody className="items-center gap-0 py-3">
                          <span className="text-xl font-semibold">{tunnelPingSummary.totalForwards}</span>
                          <span className="text-tiny text-default-500">全部转发</span>
                        </CardBody>
                      </Card>
                      <Card className="border border-success/30 shadow-none">
                        <CardBody className="items-center gap-0 py-3">
                          <span className="text-xl font-semibold text-success">{tunnelPingSummary.successfulForwards}</span>
                          <span className="text-tiny text-default-500">已通过</span>
                        </CardBody>
                      </Card>
                      <Card className="border border-danger/30 shadow-none">
                        <CardBody className="items-center gap-0 py-3">
                          <span className="text-xl font-semibold text-danger">{tunnelPingSummary.failedForwards}</span>
                          <span className="text-tiny text-default-500">未通过</span>
                        </CardBody>
                      </Card>
                    </div>
                  )}

                  {tunnelPingLoading && (
                    <div className="flex items-center gap-2 rounded-lg bg-primary-50 px-3 py-2 text-small text-primary dark:bg-primary-100/10">
                      <Spinner size="sm" color="primary" />
                      正在检测 {tunnelPingSummary?.completedForwards ?? 0}/{tunnelPingSummary?.totalForwards ?? 0} 条转发；关闭窗口会取消剩余检测
                    </div>
                  )}

                  {tunnelPingSummary?.forwards.length ? (
                    <div className="space-y-3 pb-1">
                      {tunnelPingSummary.forwards.map((forward) => (
                        <Card
                          key={forward.forwardId}
                          className={`border shadow-none ${forward.success ? 'border-success/40' : 'border-danger/40'}`}
                        >
                          <CardHeader className="items-start justify-between gap-3 pb-2">
                            <div className="min-w-0">
                              <h3 className="truncate text-base font-semibold">{forward.forwardName}</h3>
                              <code className="block truncate text-tiny text-default-500" title={forward.remoteAddress}>
                                {forward.remoteAddress || '-'}
                              </code>
                            </div>
                            <Chip color={forward.success ? 'success' : 'danger'} variant="flat" size="sm">
                              {forward.success ? '通过' : '未通过'}
                            </Chip>
                          </CardHeader>
                          <CardBody className="gap-2 pt-0">
                            {forward.results.length ? forward.results.map((result, index) => (
                              <div key={`${forward.forwardId}-${index}`} className="flex items-center justify-between gap-3 rounded-medium bg-default-100 px-3 py-2 dark:bg-default-50/10">
                                <span className="min-w-0 truncate text-small">{result.description}</span>
                                <span className={`shrink-0 text-tiny font-medium ${result.success ? 'text-success' : 'text-danger'}`}>
                                  {result.success
                                    ? `${result.averageTime?.toFixed(0) ?? '-'} ms · ${result.packetLoss?.toFixed(0) ?? '-'}% 丢包`
                                    : '连接失败'}
                                </span>
                              </div>
                            )) : (
                              <Alert color="danger" variant="flat" title="检测失败" description={forward.message || '未返回有效检测数据'} />
                            )}
                            {!forward.success && forward.results.length > 0 && forward.message && (
                              <p className="text-tiny text-danger">{forward.message}</p>
                            )}
                          </CardBody>
                        </Card>
                      ))}
                    </div>
                  ) : !tunnelPingLoading ? (
                    <div className="py-12 text-center text-small text-default-500">暂无检测结果</div>
                  ) : null}
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    {tunnelPingLoading ? '取消并关闭' : '关闭'}
                  </Button>
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>

        {/* 单条转发诊断结果模态框 */}
        <Modal 
          isOpen={diagnosisModalOpen}
          onOpenChange={setDiagnosisModalOpen}
          
          size="2xl"
        scrollBehavior="outside"
        backdrop="blur"
        placement="center"
        >
          <ModalContent>
            {(onClose) => (
              <>
                <ModalHeader className="flex flex-col gap-1">
                  <h2 className="text-xl font-bold">转发诊断结果</h2>
                  {currentDiagnosisForward && (
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-small text-default-500 truncate flex-1 min-w-0">{currentDiagnosisForward.name}</span>
                      <Chip 
                        color="primary"
                        variant="flat" 
                        size="sm"
                        className="flex-shrink-0"
                      >
                        转发服务
                      </Chip>
                    </div>
                  )}
                </ModalHeader>
                <ModalBody>
                  {diagnosisLoading ? (
                    <div className="flex items-center justify-center py-16">
                      <div className="flex items-center gap-3">
                        <Spinner size="sm" />
                        <span className="text-default-600">正在诊断转发连接...</span>
                      </div>
                    </div>
                  ) : diagnosisResult ? (
                    <div className="space-y-4">
                      {diagnosisResult.results.map((result, index) => {
                        const quality = getQualityDisplay(result.averageTime, result.packetLoss);
                        
                        return (
                          <Card key={index} className={`shadow-sm border ${result.success ? 'border-success' : 'border-danger'}`}>
                            <CardHeader className="pb-2">
                              <div className="flex items-center justify-between w-full">
                                <div>
                                  <h3 className="text-lg font-semibold text-foreground">{result.description}</h3>
                                  <div className="flex items-center gap-2 mt-1">
                                    <span className="text-small text-default-500">节点: {result.nodeName}</span>
                                    <Chip 
                                      color={result.success ? 'success' : 'danger'} 
                                      variant="flat" 
                                      size="sm"
                                    >
                                      {result.success ? '连接成功' : '连接失败'}
                                    </Chip>
                                  </div>
                                </div>
                              </div>
                            </CardHeader>
                            
                            <CardBody className="pt-0">
                              {result.success ? (
                                <div className="space-y-3">
                                  <div className="grid grid-cols-3 gap-4">
                                    <div className="text-center">
                                      <div className="text-2xl font-bold text-primary">{result.averageTime?.toFixed(0)}</div>
                                      <div className="text-small text-default-500">平均延迟(ms)</div>
                                    </div>
                                    <div className="text-center">
                                      <div className="text-2xl font-bold text-warning">{result.packetLoss?.toFixed(1)}</div>
                                      <div className="text-small text-default-500">丢包率(%)</div>
                                    </div>
                                    <div className="text-center">
                                      {quality && (
                                        <>
                                          <Chip color={quality.color as any} variant="flat" size="lg">
                                            {quality.text}
                                          </Chip>
                                          <div className="text-small text-default-500 mt-1">连接质量</div>
                                        </>
                                      )}
                                    </div>
                                  </div>
                                  <div className="text-small text-default-500 flex items-center gap-1">
                                    <span className="flex-shrink-0">目标地址:</span>
                                    <code className="font-mono truncate min-w-0" title={`${result.targetIp}${result.targetPort ? ':' + result.targetPort : ''}`}>
                                      {result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}
                                    </code>
                                  </div>
                                </div>
                              ) : (
                                <div className="space-y-2">
                                  <div className="text-small text-default-500 flex items-center gap-1">
                                    <span className="flex-shrink-0">目标地址:</span>
                                    <code className="font-mono truncate min-w-0" title={`${result.targetIp}${result.targetPort ? ':' + result.targetPort : ''}`}>
                                      {result.targetIp}{result.targetPort ? ':' + result.targetPort : ''}
                                    </code>
                                  </div>
                                  <Alert
                                    color="danger"
                                    variant="flat"
                                    title="错误详情"
                                    description={result.message}
                                  />
                                </div>
                              )}
                            </CardBody>
                          </Card>
                        );
                      })}
                    </div>
                  ) : (
                    <div className="text-center py-16">
                      <div className="w-16 h-16 bg-default-100 rounded-full flex items-center justify-center mx-auto mb-4">
                        <svg className="w-8 h-8 text-default-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M9.75 9.75l4.5 4.5m0-4.5l-4.5 4.5M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                        </svg>
                      </div>
                      <h3 className="text-lg font-semibold text-foreground">暂无诊断数据</h3>
                    </div>
                  )}
                </ModalBody>
                <ModalFooter>
                  <Button variant="light" onPress={onClose}>
                    关闭
                  </Button>
                  {currentDiagnosisForward && (
                    <Button 
                      color="primary" 
                      onPress={() => handleDiagnose(currentDiagnosisForward)}
                      isLoading={diagnosisLoading}
                    >
                      重新诊断
                    </Button>
                  )}
                </ModalFooter>
              </>
            )}
          </ModalContent>
        </Modal>
      </div>
    
  );
}
