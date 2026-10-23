import { PANEL_THEME_STORAGE_KEY, PANEL_APPEARANCE_STORAGE_KEY } from './panel-theme';

/**
 * 安全退出登录函数
 * 清除登录相关数据，但保留用户偏好设置（如主题）
 */
export const safeLogout = () => {
  const preferences = [PANEL_THEME_STORAGE_KEY, PANEL_APPEARANCE_STORAGE_KEY, 'console-sidebar-collapsed']
    .map(key => [key, localStorage.getItem(key)] as const);
  localStorage.clear();
  for (const [key, value] of preferences) {
    if (value !== null) localStorage.setItem(key, value);
  }
};
