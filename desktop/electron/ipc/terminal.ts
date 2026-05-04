import { BrowserWindow, ipcMain } from 'electron';
import os from 'os';
import * as pty from 'node-pty';
import { IPty } from 'node-pty';
import { randomUUID } from 'crypto';

type PtySession = {
  sessionId: string;
  ptyProcess: IPty;
};

type ShellKind = 'powershell' | 'pwsh' | 'cmd' | 'bash';

const sessions = new Map<string, PtySession>();

function resolveShell(kind: ShellKind = 'powershell') {
  if (process.platform === 'win32') {
    if (kind === 'cmd') {
      return { shell: process.env.COMSPEC || 'cmd.exe', args: [] as string[] };
    }
    if (kind === 'pwsh') {
      return { shell: 'pwsh.exe', args: ['-NoLogo'] };
    }
    return { shell: 'powershell.exe', args: ['-NoLogo'] };
  }

  if (process.platform === 'darwin') {
    return { shell: process.env.SHELL || '/bin/zsh', args: ['-l'] };
  }

  return { shell: process.env.SHELL || '/bin/bash', args: ['-l'] };
}

function resolveCwd(requested?: string) {
  if (requested) {
    return requested;
  }
  return process.env.HOME || process.env.USERPROFILE || os.homedir();
}

export function registerTerminalIpc(getMainWindow: () => BrowserWindow | null) {
  ipcMain.handle(
    'pty:create',
    async (
      _event,
      shellKind: ShellKind = 'powershell',
      options?: { cwd?: string; cols?: number; rows?: number }
    ) => {
      const sessionId = randomUUID();
      const cwd = resolveCwd(options?.cwd);
      const { shell, args } = resolveShell(shellKind);
      const cols = Math.max(20, options?.cols ?? 120);
      const rows = Math.max(5, options?.rows ?? 30);

      const env = { ...process.env } as Record<string, string>;
      if (process.platform === 'win32' && !env.SystemRoot) {
        env.SystemRoot = 'C:\\Windows';
      }
      env.TERM = 'xterm-256color';
      env.COLORTERM = 'truecolor';

      let ptyProcess: IPty;
      try {
        // ConPTY's helper (conpty_console_list_agent.js) crashes with "AttachConsole failed"
        // under Electron on some Windows configs, so we use the legacy winpty path on Windows.
        // It's noticeably more reliable and the visual difference is invisible to users.
        const spawnOptions: pty.IPtyForkOptions = {
          name: 'xterm-256color',
          cols,
          rows,
          cwd,
          env,
        };
        if (process.platform === 'win32') {
          (spawnOptions as { useConpty?: boolean }).useConpty = false;
        }
        ptyProcess = pty.spawn(shell, args, spawnOptions);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        return { error: `Failed to spawn ${shell}: ${message}` };
      }

      ptyProcess.onData((data) => {
        const mainWindow = getMainWindow();
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send('pty:data', { sessionId, data });
        }
      });

      ptyProcess.onExit(({ exitCode, signal }) => {
        const mainWindow = getMainWindow();
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.webContents.send('pty:exit', { sessionId, exitCode, signal });
        }
        sessions.delete(sessionId);
      });

      sessions.set(sessionId, { sessionId, ptyProcess });
      return { sessionId, shell, cwd, pid: ptyProcess.pid, shellKind };
    }
  );

  ipcMain.handle('pty:write', async (_event, sessionId: string, data: string) => {
    const session = sessions.get(sessionId);
    if (!session) return false;
    session.ptyProcess.write(data);
    return true;
  });

  ipcMain.handle('pty:resize', async (_event, sessionId: string, cols: number, rows: number) => {
    const session = sessions.get(sessionId);
    if (!session) return false;
    try {
      session.ptyProcess.resize(Math.max(1, cols), Math.max(1, rows));
    } catch {
      return false;
    }
    return true;
  });

  ipcMain.handle('pty:kill', async (_event, sessionId: string) => {
    const session = sessions.get(sessionId);
    if (!session) return false;
    try {
      session.ptyProcess.kill();
    } catch {
      // process may already be gone
    }
    sessions.delete(sessionId);
    return true;
  });
}
