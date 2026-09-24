export const PANEL_THEME_STORAGE_KEY = 'flux-panel-theme';

export const panelThemes = [
  { id: 'original', name: '原版主题', description: '保持当前界面与系统深浅色行为' },
  { id: 'dora-sky', name: '晴空蓝', description: '清爽蓝色、圆润卡片与轻量二次元感' },
  { id: 'akcdn-console', name: '运营蓝调', description: '浅色蓝调、运营控制台层级与高密度数据卡片' },
  { id: 'nvenix-minimal', name: '极简', description: '纸白底色、墨色主按钮、细边框与克制层次' },
  { id: 'neon-console', name: '霓虹控制台', description: '深色网格、高信息密度的运维风格' },
  { id: 'sakura-glass', name: '樱花玻璃', description: '柔和粉色、半透明玻璃质感' },
] as const;

export type PanelTheme = (typeof panelThemes)[number]['id'];

export function getPanelTheme(): PanelTheme {
  const savedTheme = localStorage.getItem(PANEL_THEME_STORAGE_KEY);
  return panelThemes.some((theme) => theme.id === savedTheme)
    ? (savedTheme as PanelTheme)
    : 'original';
}

export function applyPanelTheme(theme: PanelTheme) {
  const root = document.documentElement;
  if (theme === 'original') {
    root.removeAttribute('data-panel-theme');
  } else {
    root.setAttribute('data-panel-theme', theme);
  }
}

export function savePanelTheme(theme: PanelTheme) {
  localStorage.setItem(PANEL_THEME_STORAGE_KEY, theme);
  applyPanelTheme(theme);
}
