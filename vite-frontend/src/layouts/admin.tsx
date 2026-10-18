import React, { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Button } from "@heroui/button";
import { Dropdown, DropdownTrigger, DropdownMenu, DropdownItem } from "@heroui/dropdown";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import { Input } from "@heroui/input";
import { toast } from 'react-hot-toast';

import { ConsoleIcon } from '@/components/console-ui';
import { updatePassword } from '@/api';
import { safeLogout } from '@/utils/logout';
import { siteConfig } from '@/config/site';
import { getPanelTheme, panelThemes, savePanelTheme, getPanelAppearance, savePanelAppearance, type PanelTheme, type PanelAppearance } from '@/utils/panel-theme';

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
  const [appearance, setAppearance] = useState<PanelAppearance>(() => getPanelAppearance());
  const [passwordForm, setPasswordForm] = useState<PasswordForm>({
    newUsername: '',
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => {
    try { return localStorage.getItem('console-sidebar-collapsed') === 'true'; } catch { return false; }
  });
  const [commandOpen, setCommandOpen] = useState(false);
  const [commandQuery, setCommandQuery] = useState('');
  const menuButton = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    document.documentElement.dataset.console = 'xboard';
    const keyboard = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault(); setCommandOpen(open => !open);
      }
    };
    window.addEventListener('keydown', keyboard);
    return () => { delete document.documentElement.dataset.console; window.removeEventListener('keydown', keyboard); };
  }, []);

  useEffect(() => {
    if (!isMobile || !mobileMenuVisible) return;
    const menu = document.getElementById('console-side-menu');
    const focusable = () => Array.from(menu?.querySelectorAll<HTMLButtonElement>('button:not([disabled])') || []);
    const timer = window.setTimeout(() => focusable()[0]?.focus(), 50);
    const keyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setMobileMenuVisible(false); event.preventDefault(); }
      if (event.key === 'Tab') {
        const buttons = focusable(); const first = buttons[0]; const last = buttons[buttons.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
    };
    window.addEventListener('keydown', keyboard);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      window.clearTimeout(timer); window.removeEventListener('keydown', keyboard);
      document.body.style.overflow = previousOverflow; menuButton.current?.focus();
    };
  }, [isMobile, mobileMenuVisible]);

  useEffect(() => {
    setMobileMenuVisible(false);
    window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
  }, [location.pathname]);

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
      adminOnly: false
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
      adminOnly: false
    },
    {
      path: '/vps',
      label: 'VPS 托管',
      group: 'management',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M3 4a1 1 0 011-1h14a1 1 0 011 1v8a1 1 0 01-1 1h-3v2h1a1 1 0 110 2H4a1 1 0 010-2h1v-2H2a1 1 0 01-1-1V4a1 1 0 011-1h2zm2 9v2h6v-2H5zm-2-8v6h14V5H3z" clipRule="evenodd" />
        </svg>
      )
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
  const filteredMenuItems = [
    ...menuItems,
    { path: '/profile', label: '个人中心', group: 'management' as const, icon: null },
    { path: '/settings', label: '面板地址', group: 'management' as const, icon: null },
  ].filter(item => !('adminOnly' in item && item.adminOnly) || isAdmin);
  const activeMenuItem = filteredMenuItems.find((item) => item.path === location.pathname);


  const groups = [
    { label: '工作台', items: filteredMenuItems.filter(item => item.path === '/dashboard') },
    { label: '业务管理', items: filteredMenuItems.filter(item => ['/forward', '/tunnel', '/node', '/vps'].includes(item.path)) },
    { label: '系统与账户', items: filteredMenuItems.filter(item => !['/dashboard', '/forward', '/tunnel', '/node', '/vps'].includes(item.path)) },
  ];
  const commandItems = filteredMenuItems.filter(item => (item.label + item.path).toLowerCase().includes(commandQuery.toLowerCase().trim()));
  const collapsed = sidebarCollapsed && !isMobile;

  return (
    <div className={`console-shell ${collapsed ? 'is-collapsed' : ''}`}>
      {isMobile && mobileMenuVisible && <button className="console-backdrop" aria-label="关闭导航遮罩" tabIndex={-1} onClick={hideMobileMenu}/>}
      <aside id="console-side-menu" className={`console-sidebar ${mobileMenuVisible ? 'is-open' : ''}`} aria-label="主导航" role={isMobile && mobileMenuVisible ? 'dialog' : undefined} aria-modal={isMobile && mobileMenuVisible ? true : undefined}>
        <div className="console-brand">
          <div className="console-brand-mark"><ConsoleIcon name="tunnel" size={20}/></div>
          <div className="console-brand-copy min-w-0"><div className="console-brand-name">{siteConfig.name}</div><div className="console-brand-caption">RELAY CONSOLE</div></div>
          <button type="button" className="console-icon-button console-mobile-close" aria-label="关闭导航" onClick={hideMobileMenu}><ConsoleIcon name="close"/></button>
        </div>
        <nav className="console-nav">
          {groups.map(group => <React.Fragment key={group.label}>
            <p className="console-nav-section">{group.label}</p>
            {group.items.map(item => <button key={item.path} type="button" onClick={() => handleMenuClick(item.path)} className="console-nav-link" aria-current={location.pathname === item.path ? 'page' : undefined} title={collapsed ? item.label : undefined}>
              <ConsoleIcon name={item.path.slice(1)}/><span className="console-nav-label">{item.label}</span>
            </button>)}
          </React.Fragment>)}
        </nav>
        <div className="console-sidebar-footer">
          <button type="button" className="console-nav-link" onClick={() => { setIsThemePickerOpen(true); hideMobileMenu(); }} title={collapsed ? '主题切换' : undefined}><ConsoleIcon name="theme"/><span className="console-nav-label">主题切换</span></button>
          <p>v{siteConfig.version} · <a href="https://github.com/bqlpfy/flux-panel" target="_blank" rel="noopener noreferrer">Powered by flux-panel</a></p>
        </div>
      </aside>

      <div className="console-workspace">
        <header className="console-topbar">
          <div className="console-topbar-left">
            <button ref={menuButton} type="button" className="console-icon-button" aria-label={isMobile ? '打开导航' : collapsed ? '展开侧栏' : '收起侧栏'} aria-expanded={isMobile ? mobileMenuVisible : !collapsed} aria-controls="console-side-menu" onClick={() => {
              if (isMobile) toggleMobileMenu();
              else { const next = !sidebarCollapsed; setSidebarCollapsed(next); try { localStorage.setItem('console-sidebar-collapsed', String(next)); } catch { /* Keep the current session usable. */ } }
            }}><ConsoleIcon name={isMobile ? 'menu' : 'panel'} size={17}/></button>
            <span className="console-topbar-divider"/>
            <div className="console-breadcrumb"><span>控制台</span><ConsoleIcon name="chevron" size={11}/><strong>{activeMenuItem?.label || '账户'}</strong></div>
          </div>
          <div className="console-topbar-right">
            <button className="console-command-trigger" type="button" onClick={() => setCommandOpen(true)} aria-label="搜索页面"><ConsoleIcon name="search" size={14}/><span>搜索页面…</span><kbd>⌘ K</kbd></button>
            <button className="console-icon-button" type="button" aria-label="主题切换" onClick={() => setIsThemePickerOpen(true)}><ConsoleIcon name="theme" size={17}/></button>
            <Dropdown placement="bottom-end"><DropdownTrigger><Button variant="light" className="min-w-0 h-9 px-1 gap-2" aria-label="账户菜单"><span className="console-avatar">{username.slice(0, 2).toUpperCase()}</span><span className="console-account">{username}</span></Button></DropdownTrigger>
              <DropdownMenu aria-label="用户菜单">
                <DropdownItem key="profile" onPress={() => navigate('/profile')}>个人中心</DropdownItem>
                <DropdownItem key="password" onPress={onOpen}>修改密码</DropdownItem>
                <DropdownItem key="logout" className="text-danger" color="danger" onPress={handleLogout}>退出登录</DropdownItem>
              </DropdownMenu>
            </Dropdown>
          </div>
        </header>
        <main id="console-main" className="console-main" data-page={location.pathname.slice(1)}>{children}</main>
      </div>

      <nav className="console-mobile-tabs" aria-label="快捷导航">
        {[['dashboard', '概览'], ['forward', '转发'], ['tunnel', '隧道'], ['node', '节点'], ['profile', '我的']].map(([path, label]) => <button key={path} type="button" onClick={() => handleMenuClick('/' + path)} aria-current={location.pathname === '/' + path ? 'page' : undefined}><ConsoleIcon name={path} size={18}/><span>{label}</span></button>)}
      </nav>

      <Modal isOpen={commandOpen} onOpenChange={setCommandOpen} placement="top-center" size="lg">
        <ModalContent><ModalHeader>前往页面</ModalHeader><ModalBody>
          <Input autoFocus aria-label="搜索页面名称" placeholder="输入页面名称…" value={commandQuery} onValueChange={setCommandQuery} variant="bordered" startContent={<ConsoleIcon name="search" size={16}/>}/>
          <div className="console-command-list">{commandItems.map(item => <button key={item.path} type="button" onClick={() => { setCommandOpen(false); setCommandQuery(''); handleMenuClick(item.path); }}><ConsoleIcon name={item.path.slice(1)}/><span>{item.label}</span><span>前往 →</span></button>)}{commandItems.length === 0 && <p className="py-6 text-center text-default-500 text-sm">未找到页面</p>}</div>
        </ModalBody></ModalContent>
      </Modal>
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
              <div className="flex flex-wrap items-center justify-between gap-3 pb-3">
                <span className="text-sm text-default-500">显示模式</span>
                <div className="console-segment" aria-label="显示模式">
                  {([['light', '浅色'], ['dark', '深色'], ['system', '跟随系统']] as const).map(([mode, label]) => <button type="button" key={mode} aria-pressed={appearance === mode} onClick={() => { setAppearance(mode); savePanelAppearance(mode); }}>{label}</button>)}
                </div>
              </div>
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
