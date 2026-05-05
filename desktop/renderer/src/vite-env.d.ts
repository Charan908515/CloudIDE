/// <reference types="vite/client" />

declare module '*?worker' {
  const workerConstructor: { new (): Worker };
  export default workerConstructor;
}

declare global {
  type ShellKind = 'powershell' | 'pwsh' | 'cmd' | 'bash';

  type PtyCreateResult =
    | {
        sessionId: string;
        shell: string;
        cwd: string;
        pid: number;
        shellKind: ShellKind;
      }
    | { error: string };

  interface SearchMatchLine {
    line: number;
    column: number;
    text: string;
    matchStart: number;
    matchEnd: number;
  }

  interface SearchFileMatches {
    path: string;
    relativePath: string;
    matches: SearchMatchLine[];
  }

  interface SearchResult {
    results: SearchFileMatches[];
    total: number;
    hitLimit: boolean;
    error?: string;
  }

  interface QuickFile {
    path: string;
    relativePath: string;
    name: string;
  }

  interface GitChange {
    path: string;
    status: 'modified' | 'added' | 'deleted' | 'untracked' | 'renamed' | 'conflict';
    staged: boolean;
  }

  interface GitStatusReport {
    isRepo: boolean;
    branch: string | null;
    changes: GitChange[];
    hasInitialCommit: boolean;
    error?: string;
  }

  interface AuthUser {
    email: string;
    name: string;
    picture: string;
  }

  interface AuthStatus {
    signedIn: boolean;
    user: AuthUser | null;
  }

  interface ProjectSyncMeta {
    projectId: string;
    projectName: string;
    driveFolderId: string;
    lastSyncedVersion: number;
    lastSyncedAt: number;
    machineId: string;
    files: Record<string, { sha: string; size: number; mtime?: number; driveFileId?: string }>;
  }

  interface DriveManifest {
    projectId: string;
    projectName: string;
    version: number;
    updatedAt: string;
    machineId: string;
    files: Record<string, { sha: string; size: number; driveFileId: string }>;
  }

  interface SyncDiff {
    toUpload: string[];
    toDelete: string[];
    unchanged: number;
    skippedLarge: Array<{ path: string; size: number }>;
  }

  interface SyncDiffResult extends SyncDiff {
    isInitialized: boolean;
    remoteAhead?: boolean;
    remoteVersion?: number;
    error?: string;
  }

  interface SyncResult {
    ok: boolean;
    error?: string;
    reason?: 'auth' | 'conflict' | 'no-remote' | 'not-initialized' | 'exists';
    manifest?: DriveManifest;
    diff?: SyncDiff;
  }

  type SyncStateName = 'idle' | 'snapshotting' | 'uploading' | 'downloading' | 'extracting' | 'success' | 'error';

  interface Window {
    cloudide: {
      fs: {
        getDefaultRoot(): Promise<string>;
        pickDirectory(): Promise<string | null>;
        readDir(targetPath?: string): Promise<import('@shared/types').FileNode>;
        readFile(filePath: string): Promise<string>;
        writeFile(filePath: string, content: string): Promise<boolean>;
        deleteFile(targetPath: string): Promise<boolean>;
        createFile(filePath: string): Promise<boolean>;
        createFolder(folderPath: string): Promise<boolean>;
        rename(oldPath: string, newPath: string): Promise<boolean>;
        startWatch(targetPath: string): Promise<boolean>;
        stopWatch(targetPath: string): Promise<boolean>;
        onChange(
          listener: (payload: Array<{ eventName: string; path: string }>) => void
        ): () => void;
      };
      exec: {
        run(filePath: string, stdin?: string): Promise<{ sessionId: string; pid: number }>;
        kill(sessionId: string): Promise<boolean>;
        stdin(sessionId: string, input: string): Promise<boolean>;
        onOutput(
          listener: (payload: { sessionId: string; stream: 'stdout' | 'stderr'; data: string }) => void
        ): () => void;
        onDone(listener: (payload: { sessionId: string; result: unknown }) => void): () => void;
      };
      pty: {
        create(
          shellKind?: ShellKind,
          options?: { cwd?: string; cols?: number; rows?: number }
        ): Promise<PtyCreateResult>;
        write(sessionId: string, data: string): Promise<boolean>;
        resize(sessionId: string, cols: number, rows: number): Promise<boolean>;
        kill(sessionId: string): Promise<boolean>;
        onData(listener: (payload: { sessionId: string; data: string }) => void): () => void;
        onExit?(
          listener: (payload: { sessionId: string; exitCode: number; signal?: number }) => void
        ): () => void;
      };
      search: {
        run(options: {
          query: string;
          cwd: string;
          caseSensitive?: boolean;
          wholeWord?: boolean;
          regex?: boolean;
          includeGlob?: string;
          excludeGlob?: string;
        }): Promise<SearchResult>;
        findFiles(cwd: string, query: string): Promise<QuickFile[]>;
      };
      git: {
        status(cwd: string): Promise<GitStatusReport>;
        init(cwd: string): Promise<boolean>;
        stage(cwd: string, filepaths: string[]): Promise<boolean>;
        unstage(cwd: string, filepaths: string[]): Promise<boolean>;
        discard(cwd: string, filepath: string): Promise<boolean>;
        commit(
          cwd: string,
          message: string,
          author?: { name?: string; email?: string }
        ): Promise<{ sha?: string; error?: string }>;
        branchList(cwd: string): Promise<string[]>;
        diffFile(cwd: string, filepath: string): Promise<string | null>;
      };
      auth: {
        status(): Promise<AuthStatus>;
        signIn(): Promise<{ ok: true; user: AuthUser } | { ok: false; error: string }>;
      };
      sync: {
        meta(projectRoot: string): Promise<ProjectSyncMeta | null>;
        remoteManifest(projectRoot: string): Promise<DriveManifest | null>;
        diff(projectRoot: string): Promise<SyncDiffResult | null>;
        initialize(projectRoot: string, projectName?: string): Promise<SyncResult>;
        push(projectRoot: string, projectName?: string, force?: boolean): Promise<SyncResult>;
        pull(projectRoot: string): Promise<SyncResult>;
        unlink(projectRoot: string): Promise<boolean>;
        listCloudProjects(): Promise<Array<{ id: string; name: string; modifiedTime?: string }> | { error: string }>;
        cloneCloudProject(driveFolderId: string, projectName: string, localTargetDir: string): Promise<SyncResult>;
        onState(listener: (payload: { state: SyncStateName; message?: string }) => void): () => void;
      };
    };
  }
}

export {};
