import { useEffect, useMemo, useState } from 'react';
import { Button } from '@heroui/button';
import { Card, CardBody, CardHeader } from '@heroui/card';
import { Chip } from '@heroui/chip';
import { Input } from '@heroui/input';
import { Modal, ModalBody, ModalContent, ModalFooter, ModalHeader } from '@heroui/modal';
import { Spinner } from '@heroui/spinner';
import { Textarea } from '@heroui/input';
import toast from 'react-hot-toast';

import {
  checkVpsHost,
  createVpsHost,
  deleteVpsHost,
  deployVpsTemplate,
  getVpsAssignableUsers,
  getVpsDeploymentTasks,
  getVpsHosts,
  resetVpsHostFingerprint,
  updateVpsHost,
} from '@/api';
import { VpsTerminal } from '@/components/vps-terminal';

type DeploymentTemplate = 'docker' | 'flux_panel';

interface VpsHost {
  id: number;
  name: string;
  host: string;
  sshPort: number;
  sshUsername: string;
  origin: 'USER' | 'ADMIN';
  ownerUserId?: number;
  ownerUserName?: string;
  assignedUserId?: number;
  assignedUserName?: string;
  remark?: string;
  sshFingerprint?: string;
  healthStatus: 'unknown' | 'online' | 'offline' | 'fingerprint_changed';
  lastCheckTime?: number;
  lastCheckMessage?: string;
  lastLatencyMs?: number;
  canOperate: boolean;
  canManage: boolean;
  canAssign: boolean;
}

interface AssignableUser {
  id: number;
  user: string;
}

interface DeploymentTask {
  id: number;
  vpsId: number;
  requestedByUserName?: string;
  taskType: DeploymentTemplate;
  taskStatus: 'pending' | 'running' | 'succeeded' | 'failed';
  outputLog?: string;
  startedTime?: number;
  finishedTime?: number;
  createdTime?: number;
}

interface VpsForm {
  name: string;
  host: string;
  sshPort: string;
  sshUsername: string;
  sshPassword: string;
  remark: string;
  assignedUserId: string;
  adminAccessAcknowledged: boolean;
}

const emptyForm = (): VpsForm => ({
  name: '',
  host: '',
  sshPort: '22',
  sshUsername: 'root',
  sshPassword: '',
  remark: '',
  assignedUserId: '',
  adminAccessAcknowledged: false,
});

const formatTime = (value?: number) => value ? new Date(value).toLocaleString() : '尚未检测';

const statusMeta = (status: VpsHost['healthStatus']) => {
  if (status === 'online') return { color: 'success' as const, text: 'SSH 在线' };
  if (status === 'offline') return { color: 'danger' as const, text: 'SSH 离线' };
  if (status === 'fingerprint_changed') return { color: 'warning' as const, text: '主机指纹变化' };
  return { color: 'default' as const, text: '等待检测' };
};

const taskMeta = (status: DeploymentTask['taskStatus']) => {
  if (status === 'succeeded') return { color: 'success' as const, text: '已完成' };
  if (status === 'failed') return { color: 'danger' as const, text: '失败' };
  if (status === 'running') return { color: 'primary' as const, text: '执行中' };
  return { color: 'warning' as const, text: '排队中' };
};

