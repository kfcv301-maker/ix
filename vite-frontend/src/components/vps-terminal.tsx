import { useEffect, useRef, useState } from 'react';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import '@xterm/xterm/css/xterm.css';

import { createVpsTerminalTicket } from '@/api';
import { getVpsTerminalSocketUrl } from '@/utils/realtime-socket';

interface VpsTerminalProps {
  vpsId: number;
  visible: boolean;
}

/** A browser terminal whose SSH connection exists exclusively in the backend. */
export function VpsTerminal({ vpsId, visible }: VpsTerminalProps) {
  const mountRef = useRef<HTMLDivElement | null>(null);
  const [connectionStatus, setConnectionStatus] = useState('正在建立 SSH 连接…');

  useEffect(() => {
    if (!visible || !mountRef.current) return;

    const terminal = new Terminal({
      cursorBlink: true,
      convertEol: true,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
      fontSize: 13,
      lineHeight: 1.2,
      scrollback: 5000,
      theme: {
        background: '#0b1020',
        foreground: '#e5eefc',
        cursor: '#a7f3d0',
        selectionBackground: '#334155',
        black: '#1e293b',
        brightBlack: '#64748b',
        red: '#f87171',
        green: '#4ade80',
        yellow: '#facc15',
        blue: '#60a5fa',
        magenta: '#c084fc',
        cyan: '#22d3ee',
        white: '#e2e8f0',
      },
    });
    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.open(mountRef.current);

    let socket: WebSocket | null = null;
    let disposed = false;
    const fit = () => {
      try {
        fitAddon.fit();
        const dimensions = fitAddon.proposeDimensions();
        if (dimensions && socket?.readyState === WebSocket.OPEN) {
          socket.send(JSON.stringify({ type: 'resize', cols: dimensions.cols, rows: dimensions.rows }));
        }
      } catch {
        // A modal may still be mid-transition; the next resize will fit it.
      }
    };
    const fitTimer = window.setTimeout(fit, 80);
    const resizeObserver = new ResizeObserver(fit);
    resizeObserver.observe(mountRef.current);

    const disposable = terminal.onData((data) => {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'input', data }));
      }
    });

    const openTerminal = async () => {
      terminal.writeln('\x1b[36m正在请求一次性 SSH 会话…\x1b[0m');
      const response = await createVpsTerminalTicket(vpsId);
      if (disposed) return;
      if (response.code !== 0 || !response.data?.terminalTicket) {
        const message = response.msg || '无法创建 SSH 会话';
        setConnectionStatus(message);
        terminal.writeln(`\r\n\x1b[31m${message}\x1b[0m\r\n`);
        return;
      }

      socket = new WebSocket(getVpsTerminalSocketUrl(response.data.terminalTicket));
      socket.onopen = () => {
        setConnectionStatus('已连接，等待远程 Shell 就绪…');
        fit();
      };
      socket.onmessage = (event) => {
        try {
          const message = JSON.parse(event.data);
          if (message.type === 'output') {
            terminal.write(String(message.data ?? ''));
          } else if (message.type === 'ready') {
            setConnectionStatus(String(message.data || 'SSH 已连接'));
            terminal.writeln(`\r\n\x1b[32m${String(message.data || 'SSH 已连接')}\x1b[0m\r\n`);
            terminal.focus();
          } else if (message.type === 'error') {
            setConnectionStatus(String(message.data || 'SSH 连接失败'));
            terminal.writeln(`\r\n\x1b[31m${String(message.data || 'SSH 连接失败')}\x1b[0m\r\n`);
          }
        } catch {
          terminal.write(String(event.data));
        }
      };
      socket.onerror = () => {
        setConnectionStatus('终端网络连接失败');
        terminal.writeln('\r\n\x1b[31m终端网络连接失败。\x1b[0m');
      };
      socket.onclose = () => {
        setConnectionStatus('终端已断开');
        terminal.writeln('\r\n\x1b[33mSSH 终端已断开。\x1b[0m');
      };
    };
    void openTerminal();

    return () => {
      disposed = true;
      window.clearTimeout(fitTimer);
      resizeObserver.disconnect();
      disposable.dispose();
      if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) socket.close();
      terminal.dispose();
    };
  }, [visible, vpsId]);

  return (
    <div className="overflow-hidden rounded-xl border border-slate-700 bg-slate-950 shadow-inner">
      <div className="flex items-center gap-2 border-b border-slate-700 bg-slate-900 px-3 py-2 text-xs text-slate-300">
        <span className="inline-block h-2 w-2 rounded-full bg-emerald-400" />
        <span className="truncate">{connectionStatus}</span>
      </div>
      <div ref={mountRef} className="h-[58vh] min-h-[340px] w-full p-2" />
    </div>
  );
}
