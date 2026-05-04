import { app, BrowserWindow } from 'electron';
import path from 'path';
import { registerExecutionIpc } from './ipc/execution';
import { registerFileSystemIpc } from './ipc/fileSystem';
import { registerTerminalIpc } from './ipc/terminal';
import { registerSearchIpc } from './ipc/search';
import { registerGitIpc } from './ipc/git';
import { createAuthService, registerAuthIpc } from './ipc/auth';
import { registerSyncIpc } from './ipc/sync';

const isMac = process.platform === 'darwin';
const isDev = !app.isPackaged;

let mainWindow: BrowserWindow | null = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    titleBarStyle: 'hidden',
    titleBarOverlay: !isMac
      ? { color: '#181818', symbolColor: '#cccccc', height: 30 }
      : undefined,
    trafficLightPosition: isMac ? { x: 12, y: 10 } : undefined,
    backgroundColor: '#1e1e1e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });

  mainWindow.webContents.on('did-fail-load', (_event, errorCode, errorDescription, validatedURL) => {
    console.error('Renderer failed to load:', { errorCode, errorDescription, validatedURL });
  });

  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    console.error('Renderer process gone:', details);
  });

  mainWindow.webContents.on('console-message', (_event, level, message, line, sourceId) => {
    console.log(`Renderer console [${level}] ${sourceId}:${line} ${message}`);
  });

  if (isDev) {
    mainWindow.webContents.openDevTools({ mode: 'detach' });
    void mainWindow.loadURL('http://localhost:5173');
  } else {
    void mainWindow.loadFile(path.join(__dirname, '../../renderer/dist/index.html'));
  }
}

function safeRegister(label: string, fn: () => void) {
  try {
    fn();
    console.log(`[main] registered ${label}`);
  } catch (error) {
    console.error(`[main] failed to register ${label}:`, error);
  }
}

app.whenReady().then(() => {
  safeRegister('fileSystem IPC', () => registerFileSystemIpc(() => mainWindow));
  safeRegister('execution IPC', () => registerExecutionIpc(() => mainWindow));
  safeRegister('terminal IPC', () => registerTerminalIpc(() => mainWindow));
  safeRegister('search IPC', () => registerSearchIpc());
  safeRegister('git IPC', () => registerGitIpc());
  const authService = createAuthService();
  safeRegister('auth IPC', () => registerAuthIpc(authService));
  safeRegister('sync IPC', () => registerSyncIpc(authService, () => mainWindow));
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (!isMac) {
    app.quit();
  }
});
