import React from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Button } from "@heroui/button";

import { Logo } from '@/components/icons';
import { siteConfig } from '@/config/site';

export default function H5SimpleLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  
  // 路由切换时回到顶部，避免上一页滚动位置保留
  React.useEffect(() => {
    try {
      window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
    } catch (e) {
      window.scrollTo(0, 0);
    }
    document.body.scrollTop = 0;
    document.documentElement.scrollTop = 0;
  }, [location.pathname]);

  const handleBack = () => {
    navigate('/profile');
  };

  return (
    <div className="flex min-h-[100dvh] flex-col bg-gray-100 dark:bg-black">
      {/* 顶部导航栏 */}
      <header className="safe-top relative z-10 flex min-h-14 flex-shrink-0 items-center justify-between border-b border-gray-200 bg-white px-4 shadow-sm dark:border-gray-600 dark:bg-black">
        <div className="flex items-center gap-2">
          <Button
            isIconOnly
            variant="light"
            size="sm"
            onPress={handleBack}
          >
            <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
              <path fillRule="evenodd" d="M12.707 5.293a1 1 0 010 1.414L9.414 10l3.293 3.293a1 1 0 01-1.414 1.414l-4-4a1 1 0 010-1.414l4-4a1 1 0 011.414 0z" clipRule="evenodd" />
            </svg>
          </Button>
          <Logo size={20} />
          <h1 className="text-sm font-bold text-foreground">{siteConfig.name}</h1>
        </div>

        <div className="flex items-center gap-2">
        </div>
      </header>

      {/* 主内容区域 */}
      <main className="min-w-0 flex-1 bg-gray-100 pb-0 dark:bg-black">
        {children}
      </main>
    </div>
  );
}
