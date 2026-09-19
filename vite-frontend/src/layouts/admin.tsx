import React, { useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from "@heroui/dropdown";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import { Input } from "@heroui/input";
import { toast } from 'react-hot-toast';

import { Logo } from '@/components/icons';
import { updatePassword } from '@/api';
import { safeLogout } from '@/utils/logout';
import { siteConfig } from '@/config/site';
import { getPanelTheme, panelThemes, savePanelTheme, type PanelTheme } from '@/utils/panel-theme';

interface MenuItem {
  path: string;
  label: string;
  icon: React.ReactNode;
  adminOnly?: boolean;
  group: 'forwarding' | 'management';
}

interface PasswordForm {
  newUsername: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export default function AdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const { isOpen, onOpen, onOpenChange } = useDisclosure();

  const [isMobile, setIsMobile] = useState(false);
  const [mobileMenuVisible, setMobileMenuVisible] = useState(false);
  const [username, setUsername] = useState('');
  const [isAdmin, setIsAdmin] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [isThemePickerOpen, setIsThemePickerOpen] = useState(false);
  const [panelTheme, setPanelTheme] = useState<PanelTheme>(() => getPanelTheme());
  const [passwordForm, setPasswordForm] = useState<PasswordForm>({
    newUsername: '',
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  // 菜单项配置
  const menuItems: MenuItem[] = [
    {
      path: '/dashboard',
      label: '仪表板',
      group: 'forwarding',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M3 4a1 1 0 011-1h12a1 1 0 011 1v2a1 1 0 01-1 1H4a1 1 0 01-1-1V4zM3 10a1 1 0 011-1h6a1 1 0 011 1v6a1 1 0 01-1 1H4a1 1 0 01-1-1v-6zM14 9a1 1 0 00-1 1v6a1 1 0 001 1h2a1 1 0 001-1v-6a1 1 0 00-1-1h-2z" />
        </svg>
      )
    },
    {
      path: '/forward',
      label: '转发管理',
      group: 'forwarding',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 17a1 1 0 011-1h12a1 1 0 110 2H4a1 1 0 01-1-1zm3.293-7.707a1 1 0 011.414 0L9 10.586V3a1 1 0 112 0v7.586l1.293-1.293a1 1 0 111.414 1.414l-3 3a1 1 0 01-1.414 0l-3-3a1 1 0 010-1.414z" clipRule="evenodd" />
        </svg>
      )
    },
    {
      path: '/tunnel',
      label: '隧道管理',
      group: 'forwarding',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M12.586 4.586a2 2 0 112.828 2.828l-3 3a2 2 0 01-2.828 0 1 1 0 00-1.414 1.414 4 4 0 005.656 0l3-3a4 4 0 00-5.656-5.656l-1.5 1.5a1 1 0 101.414 1.414l1.5-1.5zm-5 5a2 2 0 012.828 0 1 1 0 101.414-1.414 4 4 0 00-5.656 0l-3 3a4 4 0 105.656 5.656l1.5-1.5a1 1 0 10-1.414-1.414l-1.5 1.5a2 2 0 11-2.828-2.828l3-3z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/node',
      label: '节点监控',
      group: 'forwarding',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 3a1 1 0 000 2v8a2 2 0 002 2h2.586l-1.293 1.293a1 1 0 101.414 1.414L10 15.414l2.293 2.293a1 1 0 001.414-1.414L12.414 15H15a2 2 0 002-2V5a1 1 0 100-2H3zm11.707 4.707a1 1 0 00-1.414-1.414L10 9.586 8.707 8.293a1 1 0 00-1.414 0l-2 2a1 1 0 101.414 1.414L8 10.414l1.293 1.293a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/limit',
      label: '限速管理',
      group: 'management',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/user',
      label: '用户管理',
      group: 'management',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zM17 6a3 3 0 11-6 0 3 3 0 016 0zM12.93 17c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z" />
        </svg>
      ),
      adminOnly: true
    },
    {
      path: '/config',
      label: '网站配置',
      group: 'management',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clipRule="evenodd" />
        </svg>
      ),
      adminOnly: true
    }
  ];

  // 检查移动端
  const checkMobile = () => {
    setIsMobile(window.innerWidth <= 768);
    if (window.innerWidth > 768) {
      setMobileMenuVisible(false);
    }
  };

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

    // 响应式检查
    checkMobile();
    window.addEventListener('resize', checkMobile);

    return () => {
      window.removeEventListener('resize', checkMobile);
    };
  }, []);

  // 退出登录
  const handleLogout = () => {
    safeLogout();
    navigate('/');
  };

  // 切换移动端菜单
  const toggleMobileMenu = () => {
    setMobileMenuVisible(!mobileMenuVisible);
  };

  // 隐藏移动端菜单
  const hideMobileMenu = () => {
    setMobileMenuVisible(false);
  };

  // 菜单点击处理
  const handleMenuClick = (path: string) => {
    navigate(path);
    if (isMobile) {
      hideMobileMenu();
    }
  };

  const handleThemeSelect = (theme: PanelTheme) => {
    savePanelTheme(theme);
    setPanelTheme(theme);
    setIsThemePickerOpen(false);
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

  // 过滤菜单项（根据权限）
  const filteredMenuItems = menuItems.filter(item => 
    !item.adminOnly || isAdmin
  );
  const activeMenuItem = filteredMenuItems.find((item) => item.path === location.pathname);

  return (
          <div className={`panel-shell flex ${isMobile ? 'min-h-screen' : 'h-screen'} bg-gray-100 dark:bg-black`}>
      {/* 移动端遮罩层 */}
      {isMobile && mobileMenuVisible && (
        <div 
          className="fixed inset-0 backdrop-blur-sm bg-white/50 dark:bg-black/30 z-40"
          onClick={hideMobileMenu}
        />
      )}

      {/* 左侧菜单栏 */}
      <aside className={`panel-sidebar
        ${isMobile ? 'fixed' : 'relative'} 
        ${isMobile && !mobileMenuVisible ? '-translate-x-full' : 'translate-x-0'}
        ${isMobile ? 'w-64' : 'w-72'} 
        bg-white dark:bg-black 
        shadow-lg 
        border-r border-gray-200 dark:border-gray-600
        z-50 
        transition-transform duration-300 ease-in-out
        flex flex-col
        ${isMobile ? 'h-screen' : 'h-full'}
        ${isMobile ? 'top-0 left-0' : ''}
      `}>
                 {/* Logo 区域 */}
         <div className="px-3 py-3 h-14 flex items-center">
            <div className="panel-brand flex items-center gap-2 w-full">
             <Logo size={24} />
              <div className="flex-1 min-w-0">
                <h1 className="text-sm font-bold text-foreground overflow-hidden whitespace-nowrap">{siteConfig.name}</h1>
                <p className="text-xs text-default-500">v{siteConfig.version}</p>
                {panelTheme === 'akcdn-console' && <p className="akcdn-brand-subtitle">转发管理控制台</p>}
              </div>
           </div>
         </div>

                 {/* 菜单导航 */}
         <nav className="panel-navigation flex-1 px-4 py-6 overflow-y-auto">
           <ul className="space-y-1">
             {filteredMenuItems.map((item, index) => {
               const isActive = location.pathname === item.path;
               return (
                 <React.Fragment key={item.path}>
                 {panelTheme === 'akcdn-console' && (index === 0 || filteredMenuItems[index - 1].group !== item.group) && (
                   <li className="akcdn-nav-group-title">{item.group === 'forwarding' ? '转发' : '管理'}</li>
                 )}
                 <li>
                                     <button
                     onClick={() => handleMenuClick(item.path)}
                     className={`panel-nav-item
                       w-full flex items-center gap-3 px-4 py-3 rounded-lg text-left
                       transition-colors duration-200 min-h-[44px]
                       ${isActive 
                         ? 'bg-primary-100 dark:bg-primary-600/20 text-primary-600 dark:text-primary-300' 
                         : 'text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-900'
                       }
                     `}
                   >
                     <div className="flex-shrink-0">
                       {item.icon}
                     </div>
                     <span className="font-medium text-sm">{item.label}</span>
                  </button>
                 </li>
                 </React.Fragment>
               );
             })}
            <li>
              <button
                type="button"
                onClick={() => setIsThemePickerOpen(true)}
                className="panel-nav-item w-full flex items-center gap-3 px-4 py-3 rounded-lg text-left transition-colors duration-200 min-h-[44px] text-gray-700 dark:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-900"
              >
                <svg className="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth="1.8" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" d="M12 3a9 9 0 1 0 9 9c0-1.2-.24-2.34-.68-3.38a.75.75 0 0 0-.86-.43 3.2 3.2 0 0 1-3.83-3.83.75.75 0 0 0-.43-.86A8.96 8.96 0 0 0 12 3Z" />
                  <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 10.5h.01M9 6.75h.01M14.5 7.5h.01M6.75 14.25h.01" />
                </svg>
                <span className="font-medium text-sm flex-1">主题切换</span>
                <span className="text-[10px] text-default-400 truncate max-w-16">{panelThemes.find((theme) => theme.id === panelTheme)?.name}</span>
              </button>
            </li>
          </ul>
        </nav>

                {/* 底部版权信息 */}
        <div className="px-4 py-2 pb-4 mt-auto flex-shrink-0">
          <div className="text-center">
            <p className="text-xs text-gray-400 dark:text-gray-500">
              Powered by{' '}
              <a 
                href="https://github.com/bqlpfy/flux-panel" 
                target="_blank" 
                rel="noopener noreferrer"
                className="text-gray-500 dark:text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
              >
                flux-panel
              </a>
            </p>
          </div>
        </div>
      </aside>

      {/* 主内容区域 */}
      <div className={`flex flex-col flex-1 ${isMobile ? 'min-h-0' : 'h-full overflow-hidden'}`}>
                 {/* 顶部导航栏 */}
          <header className="panel-header bg-white dark:bg-black shadow-md border-b border-gray-200 dark:border-gray-600 h-14 flex items-center justify-between px-4 lg:px-6 relative z-10">
            <div className="flex items-center gap-4">
              {!isMobile && panelTheme === 'akcdn-console' && (
                <div className="akcdn-page-heading">
                  <span>控制台</span>
                  <h2>{activeMenuItem?.label || '概览'}</h2>
                </div>
              )}
            {/* 移动端菜单按钮 */}
            {isMobile && (
              <Button
                isIconOnly
                variant="light"
                onPress={toggleMobileMenu}
                className="lg:hidden"
              >
                <svg className="w-6 h-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
                </svg>
              </Button>
            )}
          </div>

          <div className="flex items-center gap-3">
            {/* 用户菜单 */}
             <Dropdown placement="bottom-end">
               <DropdownTrigger>
                 <Button variant="light" className="text-sm font-medium text-foreground">
                   {username}
                   <svg className="w-4 h-4 ml-1" fill="currentColor" viewBox="0 0 20 20">
                     <path fillRule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clipRule="evenodd" />
                   </svg>
                 </Button>
               </DropdownTrigger>
              <DropdownMenu aria-label="用户菜单">
                <DropdownItem
                  key="change-password"
                  startContent={
                    <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v2H2v-4l4.257-4.257A6 6 0 1118 8zm-6-4a1 1 0 100 2 2 2 0 012 2 1 1 0 102 0 4 4 0 00-4-4z" clipRule="evenodd" />
                    </svg>
                  }
                  onPress={onOpen}
                >
                  修改密码
                </DropdownItem>
                <DropdownItem
                  key="logout"
                  startContent={
                    <svg className="w-4 h-4" fill="currentColor" viewBox="0 0 20 20">
                      <path fillRule="evenodd" d="M3 3a1 1 0 00-1 1v12a1 1 0 102 0V4a1 1 0 00-1-1zm10.293 9.293a1 1 0 001.414 1.414l3-3a1 1 0 000-1.414l-3-3a1 1 0 10-1.414 1.414L14.586 9H7a1 1 0 100 2h7.586l-1.293 1.293z" clipRule="evenodd" />
                    </svg>
                  }
                  className="text-danger"
                  color="danger"
                  onPress={handleLogout}
                >
                  退出登录
                </DropdownItem>
              </DropdownMenu>
            </Dropdown>
          </div>
        </header>

        {/* 主内容 */}
        <main className={`panel-main flex-1 bg-gray-100 dark:bg-black ${isMobile ? '' : 'overflow-y-auto'}`}>
          {children}
        </main>
      </div>

      {/* 修改密码弹窗 */}
      <Modal 
        isOpen={isOpen} 
        onOpenChange={() => {
          onOpenChange();
          resetPasswordForm();
        }}
        size="2xl"
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

      <Modal
        isOpen={isThemePickerOpen}
        onOpenChange={setIsThemePickerOpen}
        size="2xl"
        placement="center"
        backdrop="blur"
      >
        <ModalContent>
          <>
            <ModalHeader className="flex flex-col gap-1">主题切换</ModalHeader>
            <ModalBody>
              <p className="text-sm text-default-500">只改变界面外观，不会修改节点、转发、账户或任何面板数据。</p>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 pb-2">
                {panelThemes.map((theme) => (
                  <button
                    key={theme.id}
                    type="button"
                    onClick={() => handleThemeSelect(theme.id)}
                    className={`panel-theme-choice panel-theme-choice-${theme.id} ${panelTheme === theme.id ? 'is-selected' : ''}`}
                    aria-pressed={panelTheme === theme.id}
                  >
                    <span className="panel-theme-swatch" aria-hidden="true"><i /><i /><i /></span>
                    <span className="text-left flex-1">
                      <span className="block font-semibold text-sm">{theme.name}</span>
                      <span className="block text-xs text-default-500 mt-1 leading-5">{theme.description}</span>
                    </span>
                    {panelTheme === theme.id && <span className="text-primary text-xs font-semibold">当前</span>}
                  </button>
                ))}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button variant="light" onPress={() => setIsThemePickerOpen(false)}>关闭</Button>
            </ModalFooter>
          </>
        </ModalContent>
      </Modal>
    </div>
  );
}
