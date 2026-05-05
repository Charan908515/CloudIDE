import { promises as fsp, createReadStream } from 'fs';
import path from 'path';
import os from 'os';
import crypto from 'crypto';
import { AuthService } from './AuthService';
import { DriveClient } from './DriveClient';
import { desktopConfig } from '../utils/config';

const PROJECT_META_DIR = '.cloudide';
const PROJECT_META_FILE = 'project.json';
const REMOTE_MANIFEST_FILE = '.cloudide-manifest.json';
const IGNORE_FILE = '.cloudideignore';
const MAX_FILE_SIZE = 100 * 1024 * 1024;

const DEFAULT_IGNORED_DIRS = [
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
  PROJECT_META_DIR,
];

interface IgnoreMatcher {
  test(relativePosixPath: string, isDir: boolean): boolean;
}

function compileGlob(pattern: string): RegExp {
  // Minimal glob: ** = any path, * = any segment chars, ? = single char.
  let out = '';
  let i = 0;
  while (i < pattern.length) {
    const ch = pattern[i];
    if (ch === '*' && pattern[i + 1] === '*') {
      out += '.*';
      i += 2;
      if (pattern[i] === '/') i += 1;
      continue;
    }
    if (ch === '*') {
      out += '[^/]*';
      i += 1;
      continue;
    }
    if (ch === '?') {
      out += '[^/]';
      i += 1;
      continue;
    }
    if ('.+^${}()|[]\\'.includes(ch)) {
      out += `\\${ch}`;
      i += 1;
      continue;
    }
    out += ch;
    i += 1;
  }
  return new RegExp(`^${out}$`);
}

async function loadIgnore(projectRoot: string): Promise<IgnoreMatcher> {
  const dirSet = new Set<string>(DEFAULT_IGNORED_DIRS);
  const globs: RegExp[] = [];
  const negated: RegExp[] = [];

  try {
    const text = await fsp.readFile(path.join(projectRoot, IGNORE_FILE), 'utf8');
    for (const rawLine of text.split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line || line.startsWith('#')) continue;
      const negate = line.startsWith('!');
      const body = negate ? line.slice(1) : line;
      const trimmed = body.replace(/^\/+/, '').replace(/\/+$/, '');
      if (!trimmed) continue;
      const matcher = compileGlob(trimmed.includes('/') ? trimmed : `**/${trimmed}`);
      if (negate) negated.push(matcher);
      else globs.push(matcher);
    }
  } catch {
    /* no ignore file = use defaults only */
  }

  return {
    test(relativePosixPath, isDir) {
      const segments = relativePosixPath.split('/').filter(Boolean);
      if (isDir) {
        const last = segments[segments.length - 1];
        if (last && dirSet.has(last)) return true;
      } else {
        for (const segment of segments.slice(0, -1)) {
          if (dirSet.has(segment)) return true;
        }
      }
      const matchedByGlob = globs.some((re) => re.test(relativePosixPath));
      if (!matchedByGlob) return false;
      if (negated.some((re) => re.test(relativePosixPath))) return false;
      return true;
    },
  };
}

interface FileEntry {
  sha: string;
  size: number;
  mtime?: number;
  driveFileId?: string;
}

interface RemoteManifest {
  projectId: string;
  projectName: string;
  version: number;
  updatedAt: string;
  machineId: string;
  files: Record<string, { sha: string; size: number; driveFileId: string }>;
}

interface LocalProjectMeta {
  projectId: string;
  projectName: string;
  driveFolderId: string;
  lastSyncedVersion: number;
  lastSyncedAt: number;
  machineId: string;
  // Cached file info from last successful sync — lets us skip rehashing untouched files.
  files: Record<string, FileEntry>;
}

export interface SyncDiff {
  toUpload: string[];    // new or modified
  toDelete: string[];    // removed locally, exists on remote
  unchanged: number;
  skippedLarge: Array<{ path: string; size: number }>;
}

export interface PushOptions {
  emit?: (state: string, message?: string) => void;
  force?: boolean; // bypass the conflict check; overwrite remote regardless
}

function machineId() {
  const id = `${os.hostname()}-${os.platform()}-${os.userInfo().username}`;
  return crypto.createHash('sha1').update(id).digest('hex').slice(0, 12);
}

