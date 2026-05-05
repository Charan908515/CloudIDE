import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('cloudide', {
  fs: {
    getDefaultRoot: () => ipcRenderer.invoke('fs:getDefaultRoot'),
    pickDirectory: () => ipcRenderer.invoke('fs:pickDirectory'),
    readDir: (targetPath?: string) => ipcRenderer.invoke('fs:readDir', targetPath),
    readFile: (filePath: string) => ipcRenderer.invoke('fs:readFile', filePath),
    writeFile: (filePath: string, content: string) => ipcRenderer.invoke('fs:writeFile', filePath, content),
    deleteFile: (targetPath: string) => ipcRenderer.invoke('fs:deleteFile', targetPath),
    createFile: (filePath: string) => ipcRenderer.invoke('fs:createFile', filePath),
    createFolder: (folderPath: string) => ipcRenderer.invoke('fs:createFolder', folderPath),
    rename: (oldPath: string, newPath: string) => ipcRenderer.invoke('fs:rename', oldPath, newPath),
    startWatch: (targetPath: string) => ipcRenderer.invoke('fs:watch:start', targetPath),
    stopWatch: (targetPath: string) => ipcRenderer.invoke('fs:watch:stop', targetPath),
    onChange: (listener: (payload: Array<{ eventName: string; path: string }>) => void) => {
      const wrapped = (_event: unknown, payload: Array<{ eventName: string; path: string }>) => listener(payload);
      ipcRenderer.on('fs:change', wrapped);
      return () => ipcRenderer.removeListener('fs:change', wrapped);
    },
  },
  exec: {
    run: (filePath: string, stdin?: string) => ipcRenderer.invoke('exec:run', filePath, stdin),
    kill: (sessionId: string) => ipcRenderer.invoke('exec:kill', sessionId),
    stdin: (sessionId: string, input: string) => ipcRenderer.invoke('exec:stdin', sessionId, input),
    onOutput: (listener: (payload: { sessionId: string; stream: 'stdout' | 'stderr'; data: string }) => void) => {
      const wrapped = (_event: unknown, payload: { sessionId: string; stream: 'stdout' | 'stderr'; data: string }) =>
        listener(payload);
      ipcRenderer.on('exec:output', wrapped);
      return () => ipcRenderer.removeListener('exec:output', wrapped);
    },
    onDone: (listener: (payload: { sessionId: string; result: unknown }) => void) => {
      const wrapped = (_event: unknown, payload: { sessionId: string; result: unknown }) => listener(payload);
      ipcRenderer.on('exec:done', wrapped);
      return () => ipcRenderer.removeListener('exec:done', wrapped);
    },
  },
  pty: {
    create: (
      shellKind?: 'powershell' | 'pwsh' | 'cmd' | 'bash',
      options?: { cwd?: string; cols?: number; rows?: number }
    ) => ipcRenderer.invoke('pty:create', shellKind, options),
    write: (sessionId: string, data: string) => ipcRenderer.invoke('pty:write', sessionId, data),
    resize: (sessionId: string, cols: number, rows: number) =>
      ipcRenderer.invoke('pty:resize', sessionId, cols, rows),
    kill: (sessionId: string) => ipcRenderer.invoke('pty:kill', sessionId),
    onData: (listener: (payload: { sessionId: string; data: string }) => void) => {
      const wrapped = (_event: unknown, payload: { sessionId: string; data: string }) => listener(payload);
      ipcRenderer.on('pty:data', wrapped);
      return () => ipcRenderer.removeListener('pty:data', wrapped);
    },
    onExit: (listener: (payload: { sessionId: string; exitCode: number; signal?: number }) => void) => {
      const wrapped = (_event: unknown, payload: { sessionId: string; exitCode: number; signal?: number }) =>
        listener(payload);
      ipcRenderer.on('pty:exit', wrapped);
      return () => ipcRenderer.removeListener('pty:exit', wrapped);
    },
  },
  search: {
    run: (options: {
      query: string;
      cwd: string;
      caseSensitive?: boolean;
      wholeWord?: boolean;
      regex?: boolean;
      includeGlob?: string;
      excludeGlob?: string;
    }) => ipcRenderer.invoke('search:run', options),
    findFiles: (cwd: string, query: string) => ipcRenderer.invoke('search:findFiles', cwd, query),
  },
  git: {
    status: (cwd: string) => ipcRenderer.invoke('git:status', cwd),
    init: (cwd: string) => ipcRenderer.invoke('git:init', cwd),
    stage: (cwd: string, filepaths: string[]) => ipcRenderer.invoke('git:stage', cwd, filepaths),
    unstage: (cwd: string, filepaths: string[]) => ipcRenderer.invoke('git:unstage', cwd, filepaths),
    discard: (cwd: string, filepath: string) => ipcRenderer.invoke('git:discard', cwd, filepath),
    commit: (cwd: string, message: string, author?: { name?: string; email?: string }) =>
      ipcRenderer.invoke('git:commit', cwd, message, author),
    branchList: (cwd: string) => ipcRenderer.invoke('git:branchList', cwd),
    diffFile: (cwd: string, filepath: string) => ipcRenderer.invoke('git:diffFile', cwd, filepath),
  },
  auth: {
    status: () => ipcRenderer.invoke('auth:status'),
    signIn: () => ipcRenderer.invoke('auth:signIn'),
  },
  sync: {
    meta: (projectRoot: string) => ipcRenderer.invoke('sync:meta', projectRoot),
    remoteManifest: (projectRoot: string) => ipcRenderer.invoke('sync:remoteManifest', projectRoot),
    diff: (projectRoot: string) => ipcRenderer.invoke('sync:diff', projectRoot),
    initialize: (projectRoot: string, projectName?: string) =>
      ipcRenderer.invoke('sync:initialize', projectRoot, projectName),
    push: (projectRoot: string, projectName?: string, force?: boolean) =>
      ipcRenderer.invoke('sync:push', projectRoot, projectName, force ?? false),
    pull: (projectRoot: string) => ipcRenderer.invoke('sync:pull', projectRoot),
    unlink: (projectRoot: string) => ipcRenderer.invoke('sync:unlink', projectRoot),
    listCloudProjects: () => ipcRenderer.invoke('sync:listCloudProjects'),
    cloneCloudProject: (driveFolderId: string, projectName: string, localTargetDir: string) =>
      ipcRenderer.invoke('sync:cloneCloudProject', driveFolderId, projectName, localTargetDir),
    onState: (listener: (payload: { state: string; message?: string }) => void) => {
      const wrapped = (_event: unknown, payload: { state: string; message?: string }) => listener(payload);
      ipcRenderer.on('sync:state', wrapped);
      return () => ipcRenderer.removeListener('sync:state', wrapped);
    },
  },
});
