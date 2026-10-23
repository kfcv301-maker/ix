import React, { useLayoutEffect } from 'react';
import { applyPanelTheme, subscribePanelPreferences, resolvePanelAppearance, type PanelAppearance } from '@/utils/panel-theme';

interface ThemeProviderProps {
  children: React.ReactNode;
}

export const ThemeProvider: React.FC<ThemeProviderProps> = ({ children }) => {
  useLayoutEffect(() => {
    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    let appearance: PanelAppearance = 'light';
    const apply = () => {
      const resolved = resolvePanelAppearance(appearance, mediaQuery.matches);
      const root = document.documentElement;
      root.classList.remove('light', 'dark', 'system');
      root.classList.add(resolved);
      root.style.colorScheme = resolved;
    };
    const handleThemeChange = () => { if (appearance === 'system') apply(); };
    const unsubscribe = subscribePanelPreferences(preferences => {
      applyPanelTheme(preferences.theme);
      appearance = preferences.appearance;
      apply();
    });
    mediaQuery.addEventListener('change', handleThemeChange);
    return () => {
      mediaQuery.removeEventListener('change', handleThemeChange);
      unsubscribe();
    };
  }, []);

  return <>{children}</>;
};
