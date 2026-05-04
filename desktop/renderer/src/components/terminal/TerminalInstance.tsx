import { useEffect, useRef } from 'react';
import { Terminal } from 'xterm';
import { FitAddon } from 'xterm-addon-fit';
import { SearchAddon } from 'xterm-addon-search';
import { WebLinksAddon } from 'xterm-addon-web-links';
import 'xterm/css/xterm.css';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { ShellKind } from '../../store/terminalsStore';

const isWindows = typeof navigator !== 'undefined' && /Windows/i.test(navigator.userAgent);

interface TerminalInstanceProps {
  id: string;
  shellKind: ShellKind;
  visible: boolean;
  onExit?: () => void;
}

export default function TerminalInstance({ id, shellKind, visible, onExit }: TerminalInstanceProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const bridge = window.cloudide;
  const rootPath = useWorkspaceStore((s) => s.rootPath);
  const fitAddonRef = useRef<FitAddon | null>(null);
  const terminalRef = useRef<Terminal | null>(null);
  const sessionIdRef = useRef<string | null>(null);

  useEffect(() => {
    if (!bridge?.pty || !containerRef.current) return;

    const container = containerRef.current;
    const terminal = new Terminal({
      theme: {
        background: '#1e1e1e',
        foreground: '#cccccc',
        cursor: '#aeafad',
        cursorAccent: '#1e1e1e',
        selectionBackground: '#264f78',
        black: '#000000',
        red: '#cd3131',
        green: '#0dbc79',
        yellow: '#e5e510',
        blue: '#2472c8',
        magenta: '#bc3fbc',
        cyan: '#11a8cd',
        white: '#e5e5e5',
        brightBlack: '#666666',
        brightRed: '#f14c4c',
        brightGreen: '#23d18b',
        brightYellow: '#f5f543',
        brightBlue: '#3b8eea',
        brightMagenta: '#d670d6',
        brightCyan: '#29b8db',
        brightWhite: '#e5e5e5',
      },
      fontFamily: "'Cascadia Code', 'JetBrains Mono', 'Consolas', 'Courier New', monospace",
      fontSize: 13,
      lineHeight: 1.2,
      cursorStyle: 'bar',
      cursorBlink: true,
      scrollback: 10000,
      allowProposedApi: true,
      windowsMode: isWindows,
    });

    const fitAddon = new FitAddon();
    terminal.loadAddon(fitAddon);
    terminal.loadAddon(new SearchAddon());
    terminal.loadAddon(new WebLinksAddon());
    terminal.open(container);

    terminalRef.current = terminal;
    fitAddonRef.current = fitAddon;

    const fitSafely = () => {
      try {
        if (
          container.clientWidth > 0 &&
          container.clientHeight > 0 &&
          container.querySelector('.xterm-screen')
        ) {
          fitAddon.fit();
          return true;
        }
      } catch {
        /* renderer not ready */
      }
      return false;
    };

    let attempts = 0;
    const ensureFit = () => {
      if (fitSafely()) return;
      if (attempts++ < 30) requestAnimationFrame(ensureFit);
    };
    ensureFit();

    let disposed = false;
    let pendingData: string[] = [];

    const removeData = bridge.pty.onData((payload) => {
      if (disposed) return;
      if (sessionIdRef.current === null) {
        pendingData.push(payload.data);
        return;
      }
      if (payload.sessionId === sessionIdRef.current) {
        terminal.write(payload.data);
      }
    });

    const removeExit = bridge.pty.onExit?.((payload) => {
      if (disposed) return;
      if (payload.sessionId === sessionIdRef.current) {
        terminal.write(`\r\n\x1b[90m[process exited with code ${payload.exitCode}]\x1b[0m\r\n`);
        onExit?.();
      }
    });

    requestAnimationFrame(() => {
      const cols = terminal.cols || 120;
      const rows = terminal.rows || 30;
      bridge.pty
        .create(shellKind, { cols, rows, cwd: rootPath ?? undefined })
        .then((session) => {
          if (disposed) {
            if (session && 'sessionId' in session) {
              void bridge.pty.kill(session.sessionId);
            }
            return;
          }
          if (!session || 'error' in session) {
            const message = session && 'error' in session ? session.error : 'unknown error';
            terminal.writeln(`\x1b[31m[terminal] failed to start ${shellKind}: ${message}\x1b[0m`);
            return;
          }
          sessionIdRef.current = session.sessionId;
          for (const chunk of pendingData) terminal.write(chunk);
          pendingData = [];
          if (terminal.cols && terminal.rows) {
            void bridge.pty.resize(session.sessionId, terminal.cols, terminal.rows);
          }
        })
        .catch((error) => {
          if (disposed) return;
          terminal.writeln(
            `\x1b[31m[terminal] startup failed: ${error instanceof Error ? error.message : String(error)}\x1b[0m`
          );
        });
    });

    terminal.onData((data) => {
      const sid = sessionIdRef.current;
      if (sid) void bridge.pty.write(sid, data);
    });

    let resizeFrame = 0;
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(resizeFrame);
      resizeFrame = requestAnimationFrame(() => {
        fitSafely();
        const sid = sessionIdRef.current;
        if (sid && terminal.cols && terminal.rows) {
          void bridge.pty.resize(sid, terminal.cols, terminal.rows);
        }
      });
    });
    observer.observe(container);

    return () => {
      disposed = true;
      cancelAnimationFrame(resizeFrame);
      observer.disconnect();
      removeData();
      removeExit?.();
      const sid = sessionIdRef.current;
      if (sid) void bridge.pty.kill(sid);
      terminal.dispose();
      terminalRef.current = null;
      fitAddonRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // When the tab becomes visible we may need to re-fit because the container had 0 size when hidden.
  useEffect(() => {
    if (!visible) return;
    const container = containerRef.current;
    const terminal = terminalRef.current;
    const fitAddon = fitAddonRef.current;
    if (!container || !terminal || !fitAddon) return;
    requestAnimationFrame(() => {
      try {
        if (container.clientWidth > 0 && container.clientHeight > 0) {
          fitAddon.fit();
          terminal.focus();
          const sid = sessionIdRef.current;
          if (sid && terminal.cols && terminal.rows) {
            void bridge?.pty?.resize(sid, terminal.cols, terminal.rows);
          }
        }
      } catch {
        /* ignore */
      }
    });
  }, [visible, bridge]);

  return (
    <div
      className="terminal-instance"
      style={{ display: visible ? 'block' : 'none' }}
      ref={containerRef}
    />
  );
}
