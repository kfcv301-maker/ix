import { ConsoleHeading, ConsoleIcon } from '@/components/console-ui';
import React, { useState, useEffect } from 'react';
import { Button } from "@heroui/button";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import { Input } from "@heroui/input";
import { toast } from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';
import { isWebViewFunc } from '@/utils/panel';
import { siteConfig } from '@/config/site';
import { updatePassword } from '@/api';
import { safeLogout } from '@/utils/logout';
interface PasswordForm {
  newUsername: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}


export default function ProfilePage() {
  const navigate = useNavigate();
  const { isOpen, onOpen, onOpenChange } = useDisclosure();
  const [username, setUsername] = useState('');
  const [isAdmin, setIsAdmin] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [passwordForm, setPasswordForm] = useState<PasswordForm>({
    newUsername: '',
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  useEffect(() => {
    // 获取用户信息
    const name = localStorage.getItem('name') || 'Admin';
    
    // 兼容处理：如果没有admin字段，根据role_id判断（0为管理员）
    let adminFlag = localStorage.getItem('admin') === 'true';
    if (localStorage.getItem('admin') === null) {
      const roleId = parseInt(localStorage.getItem('role_id') || '1', 10);
      adminFlag = roleId === 0;
      // 补充设置admin字段，避免下次再次判断
      localStorage.setItem('admin', adminFlag.toString());
    }
    
    setUsername(name);
    setIsAdmin(adminFlag);
  }, []);

  const adminMenuItems = [
    { path: '/user', label: '用户管理', icon: 'user', description: '管理用户账户与权限' },
    { path: '/limit', label: '限速管理', icon: 'limit', description: '设置用户限速策略' },
    { path: '/config', label: '网站配置', icon: 'config', description: '管理站点信息与系统设置' },
  ];

  // 退出登录
  const handleLogout = () => {
    safeLogout();
    navigate('/', { replace: true });
  };

  // 密码表单验证
  const validatePasswordForm = (): boolean => {
    if (!passwordForm.newUsername.trim()) {
      toast.error('请输入新用户名');
      return false;
    }
    if (passwordForm.newUsername.length < 3) {
      toast.error('用户名长度至少3位');
      return false;
    }
    if (!passwordForm.currentPassword) {
      toast.error('请输入当前密码');
      return false;
    }
    if (!passwordForm.newPassword) {
      toast.error('请输入新密码');
      return false;
    }
    if (passwordForm.newPassword.length < 6) {
      toast.error('新密码长度不能少于6位');
      return false;
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error('两次输入密码不一致');
      return false;
    }
    return true;
  };

  // 提交密码修改
  const handlePasswordSubmit = async () => {
    if (!validatePasswordForm()) return;

    setPasswordLoading(true);
    try {
      const response = await updatePassword(passwordForm);
      if (response.code === 0) {
        toast.success('密码修改成功，请重新登录');
        onOpenChange();
        handleLogout();
      } else {
        toast.error(response.msg || '密码修改失败');
      }
    } catch (error) {
      toast.error('修改密码时发生错误');
      console.error('修改密码错误:', error);
    } finally {
      setPasswordLoading(false);
    }
  };

  // 重置密码表单
  const resetPasswordForm = () => {
    setPasswordForm({
      newUsername: '',
      currentPassword: '',
      newPassword: '',
      confirmPassword: ''
    });
  };

  return (
    <div className="profile-page">
      <ConsoleHeading title="个人中心" description="管理账户信息与登录安全，快速访问常用设置。"/>
      <div className="profile-layout">
        <section className="profile-account" aria-label="账户信息">
          <div className="profile-identity">
            <div className="profile-avatar"><ConsoleIcon name="profile" size={26}/></div>
            <div className="profile-identity-copy"><h2 title={username}>{username}</h2><span className="profile-role">{isAdmin ? '管理员' : '普通用户'}</span></div>
          </div>
          <dl className="profile-facts">
            <div><dt>账户权限</dt><dd>{isAdmin ? '系统管理' : '个人账户'}</dd></div>
            <div><dt>登录状态</dt><dd><span className="console-status-dot status-success"/>已登录</dd></div>
            <div><dt>当前版本</dt><dd>v{isWebViewFunc() ? siteConfig.app_version : siteConfig.version}</dd></div>
          </dl>
          <Button variant="bordered" onPress={handleLogout} startContent={<ConsoleIcon name="logout" size={17}/>} className="profile-logout">退出登录</Button>
        </section>
        <div className="profile-settings">
          <section className="profile-section" aria-labelledby="profile-security-title">
            <div className="profile-section-heading"><h2 id="profile-security-title">账户与安全</h2><p>维护登录信息与面板访问地址</p></div>
            <button className="profile-setting-row" onClick={onOpen}>
              <span className="profile-setting-icon"><ConsoleIcon name="lock"/></span>
              <span className="profile-setting-copy"><strong>修改密码</strong><span>更新用户名与登录密码</span></span><ConsoleIcon name="chevron" size={16}/>
            </button>
            <button className="profile-setting-row" onClick={() => navigate('/settings')}>
              <span className="profile-setting-icon"><ConsoleIcon name="globe"/></span>
              <span className="profile-setting-copy"><strong>面板地址</strong><span>查看和切换面板连接地址</span></span><ConsoleIcon name="chevron" size={16}/>
            </button>
          </section>
          {isAdmin && <section className="profile-section" aria-labelledby="profile-management-title">
            <div className="profile-section-heading"><h2 id="profile-management-title">管理入口</h2><p>账户权限与系统配置</p></div>
            {adminMenuItems.map(item => <button key={item.path} className="profile-setting-row" onClick={() => navigate(item.path)}>
              <span className="profile-setting-icon"><ConsoleIcon name={item.icon}/></span>
              <span className="profile-setting-copy"><strong>{item.label}</strong><span>{item.description}</span></span><ConsoleIcon name="chevron" size={16}/>
            </button>)}
          </section>}
        </div>
      </div>
      <footer className="profile-footer">Powered by <a href="https://github.com/bqlpfy/flux-panel" target="_blank" rel="noopener noreferrer">flux-panel</a></footer>

      {/* 修改密码弹窗 */}
      <Modal 
        isOpen={isOpen} 
        onOpenChange={() => {
          onOpenChange();
          resetPasswordForm();
        }}
        size="lg"
      scrollBehavior="outside"
      backdrop="blur"
      placement="center"
      >
        <ModalContent>
          {(onClose: () => void) => (
            <>
              <ModalHeader className="flex flex-col gap-1">修改密码</ModalHeader>
              <ModalBody>
                <div className="space-y-4">
                  <Input
                    label="新用户名"
                    placeholder="请输入新用户名（至少3位）"
                    value={passwordForm.newUsername}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newUsername: e.target.value }))}
                    variant="bordered"
                  />
                  <Input
                    label="当前密码"
                    type="password"
                    placeholder="请输入当前密码"
                    value={passwordForm.currentPassword}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, currentPassword: e.target.value }))}
                    variant="bordered"
                  />
                  <Input
                    label="新密码"
                    type="password"
                    placeholder="请输入新密码（至少6位）"
                    value={passwordForm.newPassword}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newPassword: e.target.value }))}
                    variant="bordered"
                  />
                  <Input
                    label="确认密码"
                    type="password"
                    placeholder="请再次输入新密码"
                    value={passwordForm.confirmPassword}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, confirmPassword: e.target.value }))}
                    variant="bordered"
                  />
                </div>
              </ModalBody>
              <ModalFooter>
                <Button color="default" variant="light" onPress={onClose}>
                  取消
                </Button>
                <Button 
                  color="primary" 
                  onPress={handlePasswordSubmit}
                  isLoading={passwordLoading}
                >
                  确定
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}
