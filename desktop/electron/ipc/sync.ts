import { BrowserWindow, ipcMain } from 'electron';
import path from 'path';
import { AuthService } from '../services/AuthService';
import { SyncService } from '../services/SyncService';

function projectNameFromPath(projectRoot: string) {
  return path.basename(projectRoot) || 'CloudIDE Project';
}

export function registerSyncIpc(
  authService: AuthService,
  getMainWindow: () => BrowserWindow | null
) {
  const sync = new SyncService(authService);

  function emitTo(window: BrowserWindow | null) {
    return (state: string, message?: string) => {
      if (window && !window.isDestroyed()) {
        window.webContents.send('sync:state', { state, message });
      }
    };
  }

  ipcMain.handle('sync:meta', async (_event, projectRoot: string) => {
    if (!projectRoot) return null;
    return sync.getMeta(projectRoot);
  });

  ipcMain.handle('sync:remoteManifest', async (_event, projectRoot: string) => {
    if (!projectRoot) return null;
    return sync.getRemoteManifest(projectRoot);
  });

  ipcMain.handle('sync:diff', async (_event, projectRoot: string) => {
    if (!projectRoot) return null;
    try {
      return await sync.diff(projectRoot);
    } catch (error) {
      return { error: error instanceof Error ? error.message : String(error) };
    }
  });

  ipcMain.handle('sync:initialize', async (_event, projectRoot: string, projectName?: string) => {
    if (!projectRoot) return { ok: false, error: 'No project open' };
    const emit = emitTo(getMainWindow());
    const result = await sync.initialize(projectRoot, projectName ?? projectNameFromPath(projectRoot), { emit });
    if (!result.ok) emit('error', result.error);
    return result;
  });

  ipcMain.handle('sync:push', async (_event, projectRoot: string, projectName?: string, force = false) => {
    if (!projectRoot) return { ok: false, error: 'No project open' };
    const emit = emitTo(getMainWindow());
    const result = await sync.push(projectRoot, projectName ?? projectNameFromPath(projectRoot), {
      emit,
      force,
    });
    if (!result.ok) emit('error', result.error);
    return result;
  });

  ipcMain.handle('sync:pull', async (_event, projectRoot: string) => {
    if (!projectRoot) return { ok: false, error: 'No project open' };
    const emit = emitTo(getMainWindow());
    const result = await sync.pull(projectRoot, { emit });
    if (!result.ok) emit('error', result.error);
    return result;
  });

  ipcMain.handle('sync:unlink', async (_event, projectRoot: string) => {
    if (!projectRoot) return false;
    await sync.unlink(projectRoot);
    return true;
  });

  ipcMain.handle('sync:listCloudProjects', async () => {
    try {
      return await sync.listCloudProjects();
    } catch (error) {
      return { error: error instanceof Error ? error.message : String(error) };
    }
  });

  ipcMain.handle('sync:cloneCloudProject', async (_event, driveFolderId: string, projectName: string, localTargetDir: string) => {
    const emit = emitTo(getMainWindow());
    const result = await sync.cloneCloudProject(driveFolderId, projectName, localTargetDir, { emit });
    if (!result.ok) emit('error', result.error);
    return result;
  });
}
