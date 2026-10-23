import { Component, type ReactNode } from 'react';

/** Keep a failed route import or render from leaving an empty application. */
export class PageErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <main className="min-h-screen flex items-center justify-center bg-background px-6 text-foreground">
        <section role="alert" className="w-full max-w-md rounded-2xl border border-divider bg-content1 p-8 shadow-sm">
          <h1 className="text-xl font-semibold">页面加载失败</h1>
          <p className="mt-3 text-sm text-default-600">请检查网络连接，然后重新加载页面。</p>
          <div className="mt-6 flex flex-wrap items-center gap-4">
            <button type="button" onClick={() => window.location.reload()}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary">
              重新加载
            </button>
            <a href="/dashboard" className="text-sm text-primary underline underline-offset-4">返回概览</a>
          </div>
        </section>
      </main>
    );
  }
}
