import { ipcMain } from 'electron';
import { promises as fsp, createReadStream } from 'fs';
import path from 'path';
import readline from 'readline';

const IGNORED_DIRS = new Set([
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
  'out',
  'target',
]);

const MAX_FILE_SIZE = 4 * 1024 * 1024;
const MAX_RESULTS = 1500;
const MAX_LINE_LENGTH = 500;

interface SearchOptions {
  query: string;
  cwd: string;
  caseSensitive?: boolean;
  wholeWord?: boolean;
  regex?: boolean;
  includeGlob?: string;
  excludeGlob?: string;
}

interface SearchMatchLine {
  line: number;
  column: number;
  text: string;
  matchStart: number;
  matchEnd: number;
}

interface FileMatches {
  path: string;
  relativePath: string;
  matches: SearchMatchLine[];
}

function escapeRegex(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function buildPattern(opts: SearchOptions): RegExp {
  let body = opts.regex ? opts.query : escapeRegex(opts.query);
  if (opts.wholeWord) body = `\\b${body}\\b`;
  const flags = opts.caseSensitive ? 'g' : 'gi';
  return new RegExp(body, flags);
}

function globToRegex(glob: string): RegExp {
  // simple glob: *, **, ?
  const tokens = glob
    .replace(/[.+^${}()|[\]\\]/g, '\\$&')
    .replace(/\*\*/g, '<<DOUBLE>>')
    .replace(/\*/g, '[^/\\\\]*')
    .replace(/<<DOUBLE>>/g, '.*')
    .replace(/\?/g, '.');
  return new RegExp(`^${tokens}$`, 'i');
}

function matchesAnyGlob(relPath: string, globs: RegExp[]): boolean {
  if (globs.length === 0) return false;
  const normalized = relPath.replace(/\\/g, '/');
  return globs.some((re) => re.test(normalized));
}

async function isProbablyBinary(filePath: string): Promise<boolean> {
  try {
    const fd = await fsp.open(filePath, 'r');
    try {
      const buf = Buffer.alloc(8000);
      const { bytesRead } = await fd.read(buf, 0, 8000, 0);
      for (let i = 0; i < bytesRead; i++) {
        if (buf[i] === 0) return true;
      }
    } finally {
      await fd.close();
    }
    return false;
  } catch {
    return true;
  }
}

async function searchFile(
  filePath: string,
  relativePath: string,
  pattern: RegExp,
  results: FileMatches[],
  remaining: { count: number }
): Promise<void> {
  const fileMatch: FileMatches = { path: filePath, relativePath, matches: [] };
  return new Promise<void>((resolve) => {
    const stream = createReadStream(filePath, { encoding: 'utf8' });
    const rl = readline.createInterface({ input: stream, crlfDelay: Infinity });
    let lineNumber = 0;
    let stopped = false;

    rl.on('line', (line) => {
      if (stopped) return;
      lineNumber += 1;
      if (line.length > MAX_LINE_LENGTH * 8) return;
      pattern.lastIndex = 0;
      let match: RegExpExecArray | null;
      let matchesOnLine = 0;
      while ((match = pattern.exec(line)) !== null) {
        const display = line.length > MAX_LINE_LENGTH ? line.slice(0, MAX_LINE_LENGTH) + '…' : line;
        fileMatch.matches.push({
          line: lineNumber,
          column: match.index + 1,
          text: display,
          matchStart: match.index,
          matchEnd: match.index + match[0].length,
        });
        remaining.count -= 1;
        matchesOnLine += 1;
        if (remaining.count <= 0 || matchesOnLine > 50) {
          stopped = true;
          rl.close();
          stream.destroy();
          break;
        }
        if (match.index === pattern.lastIndex) pattern.lastIndex += 1;
      }
    });
    rl.on('close', () => {
      if (fileMatch.matches.length > 0) results.push(fileMatch);
      resolve();
    });
    stream.on('error', () => resolve());
  });
}

async function* walk(dir: string, baseDir: string): AsyncGenerator<{ full: string; relative: string }> {
  let entries;
  try {
    entries = await fsp.readdir(dir, { withFileTypes: true });
  } catch {
    return;
  }

  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (IGNORED_DIRS.has(entry.name)) continue;
      yield* walk(full, baseDir);
    } else if (entry.isFile()) {
      yield { full, relative: path.relative(baseDir, full) };
    }
  }
}

export function registerSearchIpc() {
  ipcMain.handle('search:run', async (_event, options: SearchOptions) => {
    if (!options?.cwd || !options.query) {
      return { results: [], total: 0, hitLimit: false };
    }

    let pattern: RegExp;
    try {
      pattern = buildPattern(options);
    } catch (error) {
      return {
        results: [],
        total: 0,
        hitLimit: false,
        error: error instanceof Error ? error.message : String(error),
      };
    }

    const includeGlobs = (options.includeGlob ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map(globToRegex);
    const excludeGlobs = (options.excludeGlob ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map(globToRegex);

    const results: FileMatches[] = [];
    const remaining = { count: MAX_RESULTS };

    for await (const { full, relative } of walk(options.cwd, options.cwd)) {
      if (remaining.count <= 0) break;
      if (includeGlobs.length > 0 && !matchesAnyGlob(relative, includeGlobs)) continue;
      if (excludeGlobs.length > 0 && matchesAnyGlob(relative, excludeGlobs)) continue;

      let stats;
      try {
        stats = await fsp.stat(full);
      } catch {
        continue;
      }
      if (stats.size > MAX_FILE_SIZE) continue;
      if (await isProbablyBinary(full)) continue;

      await searchFile(full, relative, pattern, results, remaining);
    }

    const total = results.reduce((sum, r) => sum + r.matches.length, 0);
    return { results, total, hitLimit: remaining.count <= 0 };
  });

  ipcMain.handle('search:findFiles', async (_event, cwd: string, query: string) => {
    if (!cwd) return [];
    const trimmed = query.trim().toLowerCase();
    const out: Array<{ path: string; relativePath: string; name: string }> = [];
    for await (const { full, relative } of walk(cwd, cwd)) {
      const name = path.basename(full);
      if (!trimmed || name.toLowerCase().includes(trimmed) || relative.toLowerCase().includes(trimmed)) {
        out.push({ path: full, relativePath: relative, name });
      }
      if (out.length > 500) break;
    }
    return out;
  });
}
