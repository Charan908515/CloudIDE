export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'directory';
  children?: FileNode[];
  size?: number;
  modified?: number;
}

export interface ExecutionResult {
  stdout: string;
  stderr: string;
  exitCode: number;
  duration: number;
  source: 'local' | 'judge0' | 'piston';
  language: string;
}

export interface GitFileStatus {
  path: string;
  status: 'modified' | 'added' | 'deleted' | 'renamed' | 'conflict' | 'untracked';
  staged: boolean;
}

export interface GitCommit {
  sha: string;
  shortSha: string;
  message: string;
  author: string;
  email: string;
  timestamp: number;
  parents: string[];
}

export interface SyncEvent {
  type: 'upload' | 'download' | 'delete' | 'conflict' | 'error';
  localPath: string;
  driveFileId?: string;
  error?: string;
  timestamp: number;
}

export interface Checkpoint {
  id: string;
  name?: string;
  timestamp: number;
  sizeBytes: number;
  driveFileId: string;
  projectName: string;
}

export interface RunConfig {
  command: string;
  cwd: string;
  env: Record<string, string>;
  ports: number[];
}

export interface AuthState {
  googleAccessToken: string;
  googleRefreshToken: string;
  appJwt: string;
  user: { email: string; name: string; picture: string };
  expiresAt: number;
}
