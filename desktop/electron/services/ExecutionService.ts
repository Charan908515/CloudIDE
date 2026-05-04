import { BrowserWindow } from 'electron';
import { spawn, ChildProcessWithoutNullStreams } from 'child_process';
import fs from 'fs';
import path from 'path';
import { randomUUID } from 'crypto';
import { ExecutionResult } from '../../shared/types';
import { languageMap, LanguageRunConfig } from '../../shared/languageMap';

type RunningProcess = {
  process: ChildProcessWithoutNullStreams;
  startedAt: number;
  timeout: NodeJS.Timeout;
  killTimeout?: NodeJS.Timeout;
};

export class ExecutionService {
  private sessions = new Map<string, RunningProcess>();

  constructor(private getMainWindow: () => BrowserWindow | null) {}

  async runFile(filePath: string, stdin = '') {
    const language = this.detectLanguage(filePath);
    if (!language || !language.cmd) {
      throw new Error(`No local runner configured for ${path.extname(filePath)}`);
    }

    const sessionId = randomUUID();
    const cwd = this.findProjectRoot(path.dirname(filePath));
    const command = language.isProject ? language.cmd : `${language.cmd} "${filePath}"`;
    const child = spawn(command, {
      cwd,
      shell: true,
      env: process.env,
    });

    const startedAt = Date.now();
    const timeout = setTimeout(() => {
      child.kill('SIGTERM');
      const killTimeout = setTimeout(() => child.kill('SIGKILL'), 3000);
      const session = this.sessions.get(sessionId);
      if (session) {
        session.killTimeout = killTimeout;
      }
    }, 60000);

    this.sessions.set(sessionId, { process: child, startedAt, timeout });

    child.stdout.on('data', (chunk) => {
      this.emit('exec:output', { sessionId, stream: 'stdout', data: String(chunk) });
    });

    child.stderr.on('data', (chunk) => {
      this.emit('exec:output', { sessionId, stream: 'stderr', data: String(chunk) });
    });

    child.on('close', (exitCode) => {
      const session = this.sessions.get(sessionId);
      if (session) {
        clearTimeout(session.timeout);
        if (session.killTimeout) {
          clearTimeout(session.killTimeout);
        }
      }
      this.sessions.delete(sessionId);

      const result: ExecutionResult = {
        stdout: '',
        stderr: '',
        exitCode: exitCode ?? -1,
        duration: Date.now() - startedAt,
        source: 'local',
        language: language.lang,
      };
      this.emit('exec:done', { sessionId, result });
    });

    if (stdin) {
      child.stdin.write(stdin);
      child.stdin.end();
    }

    return {
      sessionId,
      pid: child.pid ?? -1,
    };
  }

  kill(sessionId: string) {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return false;
    }

    session.process.kill('SIGTERM');
    return true;
  }

  writeStdin(sessionId: string, input: string) {
    const session = this.sessions.get(sessionId);
    if (!session) {
      return false;
    }

    session.process.stdin.write(input);
    return true;
  }

  private emit(channel: 'exec:output' | 'exec:done', payload: unknown) {
    const mainWindow = this.getMainWindow();
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send(channel, payload);
    }
  }

  private detectLanguage(filePath: string): LanguageRunConfig | null {
    return languageMap[path.extname(filePath)] || null;
  }

  private findProjectRoot(startDir: string) {
    let currentDir = startDir;
    const markers = ['package.json', 'Cargo.toml', 'go.mod', 'requirements.txt', '.cloudide'];

    while (true) {
      if (markers.some((marker) => fs.existsSync(path.join(currentDir, marker)))) {
        return currentDir;
      }

      const parent = path.dirname(currentDir);
      if (parent === currentDir) {
        return startDir;
      }
      currentDir = parent;
    }
  }
}
