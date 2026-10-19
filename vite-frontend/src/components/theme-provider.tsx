import React, { useEffect } from 'react';
import { useTheme } from '@heroui/use-theme';
import { applyPanelTheme, getPanelTheme, getPanelAppearance, type PanelAppearance } from '@/utils/panel-theme';

interface ThemeProviderProps {
  children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  const { setTheme } = useTheme();

  useEffect(() => {
    applyPanelTheme(getPanelTheme());

    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    let appearance = getPanelAppearance();
    const apply = () => {
      const resolved = appearance === 'system' ? (mediaQuery.matches ? 'dark' : 'light') : appearance;
      setTheme(resolved);
      document.documentElement.classList.toggle('dark', resolved === 'dark');
      document.documentElement.style.colorScheme = resolved;
    };
    const handleThemeChange = () => { if (appearance === 'system') apply(); };
    const handleAppearanceChange = (event: Event) => {
      const choice = (event as CustomEvent<PanelAppearance>).detail;
      if (choice === 'light' || choice === 'dark' || choice === 'system') appearance = choice;
      apply();
    };
    apply();
    mediaQuery.addEventListener('change', handleThemeChange);
    window.addEventListener('panel-appearance-change', handleAppearanceChange);
    return () => {
      mediaQuery.removeEventListener('change', handleThemeChange);
      window.removeEventListener('panel-appearance-change', handleAppearanceChange);
    };
  }, [setTheme]);

  return <>{children}</>;
};
