import { BrowserWindow, OpenDialogOptions, app, dialog, ipcMain } from 'electron';
import chokidar, { FSWatcher } from 'chokidar';
import { promises as fsp } from 'fs';
import path from 'path';
import { FileNode } from '../../shared/types';

const watchers = new Map<string, FSWatcher>();

const IGNORED_DIR_NAMES = new Set([
  'node_modules',
  '.git',
  'dist',
  'build',
  '.next',
  '.turbo',
  '.cache',
  '.parcel-cache',
  '.venv',
  '__pycache__',
  '.idea',
  '.vscode-test',
]);

const MAX_FILE_SIZE = 8 * 1024 * 1024; // 8 MB safety cap for readFile

function getWorkspaceRoot(targetPath?: string) {
  if (targetPath && path.isAbsolute(targetPath)) {
    return targetPath;
  }
  return app.getPath('documents');
}

function shouldIgnoreDir(name: string) {
  return IGNORED_DIR_NAMES.has(name);
}

async function readDirShallow(targetPath: string): Promise<FileNode> {
  // One stat for the root node itself.
  let rootStats;
  try {
    rootStats = await fsp.stat(targetPath);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    throw new Error(`Cannot access ${targetPath}: ${message}`);
  }

  const node: FileNode = {
    name: path.basename(targetPath) || targetPath,
    path: targetPath,
    type: rootStats.isDirectory() ? 'directory' : 'file',
    size: rootStats.isFile() ? rootStats.size : undefined,
    modified: rootStats.mtimeMs,
  };

  if (!rootStats.isDirectory()) {
    return node;
  }

  // withFileTypes avoids per-entry stat() calls.
  let entries;
  try {
    entries = await fsp.readdir(targetPath, { withFileTypes: true });
  } catch {
    node.children = [];
    return node;
  }

  const children: FileNode[] = [];
  for (const entry of entries) {
    if (entry.isDirectory() && shouldIgnoreDir(entry.name)) continue;
    // Skip symlinks pointing outside — keep the tree fast and predictable.
    const childPath = path.join(targetPath, entry.name);
    children.push({
      name: entry.name,
      path: childPath,
      type: entry.isDirectory() ? 'directory' : 'file',
    });
  }

  children.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'directory' ? -1 : 1;
    return a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' });
  });

  node.children = children;
  return node;
}

export function registerFileSystemIpc(getMainWindow: () => BrowserWindow | null) {
  ipcMain.handle('fs:getDefaultRoot', async () => app.getPath('documents'));

  ipcMain.handle('fs:pickDirectory', async () => {
    const mainWindow = getMainWindow();
    const options: OpenDialogOptions = { properties: ['openDirectory'] };
    const result = mainWindow
      ? await dialog.showOpenDialog(mainWindow, options)
      : await dialog.showOpenDialog(options);

    if (result.canceled || result.filePaths.length === 0) {
      return null;
    }
    return result.filePaths[0];
  });

  ipcMain.handle('fs:readDir', async (_event, targetPath?: string) =>
    readDirShallow(getWorkspaceRoot(targetPath))
  );

  ipcMain.handle('fs:readFile', async (_event, filePath: string) => {
    const stats = await fsp.stat(filePath);
    if (stats.size > MAX_FILE_SIZE) {
      throw new Error(`File too large to open in editor (${(stats.size / 1024 / 1024).toFixed(1)} MB)`);
    }
    return fsp.readFile(filePath, 'utf8');
  });

  ipcMain.handle('fs:writeFile', async (_event, filePath: string, content: string) => {
    await fsp.writeFile(filePath, content, 'utf8');
    return true;
  });

  ipcMain.handle('fs:deleteFile', async (_event, targetPath: string) => {
    await fsp.rm(targetPath, { recursive: true, force: true });
    return true;
  });

  ipcMain.handle('fs:createFile', async (_event, filePath: string) => {
    await fsp.mkdir(path.dirname(filePath), { recursive: true });
    await fsp.writeFile(filePath, '', 'utf8');
    return true;
  });

  ipcMain.handle('fs:createFolder', async (_event, folderPath: string) => {
    await fsp.mkdir(folderPath, { recursive: true });
    return true;
  });

  ipcMain.handle('fs:rename', async (_event, oldPath: string, newPath: string) => {
    await fsp.rename(oldPath, newPath);
    return true;
  });

  ipcMain.handle('fs:watch:start', async (_event, targetPath: string) => {
    const resolvedPath = getWorkspaceRoot(targetPath);
    if (watchers.has(resolvedPath)) {
      return true;
    }

    const watcher = chokidar.watch(resolvedPath, {
      ignoreInitial: true,
      depth: 6,
      ignored: [
        /(^|[\\/])\.git([\\/]|$)/,
        /(^|[\\/])node_modules([\\/]|$)/,
        /(^|[\\/])dist([\\/]|$)/,
        /(^|[\\/])build([\\/]|$)/,
      ],
      awaitWriteFinish: { stabilityThreshold: 80, pollInterval: 40 },
    });

    let pendingTimer: NodeJS.Timeout | null = null;
    const queue: Array<{ eventName: string; path: string }> = [];
    const flush = () => {
      pendingTimer = null;
      const mainWindow = getMainWindow();
      if (!mainWindow || mainWindow.isDestroyed()) {
        queue.length = 0;
        return;
      }
      const batch = queue.splice(0, queue.length);
      mainWindow.webContents.send('fs:change', batch);
    };

    watcher.on('all', (eventName, changedPath) => {
      queue.push({ eventName, path: changedPath });
      if (!pendingTimer) {
        pendingTimer = setTimeout(flush, 80);
      }
    });

    watchers.set(resolvedPath, watcher);
    return true;
  });

  ipcMain.handle('fs:watch:stop', async (_event, targetPath: string) => {
    const resolvedPath = getWorkspaceRoot(targetPath);
    const watcher = watchers.get(resolvedPath);
    if (watcher) {
      await watcher.close();
      watchers.delete(resolvedPath);
    }
    return true;
  });
}