export default function VpsPage() {
  const [isAdmin, setIsAdmin] = useState(false);
  const [hosts, setHosts] = useState<VpsHost[]>([]);
  const [assignableUsers, setAssignableUsers] = useState<AssignableUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [formOpen, setFormOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [editingHost, setEditingHost] = useState<VpsHost | null>(null);
  const [form, setForm] = useState<VpsForm>(emptyForm());
  const [terminalHost, setTerminalHost] = useState<VpsHost | null>(null);
  const [deploymentHost, setDeploymentHost] = useState<VpsHost | null>(null);
  const [deploymentTemplate, setDeploymentTemplate] = useState<DeploymentTemplate>('docker');
  const [deploymentSubmitting, setDeploymentSubmitting] = useState(false);
  const [tasksHost, setTasksHost] = useState<VpsHost | null>(null);
  const [tasks, setTasks] = useState<DeploymentTask[]>([]);
  const [tasksLoading, setTasksLoading] = useState(false);

  const loadHosts = async (quiet = false) => {
    if (!quiet) setLoading(true);
    try {
      const response = await getVpsHosts();
      if (response.code === 0) {
        setHosts(Array.isArray(response.data) ? response.data : []);
      } else if (!quiet) {
        toast.error(response.msg || '加载 VPS 列表失败');
      }
    } catch {
      if (!quiet) toast.error('加载 VPS 列表失败');
    } finally {
      if (!quiet) setLoading(false);
    }
  };

  const loadAssignableUsers = async () => {
    const response = await getVpsAssignableUsers();
    if (response.code === 0 && Array.isArray(response.data)) setAssignableUsers(response.data);
  };

  const loadTasks = async (host: VpsHost) => {
    setTasksLoading(true);
    try {
      const response = await getVpsDeploymentTasks(host.id);
      if (response.code === 0) setTasks(Array.isArray(response.data) ? response.data : []);
      else toast.error(response.msg || '加载部署任务失败');
    } finally {
      setTasksLoading(false);
    }
  };

  useEffect(() => {
    const admin = localStorage.getItem('admin') === 'true' || localStorage.getItem('role_id') === '0';
    setIsAdmin(admin);
    void loadHosts();
    if (admin) void loadAssignableUsers();
  }, []);

  useEffect(() => {
    if (!tasksHost) return;
    void loadTasks(tasksHost);
    const timer = window.setInterval(() => void loadTasks(tasksHost), 3000);
    return () => window.clearInterval(timer);
  }, [tasksHost?.id]);

  const isEditing = editingHost !== null;
  const hostSummary = useMemo(() => ({
    online: hosts.filter((host) => host.healthStatus === 'online').length,
    assigned: hosts.filter((host) => host.origin === 'ADMIN' && host.assignedUserId).length,
  }), [hosts]);

  const openCreate = () => {
    setEditingHost(null);
    setForm(emptyForm());
    setFormOpen(true);
  };

  const openEdit = (host: VpsHost) => {
    setEditingHost(host);
    setForm({
      name: host.name,
      host: host.host,
      sshPort: String(host.sshPort || 22),
      sshUsername: host.sshUsername,
      sshPassword: '',
      remark: host.remark || '',
      assignedUserId: host.assignedUserId ? String(host.assignedUserId) : '',
      adminAccessAcknowledged: false,
    });
    setFormOpen(true);
  };

  const updateForm = <K extends keyof VpsForm>(key: K, value: VpsForm[K]) => {
    setForm((previous) => ({ ...previous, [key]: value }));
  };

  const submitForm = async () => {
    const port = Number(form.sshPort);
    if (!form.name.trim() || !form.host.trim() || !form.sshUsername.trim() || !Number.isInteger(port) || port < 1 || port > 65535) {
      toast.error('请完整填写 VPS 名称、SSH 地址、端口和用户名');
      return;
    }
    if (!isEditing && !form.sshPassword) {
      toast.error('首次托管必须填写 SSH 密码');
      return;
    }
    if (!isAdmin && !isEditing && !form.adminAccessAcknowledged) {
      toast.error('请确认管理员可维护你托管的 VPS');
      return;
    }

    const payload = {
      name: form.name.trim(),
      host: form.host.trim(),
      sshPort: port,
      sshUsername: form.sshUsername.trim(),
      sshPassword: form.sshPassword,
      remark: form.remark.trim(),
      assignedUserId: isAdmin && form.assignedUserId ? Number(form.assignedUserId) : null,
      adminAccessAcknowledged: form.adminAccessAcknowledged,
    };
    setSubmitting(true);
    try {
      const response = isEditing
        ? await updateVpsHost({ ...payload, id: editingHost.id })
        : await createVpsHost(payload);
      if (response.code === 0) {
        toast.success(isEditing ? 'VPS 托管信息已保存' : 'VPS 已加入托管，正在等待 SSH 检测');
        setFormOpen(false);
        await loadHosts(true);
      } else {
        toast.error(response.msg || '保存 VPS 托管信息失败');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const checkHost = async (host: VpsHost) => {
    const response = await checkVpsHost(host.id);
    if (response.code === 0) {
      setHosts((previous) => previous.map((item) => item.id === host.id ? response.data : item));
      toast.success(response.data?.healthStatus === 'online' ? 'SSH 连接正常' : '检测完成');
    } else {
      toast.error(response.msg || 'SSH 检测失败');
    }
  };

  const removeHost = async (host: VpsHost) => {
    if (!window.confirm(`确认移除 VPS「${host.name}」吗？这不会删除远程服务器，只会删除面板中的托管记录。`)) return;
    const response = await deleteVpsHost(host.id);
    if (response.code === 0) {
      toast.success('VPS 托管已移除');
      await loadHosts(true);
    } else {
      toast.error(response.msg || '移除失败');
    }
  };

  const resetFingerprint = async (host: VpsHost) => {
    if (!window.confirm(`确认重新信任「${host.name}」的新 SSH 主机指纹吗？仅应在你确认服务器已重装或更换时执行。`)) return;
    const response = await resetVpsHostFingerprint(host.id);
    if (response.code === 0) {
      setHosts((previous) => previous.map((item) => item.id === host.id ? response.data : item));
      toast.success('已清除旧指纹，请重新执行一键 Ping');
    } else {
      toast.error(response.msg || '重置 SSH 主机指纹失败');
    }
  };

  const startDeployment = async () => {
    if (!deploymentHost) return;
    setDeploymentSubmitting(true);
    try {
      const response = await deployVpsTemplate(deploymentHost.id, deploymentTemplate);
      if (response.code === 0) {
        toast.success('部署任务已创建，正在通过 SSH 执行');
        const host = deploymentHost;
        setDeploymentHost(null);
        setTasksHost(host);
      } else {
        toast.error(response.msg || '创建部署任务失败');
      }
    } finally {
      setDeploymentSubmitting(false);
    }
  };

  return (
    <div className="mx-auto w-full max-w-7xl px-3 py-4 sm:px-5 lg:px-8 lg:py-6">
      <div className="mb-5 flex flex-col gap-3 sm:mb-6 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <span className="rounded-lg bg-primary-100 px-2 py-1 text-xs font-semibold text-primary-700 dark:bg-primary-100/20 dark:text-primary-300">VPS</span>
            <h1 className="text-xl font-bold text-foreground sm:text-2xl">VPS 托管</h1>
          </div>
          <p className="mt-1 text-sm text-default-500">
            {isAdmin ? '管理管理员库存与用户托管的服务器。' : '托管自己的 VPS，或使用管理员分配给你的 VPS。'}
          </p>
        </div>
        <Button color="primary" onPress={openCreate} className="min-h-10">
          + 托管 VPS
        </Button>
      </div>

      <div className="mb-5 grid grid-cols-3 gap-3 sm:mb-6 sm:max-w-xl">
        <Card className="border border-divider shadow-none"><CardBody className="p-3"><p className="text-xs text-default-500">已托管</p><p className="mt-1 text-xl font-semibold">{hosts.length}</p></CardBody></Card>
        <Card className="border border-divider shadow-none"><CardBody className="p-3"><p className="text-xs text-default-500">SSH 在线</p><p className="mt-1 text-xl font-semibold text-success">{hostSummary.online}</p></CardBody></Card>
        <Card className="border border-divider shadow-none"><CardBody className="p-3"><p className="text-xs text-default-500">已分配</p><p className="mt-1 text-xl font-semibold">{hostSummary.assigned}</p></CardBody></Card>
      </div>

      {!isAdmin && (
        <div className="mb-5 rounded-xl border border-warning-200 bg-warning-50 px-4 py-3 text-sm text-warning-800 dark:border-warning-300/20 dark:bg-warning-100/10 dark:text-warning-200">
          用户自行托管的 VPS 会同时授权给所有管理员处理故障、执行部署和使用 SSH；密码仅以加密形式保存在后端。
        </div>
      )}

      {loading ? (
        <div className="flex min-h-64 items-center justify-center"><Spinner label="正在加载 VPS…" /></div>
      ) : hosts.length === 0 ? (
        <Card className="border border-dashed border-divider shadow-none"><CardBody className="py-16 text-center"><p className="text-base font-medium">暂无 VPS 托管记录</p><p className="mt-2 text-sm text-default-500">添加服务器后，面板会自动检测 SSH 连通性，并可直接打开在线终端。</p><Button className="mt-5" color="primary" variant="flat" onPress={openCreate}>托管第一台 VPS</Button></CardBody></Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
          {hosts.map((host) => {
            const health = statusMeta(host.healthStatus);
            return (
              <Card key={host.id} className="border border-divider shadow-sm">
                <CardHeader className="flex items-start justify-between gap-3 pb-2">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="truncate text-base font-semibold text-foreground">{host.name}</h2>
                      <Chip size="sm" color={health.color} variant="flat">{health.text}</Chip>
                    </div>
                    <p className="mt-1 break-all font-mono text-xs text-default-500">{host.sshUsername}@{host.host}:{host.sshPort}</p>
                  </div>
                  <Chip size="sm" variant="bordered" color={host.origin === 'ADMIN' ? 'primary' : 'secondary'}>
                    {host.origin === 'ADMIN' ? '管理员托管' : '用户托管'}
                  </Chip>
                </CardHeader>
                <CardBody className="gap-3 pt-1">
                  <div className="grid grid-cols-1 gap-2 text-xs text-default-600 sm:grid-cols-2">
                    <div className="rounded-lg bg-default-100/70 p-2 dark:bg-default-100/10"><span className="text-default-500">归属</span><p className="mt-1 font-medium text-foreground">{host.origin === 'USER' ? `${host.ownerUserName || '用户'} 托管` : '管理员库存'}</p></div>
                    <div className="rounded-lg bg-default-100/70 p-2 dark:bg-default-100/10"><span className="text-default-500">分配</span><p className="mt-1 font-medium text-foreground">{host.assignedUserName || (host.origin === 'ADMIN' ? '尚未分配' : '托管用户可用')}</p></div>
                  </div>
                  <div className="text-xs text-default-500">
                    <p>{host.lastCheckMessage || '等待自动 SSH 检测'}</p>
                    <p className="mt-1">最近检测：{formatTime(host.lastCheckTime)}{host.lastLatencyMs !== undefined && host.lastLatencyMs !== null ? ` · ${host.lastLatencyMs} ms` : ''}</p>
                  </div>
                  {host.remark && <p className="rounded-lg border border-divider px-2 py-1.5 text-xs text-default-500">{host.remark}</p>}
                  <div className="flex flex-wrap gap-2 border-t border-divider pt-3">
                    <Button size="sm" variant="flat" color="primary" onPress={() => void checkHost(host)} isDisabled={!host.canOperate}>一键 Ping</Button>
                    <Button size="sm" variant="flat" color="secondary" onPress={() => setTerminalHost(host)} isDisabled={!host.canOperate}>在线 SSH</Button>
                    <Button size="sm" variant="flat" onPress={() => setDeploymentHost(host)} isDisabled={!host.canOperate}>一键部署</Button>
                    <Button size="sm" variant="light" onPress={() => setTasksHost(host)} isDisabled={!host.canOperate}>任务日志</Button>
                    {host.canManage && <Button size="sm" variant="light" onPress={() => openEdit(host)}>编辑</Button>}
                    {host.healthStatus === 'fingerprint_changed' && host.canManage && <Button size="sm" variant="light" color="warning" onPress={() => void resetFingerprint(host)}>重新验证指纹</Button>}
                    {host.canManage && <Button size="sm" variant="light" color="danger" onPress={() => void removeHost(host)}>移除</Button>}
                  </div>
                </CardBody>
              </Card>
            );
          })}
        </div>
      )}

      <Modal isOpen={formOpen} onOpenChange={setFormOpen} size="2xl" scrollBehavior="inside" backdrop="blur">
        <ModalContent>
          <ModalHeader>{isEditing ? '编辑 VPS 托管' : isAdmin ? '管理员托管 VPS' : '托管我的 VPS'}</ModalHeader>
          <ModalBody>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <Input label="VPS 名称" value={form.name} onValueChange={(value) => updateForm('name', value)} placeholder="例如：洛杉矶入口机" isRequired />
              <Input label="SSH 地址 / IP" value={form.host} onValueChange={(value) => updateForm('host', value)} placeholder="例如：203.0.113.10" isRequired />
              <Input label="SSH 端口" type="number" value={form.sshPort} onValueChange={(value) => updateForm('sshPort', value)} min={1} max={65535} isRequired />
              <Input label="SSH 用户名" value={form.sshUsername} onValueChange={(value) => updateForm('sshUsername', value)} placeholder="root" isRequired />
              <div className="sm:col-span-2">
                <Input label={isEditing ? 'SSH 密码（留空则不修改）' : 'SSH 密码'} type="password" autoComplete="new-password" value={form.sshPassword} onValueChange={(value) => updateForm('sshPassword', value)} description="密码以 AES-GCM 加密保存，不会返回到浏览器。" isRequired={!isEditing} />
              </div>
              <div className="sm:col-span-2"><Textarea label="备注" value={form.remark} onValueChange={(value) => updateForm('remark', value)} placeholder="可记录系统、用途或维护说明" minRows={2} /></div>
              {isAdmin && (
                <label className="sm:col-span-2">
                  <span className="mb-1 block text-sm font-medium text-foreground">分配给用户</span>
                  <select value={form.assignedUserId} onChange={(event) => updateForm('assignedUserId', event.target.value)} className="h-10 w-full rounded-lg border border-divider bg-content1 px-3 text-sm outline-none focus:border-primary">
                    <option value="">暂不分配（仅管理员可见）</option>
                    {assignableUsers.map((user) => <option key={user.id} value={user.id}>{user.user}（#{user.id}）</option>)}
                  </select>
                  <p className="mt-1 text-xs text-default-500">分配后，该用户可检测、SSH 和执行部署；管理员始终保留完整控制权。</p>
                </label>
              )}
              {!isAdmin && !isEditing && (
                <label className="sm:col-span-2 flex cursor-pointer items-start gap-2 rounded-lg border border-warning-200 bg-warning-50 p-3 text-sm dark:border-warning-300/20 dark:bg-warning-100/10">
                  <input type="checkbox" checked={form.adminAccessAcknowledged} onChange={(event) => updateForm('adminAccessAcknowledged', event.target.checked)} className="mt-1 h-4 w-4" />
                  <span>我确认：提交后，所有管理员可以维护、SSH 登录并在此 VPS 上执行受限部署任务。</span>
                </label>
              )}
            </div>
          </ModalBody>
          <ModalFooter><Button variant="light" onPress={() => setFormOpen(false)}>取消</Button><Button color="primary" isLoading={submitting} onPress={() => void submitForm}>保存托管信息</Button></ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={terminalHost !== null} onOpenChange={(open) => { if (!open) setTerminalHost(null); }} size="5xl" scrollBehavior="inside" backdrop="blur">
        <ModalContent>{terminalHost && <><ModalHeader className="flex-col items-start gap-1"><span>{terminalHost.name} · 在线 SSH</span><span className="text-xs font-normal text-default-500">{terminalHost.sshUsername}@{terminalHost.host}:{terminalHost.sshPort} · SSH 密码不会发送至浏览器</span></ModalHeader><ModalBody className="pb-5"><VpsTerminal vpsId={terminalHost.id} visible /></ModalBody></>}</ModalContent>
      </Modal>

      <Modal isOpen={deploymentHost !== null} onOpenChange={(open) => { if (!open) setDeploymentHost(null); }} size="lg" backdrop="blur">
        <ModalContent>{deploymentHost && <><ModalHeader>一键部署 · {deploymentHost.name}</ModalHeader><ModalBody><p className="text-sm text-default-600">只可执行经过审核的模板，不支持从浏览器提交任意 Shell 命令。执行日志会保存在此 VPS 的任务记录中。</p><label className="rounded-lg border border-divider p-3"><input type="radio" name="deployment-template" checked={deploymentTemplate === 'docker'} onChange={() => setDeploymentTemplate('docker')} className="mr-2" /><span className="font-medium">安装 / 检查 Docker</span><p className="mt-1 pl-5 text-xs text-default-500">若 Docker 已存在则仅检查版本；否则使用 Docker 官方安装脚本。</p></label><label className="rounded-lg border border-divider p-3"><input type="radio" name="deployment-template" checked={deploymentTemplate === 'flux_panel'} onChange={() => setDeploymentTemplate('flux_panel')} className="mr-2" /><span className="font-medium">部署 Flux Panel 后端与前端</span><p className="mt-1 pl-5 text-xs text-default-500">先确保 Docker 可用，再运行本项目 main 分支的面板安装脚本。</p></label><div className="rounded-lg bg-warning-50 p-3 text-xs text-warning-800 dark:bg-warning-100/10 dark:text-warning-200">该操作会在远程 VPS 上安装软件或创建服务。请确认目标服务器和 SSH 账号正确。</div></ModalBody><ModalFooter><Button variant="light" onPress={() => setDeploymentHost(null)}>取消</Button><Button color="primary" isLoading={deploymentSubmitting} onPress={() => void startDeployment}>确认执行</Button></ModalFooter></>}</ModalContent>
      </Modal>

      <Modal isOpen={tasksHost !== null} onOpenChange={(open) => { if (!open) setTasksHost(null); }} size="4xl" scrollBehavior="inside" backdrop="blur">
        <ModalContent>{tasksHost && <><ModalHeader className="flex items-center justify-between gap-3"><span>{tasksHost.name} · 部署任务</span><Button size="sm" variant="flat" onPress={() => void loadTasks(tasksHost)} isLoading={tasksLoading}>刷新</Button></ModalHeader><ModalBody className="pb-5">{tasksLoading && tasks.length === 0 ? <div className="py-12 text-center"><Spinner label="正在加载任务…" /></div> : tasks.length === 0 ? <div className="py-12 text-center text-sm text-default-500">暂无部署任务</div> : <div className="space-y-3">{tasks.map((task) => { const meta = taskMeta(task.taskStatus); return <div key={task.id} className="rounded-xl border border-divider"><div className="flex flex-wrap items-center justify-between gap-2 border-b border-divider px-3 py-2"><div><span className="font-medium text-sm">{task.taskType === 'docker' ? 'Docker 环境' : 'Flux Panel 部署'}</span><p className="mt-0.5 text-xs text-default-500">{task.requestedByUserName || '用户'} · {formatTime(task.createdTime)}</p></div><Chip size="sm" color={meta.color} variant="flat">{meta.text}</Chip></div><pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words bg-slate-950 p-3 text-xs leading-5 text-slate-100">{task.outputLog || '等待输出…'}</pre></div>; })}</div>}</ModalBody></>}</ModalContent>
      </Modal>
    </div>
  );
}