function hashFile(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = createReadStream(filePath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('end', () => resolve(hash.digest('hex')));
    stream.on('error', reject);
  });
}

function toPosix(p: string) {
  return p.replace(/\\/g, '/');
}

async function readJsonFile<T>(filePath: string): Promise<T | null> {
  try {
    const text = await fsp.readFile(filePath, 'utf8');
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

async function writeJsonFile(filePath: string, data: unknown) {
  await fsp.mkdir(path.dirname(filePath), { recursive: true });
  await fsp.writeFile(filePath, JSON.stringify(data, null, 2), 'utf8');
}

async function* walkProject(projectRoot: string, ignore: IgnoreMatcher): AsyncGenerator<string> {
  async function* visit(dir: string): AsyncGenerator<string> {
    let entries;
    try {
      entries = await fsp.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      const relative = toPosix(path.relative(projectRoot, full));
      if (entry.isDirectory()) {
        if (ignore.test(relative, true)) continue;
        yield* visit(full);
      } else if (entry.isFile()) {
        if (ignore.test(relative, false)) continue;
        yield full;
      }
    }
  }
  yield* visit(projectRoot);
}

interface FileMapResult {
  files: Record<string, FileEntry>;
  skipped: Array<{ path: string; size: number; reason: 'too-large' }>;
}

async function buildLocalFileMap(
  projectRoot: string,
  cached: Record<string, FileEntry> = {}
): Promise<FileMapResult> {
  const ignore = await loadIgnore(projectRoot);
  const out: Record<string, FileEntry> = {};
  const skipped: FileMapResult['skipped'] = [];
  for await (const fullPath of walkProject(projectRoot, ignore)) {
    const relative = toPosix(path.relative(projectRoot, fullPath));
    let stats;
    try {
      stats = await fsp.stat(fullPath);
    } catch {
      continue;
    }
    if (stats.size > MAX_FILE_SIZE) {
      skipped.push({ path: relative, size: stats.size, reason: 'too-large' });
      continue;
    }

    const prev = cached[relative];
    if (prev && prev.mtime === stats.mtimeMs && prev.size === stats.size) {
      out[relative] = { ...prev };
      continue;
    }

    out[relative] = {
      sha: await hashFile(fullPath),
      size: stats.size,
      mtime: stats.mtimeMs,
      driveFileId: prev?.driveFileId,
    };
  }
  return { files: out, skipped };
}

function computeDiff(
  local: Record<string, FileEntry>,
  remote: Record<string, { sha: string }>,
  skippedLarge: Array<{ path: string; size: number }> = []
): SyncDiff {
  const toUpload: string[] = [];
  const toDelete: string[] = [];
  let unchanged = 0;

  for (const [relPath, info] of Object.entries(local)) {
    const remoteEntry = remote[relPath];
    if (!remoteEntry || remoteEntry.sha !== info.sha) {
      toUpload.push(relPath);
    } else {
      unchanged += 1;
    }
  }
  for (const remotePath of Object.keys(remote)) {
    if (!local[remotePath]) toDelete.push(remotePath);
  }
  return { toUpload, toDelete, unchanged, skippedLarge };
}

async function readLocalMeta(projectRoot: string): Promise<LocalProjectMeta | null> {
  return readJsonFile<LocalProjectMeta>(
    path.join(projectRoot, PROJECT_META_DIR, PROJECT_META_FILE)
  );
}

async function writeLocalMeta(projectRoot: string, meta: LocalProjectMeta) {
  await writeJsonFile(path.join(projectRoot, PROJECT_META_DIR, PROJECT_META_FILE), meta);
}

/** Resolve & cache Drive folder IDs along a path, creating folders as needed. */
class FolderCache {
  private cache = new Map<string, string>();

  constructor(private drive: DriveClient, private projectFolderId: string) {
    this.cache.set('', projectFolderId);
  }

  /** Get/create a folder ID for `relDir` (POSIX, e.g. "src/utils"). Empty string is project root. */
  async ensure(relDir: string): Promise<string> {
    const cached = this.cache.get(relDir);
    if (cached) return cached;

    const parts = relDir.split('/').filter(Boolean);
    let parentId = this.projectFolderId;
    let walked = '';
    for (const part of parts) {
      walked = walked ? `${walked}/${part}` : part;
      const known = this.cache.get(walked);
      if (known) {
        parentId = known;
        continue;
      }
      const id = await this.drive.ensureFolder(part, parentId);
      this.cache.set(walked, id);
      parentId = id;
    }
    return parentId;
  }
}

export class SyncService {
  constructor(private auth: AuthService) {}

  private async getDrive(): Promise<DriveClient | null> {
    const token = await this.auth.getValidAccessToken();
    if (!token) return null;
    return new DriveClient(token);
  }

  async isSignedIn(): Promise<boolean> {
    return !!(await this.auth.getValidAccessToken());
  }

  async getMeta(projectRoot: string): Promise<LocalProjectMeta | null> {
    return readLocalMeta(projectRoot);
  }

  /** Read remote manifest from Drive. */
  async getRemoteManifest(projectRoot: string): Promise<RemoteManifest | null> {
    const drive = await this.getDrive();
    const meta = await readLocalMeta(projectRoot);
    if (!drive || !meta) return null;
    try {
      const manifestId = await drive.findFile(REMOTE_MANIFEST_FILE, meta.driveFolderId);
      if (!manifestId) return null;
      const buf = await drive.downloadFile(manifestId);
      return JSON.parse(buf.toString('utf8')) as RemoteManifest;
    } catch {
      return null;
    }
  }

  /** Compute what would change without uploading anything. */
  async diff(projectRoot: string): Promise<SyncDiff & { isInitialized: boolean; remoteAhead?: boolean; remoteVersion?: number }> {
    const meta = await readLocalMeta(projectRoot);
    const { files: local, skipped } = await buildLocalFileMap(projectRoot, meta?.files ?? {});

    if (!meta) {
      return {
        toUpload: Object.keys(local),
        toDelete: [],
        unchanged: 0,
        skippedLarge: skipped.map((s) => ({ path: s.path, size: s.size })),
        isInitialized: false,
      };
    }
    const remote = await this.getRemoteManifest(projectRoot);
    const remoteFiles = remote?.files ?? meta.files;
    const diff = computeDiff(local, remoteFiles, skipped.map((s) => ({ path: s.path, size: s.size })));
    return {
      ...diff,
      isInitialized: true,
      remoteAhead: !!(remote && remote.version > meta.lastSyncedVersion && remote.machineId !== meta.machineId),
      remoteVersion: remote?.version,
    };
  }

  /** First-time setup: create the project folder on Drive, upload everything, write manifest. */
  async initialize(
    projectRoot: string,
    projectName: string,
    options?: PushOptions
  ): Promise<{ ok: true; manifest: RemoteManifest } | { ok: false; error: string; reason?: 'auth' | 'exists' }> {
    const emit = options?.emit ?? (() => undefined);
    const drive = await this.getDrive();
    if (!drive) return { ok: false, error: 'Not signed in to Google Drive', reason: 'auth' };

    const existing = await readLocalMeta(projectRoot);
    if (existing) {
      return { ok: false, error: 'Project is already linked to Drive. Use Push instead.', reason: 'exists' };
    }

    emit('snapshotting', 'Hashing files…');
    const { files: local } = await buildLocalFileMap(projectRoot);

    emit('uploading', 'Creating Drive folder…');
    const rootFolderId = await drive.ensureFolder(desktopConfig.driveRootName);
    const projectFolderId = await drive.ensureFolder(projectName, rootFolderId);
    const folders = new FolderCache(drive, projectFolderId);

    const remoteFiles: RemoteManifest['files'] = {};
    const total = Object.keys(local).length;
    let uploaded = 0;
    for (const [relPath, info] of Object.entries(local)) {
      uploaded += 1;
      emit('uploading', `Uploading ${uploaded}/${total}: ${relPath}`);
      const dir = toPosix(path.dirname(relPath));
      const parentId = await folders.ensure(dir === '.' ? '' : dir);
      const fileName = path.basename(relPath);
      const buf = await fsp.readFile(path.join(projectRoot, relPath));
      const driveFileId = await drive.upsertFile(fileName, parentId, buf);
      remoteFiles[relPath] = { sha: info.sha, size: info.size, driveFileId };
      info.driveFileId = driveFileId;
    }

    const manifest: RemoteManifest = {
      projectId: crypto.randomUUID(),
      projectName,
      version: 1,
      updatedAt: new Date().toISOString(),
      machineId: machineId(),
      files: remoteFiles,
    };

    emit('uploading', 'Writing manifest…');
    await drive.upsertFile(
      REMOTE_MANIFEST_FILE,
      projectFolderId,
      Buffer.from(JSON.stringify(manifest, null, 2), 'utf8'),
      'application/json'
    );

    const localMeta: LocalProjectMeta = {
      projectId: manifest.projectId,
      projectName,
      driveFolderId: projectFolderId,
      lastSyncedVersion: 1,
      lastSyncedAt: Date.now(),
      machineId: machineId(),
      files: local,
    };
    await writeLocalMeta(projectRoot, localMeta);

    emit('success', `Initialized ${total} file${total === 1 ? '' : 's'} on Drive`);
    return { ok: true, manifest };
  }

  /** Incremental push: only changed/new files get uploaded; deleted files removed. */
  async push(
    projectRoot: string,
    projectName: string,
    options?: PushOptions
  ): Promise<{ ok: true; manifest: RemoteManifest; diff: SyncDiff } | { ok: false; error: string; reason?: 'auth' | 'conflict' | 'not-initialized' }> {
    const emit = options?.emit ?? (() => undefined);
    const drive = await this.getDrive();
    if (!drive) return { ok: false, error: 'Not signed in to Google Drive', reason: 'auth' };

    const meta = await readLocalMeta(projectRoot);
    if (!meta) {
      return { ok: false, error: 'Project not initialized. Run "Initialize Drive sync" first.', reason: 'not-initialized' };
    }

    emit('snapshotting', 'Hashing changed files…');
    const { files: local, skipped } = await buildLocalFileMap(projectRoot, meta.files);

    emit('snapshotting', 'Reading remote manifest…');
    const remote = await this.getRemoteManifest(projectRoot);
    // Seed remoteFiles strictly typed (driveFileId required). Fall back to local meta when remote
    // is missing — the user re-pushed before the remote manifest was generated, or it was deleted.
    const remoteFiles: RemoteManifest['files'] = {};
    const seedSource = remote?.files ?? meta.files;
    for (const [k, v] of Object.entries(seedSource)) {
      if (v.driveFileId) {
        remoteFiles[k] = { sha: v.sha, size: v.size, driveFileId: v.driveFileId };
      }
    }

    // Conflict: someone else pushed since our last sync. `force` skips this check.
    if (
      !options?.force &&
      remote &&
      remote.version > meta.lastSyncedVersion &&
      remote.machineId !== meta.machineId
    ) {
      return {
        ok: false,
        error: `Remote is at version ${remote.version} (changed by another device). Pull first.`,
        reason: 'conflict',
      };
    }

    const diff = computeDiff(local, remoteFiles, skipped.map((s) => ({ path: s.path, size: s.size })));
    if (diff.toUpload.length === 0 && diff.toDelete.length === 0) {
      emit('success', 'Already up to date');
      return { ok: true, manifest: remote ?? buildManifestFromMeta(meta, projectName), diff };
    }

    const folders = new FolderCache(drive, meta.driveFolderId);

    // Upload new/changed files.
    let uploaded = 0;
    for (const relPath of diff.toUpload) {
      uploaded += 1;
      emit('uploading', `${uploaded}/${diff.toUpload.length} → ${relPath}`);
      const info = local[relPath];
      const dir = toPosix(path.dirname(relPath));
      const parentId = await folders.ensure(dir === '.' ? '' : dir);
      const fileName = path.basename(relPath);
      const buf = await fsp.readFile(path.join(projectRoot, relPath));

      // If we have a known driveFileId from the remote manifest, update in place.
      const existingId = remoteFiles[relPath]?.driveFileId;
      let driveFileId: string;
      if (existingId) {
        driveFileId = await drive.upsertFile(fileName, parentId, buf);
      } else {
        driveFileId = await drive.upsertFile(fileName, parentId, buf);
      }
      info.driveFileId = driveFileId;
      remoteFiles[relPath] = { sha: info.sha, size: info.size, driveFileId };
    }

    // Delete removed files.
    let deleted = 0;
    for (const relPath of diff.toDelete) {
      deleted += 1;
      emit('uploading', `Deleting ${deleted}/${diff.toDelete.length} → ${relPath}`);
      const id = remoteFiles[relPath]?.driveFileId;
      if (id) {
        try {
          await drive.deleteFile(id);
        } catch {
          /* ignore — file may already be gone */
        }
      }
      delete remoteFiles[relPath];
    }

    const nextVersion = (remote?.version ?? meta.lastSyncedVersion) + 1;
    const manifest: RemoteManifest = {
      projectId: meta.projectId,
      projectName,
      version: nextVersion,
      updatedAt: new Date().toISOString(),
      machineId: machineId(),
      files: remoteFiles,
    };

    emit('uploading', 'Writing manifest…');
    await drive.upsertFile(
      REMOTE_MANIFEST_FILE,
      meta.driveFolderId,
      Buffer.from(JSON.stringify(manifest, null, 2), 'utf8'),
      'application/json'
    );

    meta.lastSyncedVersion = nextVersion;
    meta.lastSyncedAt = Date.now();
    meta.machineId = machineId();
    meta.projectName = projectName;
    meta.files = local;
    await writeLocalMeta(projectRoot, meta);

    emit('success', `Pushed ${diff.toUpload.length} change${diff.toUpload.length === 1 ? '' : 's'}, deleted ${diff.toDelete.length}`);
    return { ok: true, manifest, diff };
  }

  /** Incremental pull: download changed/new files; delete locally what's gone from remote. */
  async pull(
    projectRoot: string,
    options?: PushOptions
  ): Promise<{ ok: true; manifest: RemoteManifest; diff: SyncDiff } | { ok: false; error: string; reason?: 'auth' | 'no-remote' }> {
    const emit = options?.emit ?? (() => undefined);
    const drive = await this.getDrive();
    if (!drive) return { ok: false, error: 'Not signed in to Google Drive', reason: 'auth' };

    const meta = await readLocalMeta(projectRoot);
    if (!meta) return { ok: false, error: 'Project not initialized. Initialize first.', reason: 'no-remote' };

    emit('downloading', 'Reading remote manifest…');
    const remote = await this.getRemoteManifest(projectRoot);
    if (!remote) return { ok: false, error: 'Remote manifest not found.', reason: 'no-remote' };

    const { files: local } = await buildLocalFileMap(projectRoot, meta.files);
    // For pull, we want the inverse: download files whose remote sha differs from local sha.
    const toDownload: string[] = [];
    const toDeleteLocal: string[] = [];
    for (const [relPath, info] of Object.entries(remote.files)) {
      if (!local[relPath] || local[relPath].sha !== info.sha) {
        toDownload.push(relPath);
      }
    }
    for (const relPath of Object.keys(local)) {
      if (!remote.files[relPath]) toDeleteLocal.push(relPath);
    }

    let downloaded = 0;
    for (const relPath of toDownload) {
      downloaded += 1;
      emit('downloading', `${downloaded}/${toDownload.length} ← ${relPath}`);
      const entry = remote.files[relPath];
      const buf = await drive.downloadFile(entry.driveFileId);
      const localPath = path.join(projectRoot, relPath);
      await fsp.mkdir(path.dirname(localPath), { recursive: true });
      await fsp.writeFile(localPath, buf);
    }

    let deletedLocal = 0;
    for (const relPath of toDeleteLocal) {
      deletedLocal += 1;
      emit('downloading', `Removing local ${deletedLocal}/${toDeleteLocal.length} → ${relPath}`);
      try {
        await fsp.unlink(path.join(projectRoot, relPath));
      } catch {
        /* ignore */
      }
    }

    // Refresh local file map post-pull so meta.files reflects on-disk truth.
    const { files: refreshed } = await buildLocalFileMap(projectRoot);
    meta.lastSyncedVersion = remote.version;
    meta.lastSyncedAt = Date.now();
    meta.projectName = remote.projectName;
    meta.files = refreshed;
    await writeLocalMeta(projectRoot, meta);

    const diff: SyncDiff = {
      toUpload: toDownload,    // semantic: changes pulled
      toDelete: toDeleteLocal,
      unchanged: Object.keys(remote.files).length - toDownload.length,
      skippedLarge: [],
    };
    emit('success', `Pulled ${toDownload.length} change${toDownload.length === 1 ? '' : 's'}, removed ${toDeleteLocal.length} local`);
    return { ok: true, manifest: remote, diff };
  }

  async unlink(projectRoot: string) {
    try {
      await fsp.rm(path.join(projectRoot, PROJECT_META_DIR), { recursive: true, force: true });
    } catch {
      /* ignore */
    }
  }

  async listCloudProjects(): Promise<Array<{ id: string; name: string; modifiedTime?: string }>> {
    const drive = await this.getDrive();
    if (!drive) throw new Error('Not signed in to Google Drive');
    
    const rootId = await drive.findFolder(desktopConfig.driveRootName);
    if (!rootId) return [];
    
    const children = await drive.listChildren(rootId);
    // Filter to just folders (since projects are folders)
    return children.filter(c => c.mimeType === 'application/vnd.google-apps.folder');
  }

  async cloneCloudProject(
    driveFolderId: string,
    projectName: string,
    localTargetDir: string,
    options?: PushOptions
  ): Promise<{ ok: true; manifest: RemoteManifest } | { ok: false; error: string }> {
    const emit = options?.emit ?? (() => undefined);
    const drive = await this.getDrive();
    if (!drive) return { ok: false, error: 'Not signed in to Google Drive' };

    try {
      // 1. Download manifest
      emit('downloading', 'Reading remote manifest…');
      const manifestId = await drive.findFile(REMOTE_MANIFEST_FILE, driveFolderId);
      if (!manifestId) return { ok: false, error: 'Project manifest not found on Drive. This might not be a valid CloudIDE project.' };
      
      const buf = await drive.downloadFile(manifestId);
      const remote = JSON.parse(buf.toString('utf8')) as RemoteManifest;
      
      // 2. Download all files
      const total = Object.keys(remote.files).length;
      let downloaded = 0;
      for (const [relPath, entry] of Object.entries(remote.files)) {
        downloaded++;
        emit('downloading', `Downloading ${downloaded}/${total}: ${relPath}`);
        const fileBuf = await drive.downloadFile(entry.driveFileId);
        const localPath = path.join(localTargetDir, relPath);
        await fsp.mkdir(path.dirname(localPath), { recursive: true });
        await fsp.writeFile(localPath, fileBuf);
      }
      
      // 3. Write local meta
      emit('downloading', 'Writing local metadata…');
      const { files: localMap } = await buildLocalFileMap(localTargetDir);
      // Merge driveFileIds
      for (const [relPath, info] of Object.entries(localMap)) {
        if (remote.files[relPath]) {
          info.driveFileId = remote.files[relPath].driveFileId;
        }
      }
      
      const meta: LocalProjectMeta = {
        projectId: remote.projectId,
        projectName,
        driveFolderId,
        lastSyncedVersion: remote.version,
        lastSyncedAt: Date.now(),
        machineId: machineId(),
        files: localMap,
      };
      await writeLocalMeta(localTargetDir, meta);
      
      emit('success', 'Project cloned successfully');
      return { ok: true, manifest: remote };
    } catch (e) {
      return { ok: false, error: e instanceof Error ? e.message : String(e) };
    }
  }
}

function buildManifestFromMeta(meta: LocalProjectMeta, projectName: string): RemoteManifest {
  const files: RemoteManifest['files'] = {};
  for (const [k, v] of Object.entries(meta.files)) {
    if (v.driveFileId) {
      files[k] = { sha: v.sha, size: v.size, driveFileId: v.driveFileId };
    }
  }
  return {
    projectId: meta.projectId,
    projectName,
    version: meta.lastSyncedVersion,
    updatedAt: new Date(meta.lastSyncedAt).toISOString(),
    machineId: meta.machineId,
    files,
  };
}
