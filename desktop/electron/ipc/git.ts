import { ipcMain } from 'electron';
import { promises as fsp } from 'fs';
import fs from 'fs';
import path from 'path';
import git from 'isomorphic-git';

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

async function isGitRepo(dir: string): Promise<boolean> {
  try {
    await fsp.access(path.join(dir, '.git'));
    return true;
  } catch {
    return false;
  }
}

function classify(row: [string, number, number, number]): { status: GitChange['status']; staged: boolean } | null {
  const [, head, workdir, stage] = row;
  // 0 = absent, 1 = unchanged, 2 = modified, 3 = different across all
  // workdir 0 means deleted in workdir
  // Reference: https://isomorphic-git.org/docs/en/statusMatrix
  if (head === 0 && workdir === 2 && stage === 0) return { status: 'untracked', staged: false };
  if (head === 0 && workdir === 2 && stage === 2) return { status: 'added', staged: true };
  if (head === 0 && workdir === 2 && stage === 3) return { status: 'added', staged: true };
  if (head === 1 && workdir === 2 && stage === 1) return { status: 'modified', staged: false };
  if (head === 1 && workdir === 2 && stage === 2) return { status: 'modified', staged: true };
  if (head === 1 && workdir === 2 && stage === 3) return { status: 'modified', staged: true };
  if (head === 1 && workdir === 0 && stage === 1) return { status: 'deleted', staged: false };
  if (head === 1 && workdir === 0 && stage === 0) return { status: 'deleted', staged: true };
  if (head === 1 && workdir === 1 && stage === 0) return { status: 'deleted', staged: true };
  return null;
}

export function registerGitIpc() {
  ipcMain.handle('git:status', async (_event, cwd: string): Promise<GitStatusReport> => {
    if (!cwd) {
      return { isRepo: false, branch: null, changes: [], hasInitialCommit: false };
    }

    const repo = await isGitRepo(cwd);
    if (!repo) {
      return { isRepo: false, branch: null, changes: [], hasInitialCommit: false };
    }

    try {
      const branch = await git.currentBranch({ fs, dir: cwd, fullname: false });
      let hasInitialCommit = true;
      try {
        await git.resolveRef({ fs, dir: cwd, ref: 'HEAD' });
      } catch {
        hasInitialCommit = false;
      }

      const matrix = await git.statusMatrix({
        fs,
        dir: cwd,
        filter: (filepath) =>
          !filepath.startsWith('node_modules/') &&
          !filepath.startsWith('.git/') &&
          !filepath.startsWith('dist/') &&
          !filepath.startsWith('build/'),
      });

      const changes: GitChange[] = [];
      for (const row of matrix) {
        const classified = classify(row as [string, number, number, number]);
        if (classified) {
          changes.push({ path: row[0], status: classified.status, staged: classified.staged });
        }
      }

      return { isRepo: true, branch: branch ?? null, changes, hasInitialCommit };
    } catch (error) {
      return {
        isRepo: true,
        branch: null,
        changes: [],
        hasInitialCommit: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }
  });

  ipcMain.handle('git:init', async (_event, cwd: string) => {
    await git.init({ fs, dir: cwd, defaultBranch: 'main' });
    return true;
  });

  ipcMain.handle('git:stage', async (_event, cwd: string, filepaths: string[]) => {
    for (const filepath of filepaths) {
      const abs = path.join(cwd, filepath);
      let exists = true;
      try {
        await fsp.access(abs);
      } catch {
        exists = false;
      }
      if (exists) {
        await git.add({ fs, dir: cwd, filepath });
      } else {
        await git.remove({ fs, dir: cwd, filepath });
      }
    }
    return true;
  });

  ipcMain.handle('git:unstage', async (_event, cwd: string, filepaths: string[]) => {
    for (const filepath of filepaths) {
      try {
        await git.resetIndex({ fs, dir: cwd, filepath });
      } catch (error) {
        console.error('git:unstage failed for', filepath, error);
      }
    }
    return true;
  });

  ipcMain.handle('git:discard', async (_event, cwd: string, filepath: string) => {
    try {
      const head = await git.readBlob({ fs, dir: cwd, oid: await git.resolveRef({ fs, dir: cwd, ref: 'HEAD' }), filepath });
      await fsp.writeFile(path.join(cwd, filepath), Buffer.from(head.blob));
      return true;
    } catch {
      return false;
    }
  });

  ipcMain.handle(
    'git:commit',
    async (
      _event,
      cwd: string,
      message: string,
      author?: { name?: string; email?: string }
    ) => {
      if (!message?.trim()) {
        return { error: 'Commit message is required' };
      }

      const name = author?.name?.trim() || 'CloudIDE User';
      const email = author?.email?.trim() || 'user@cloudide.local';

      try {
        const sha = await git.commit({
          fs,
          dir: cwd,
          message,
          author: { name, email },
        });
        return { sha };
      } catch (error) {
        return { error: error instanceof Error ? error.message : String(error) };
      }
    }
  );

  ipcMain.handle('git:branchList', async (_event, cwd: string) => {
    try {
      return await git.listBranches({ fs, dir: cwd });
    } catch {
      return [];
    }
  });

  ipcMain.handle('git:diffFile', async (_event, cwd: string, filepath: string) => {
    try {
      const oid = await git.resolveRef({ fs, dir: cwd, ref: 'HEAD' });
      const blob = await git.readBlob({ fs, dir: cwd, oid, filepath });
      return Buffer.from(blob.blob).toString('utf8');
    } catch {
      return null;
    }
  });
}
