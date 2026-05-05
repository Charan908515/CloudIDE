import { useCallback, useEffect, useMemo, useState } from 'react';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useEditorStore } from '../../store/editorStore';
import { useSyncStore } from '../../store/syncStore';
import { useOfflineQueueStore } from '../../store/offlineQueueStore';
import { toast } from '../../store/toastStore';
import Codicon from '../common/Codicon';
import { fileIconFor } from '../common/fileIcon';

const STATUS_META: Record<GitChange['status'], { letter: string; color: string; title: string }> = {
  modified: { letter: 'M', color: '#e2c08d', title: 'Modified' },
  added: { letter: 'A', color: '#81b88b', title: 'Added' },
  deleted: { letter: 'D', color: '#c74e39', title: 'Deleted' },
  untracked: { letter: 'U', color: '#73c991', title: 'Untracked' },
  renamed: { letter: 'R', color: '#7eb6f6', title: 'Renamed' },
  conflict: { letter: '!', color: '#c74e39', title: 'Conflict' },
};

function basename(p: string) {
  const norm = p.replace(/\\/g, '/');
  return norm.split('/').filter(Boolean).pop() ?? p;
}

function dirname(p: string) {
  const norm = p.replace(/\\/g, '/');
  const idx = norm.lastIndexOf('/');
  return idx === -1 ? '' : norm.slice(0, idx);
}

function joinAbs(rootPath: string, rel: string) {
  const sep = rootPath.includes('\\') ? '\\' : '/';
  return `${rootPath.replace(/[\\/]$/, '')}${sep}${rel.replace(/\//g, sep)}`;
}

type ViewMode = 'list' | 'tree';

const VIEW_MODE_KEY = 'cloudide-scm-view';

function loadViewMode(): ViewMode {
  try {
    const stored = localStorage.getItem(VIEW_MODE_KEY);
    return stored === 'tree' ? 'tree' : 'list';
  } catch {
    return 'list';
  }
}

export default function SourceControlPanel() {
  const bridge = window.cloudide;
  const rootPath = useWorkspaceStore((s) => s.rootPath);
  const openFile = useEditorStore((s) => s.openFile);
  const autoSyncOnCommit = useSyncStore((s) => s.autoSyncOnCommit);
  const enqueue = useOfflineQueueStore((s) => s.enqueue);

  const [status, setStatus] = useState<GitStatusReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [viewMode, setViewMode] = useState<ViewMode>(loadViewMode);

  useEffect(() => {
    try { localStorage.setItem(VIEW_MODE_KEY, viewMode); } catch { /* ignore */ }
  }, [viewMode]);

  const refresh = useCallback(async () => {
    if (!bridge?.git || !rootPath) {
      setStatus(null);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const next = await bridge.git.status(rootPath);
      setStatus(next);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [bridge, rootPath]);

  useEffect(() => { void refresh(); }, [refresh]);

  useEffect(() => {
    if (!bridge?.fs) return;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const remove = bridge.fs.onChange(() => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => void refresh(), 400);
    });
    return () => {
      remove();
      if (timer) clearTimeout(timer);
    };
  }, [bridge, refresh]);

  async function handleStage(path: string) {
    if (!bridge?.git || !rootPath) return;
    setBusy(path);
    try { await bridge.git.stage(rootPath, [path]); await refresh(); }
    finally { setBusy(null); }
  }

  async function handleUnstage(path: string) {
    if (!bridge?.git || !rootPath) return;
    setBusy(path);
    try { await bridge.git.unstage(rootPath, [path]); await refresh(); }
    finally { setBusy(null); }
  }

  async function handleStageAll() {
    if (!bridge?.git || !rootPath || !status) return;
    const unstaged = status.changes.filter((c) => !c.staged).map((c) => c.path);
    if (unstaged.length === 0) return;
    setBusy('__all__');
    try { await bridge.git.stage(rootPath, unstaged); await refresh(); }
    finally { setBusy(null); }
  }

  async function handleUnstageAll() {
    if (!bridge?.git || !rootPath || !status) return;
    const staged = status.changes.filter((c) => c.staged).map((c) => c.path);
    if (staged.length === 0) return;
    setBusy('__unstageAll__');
    try { await bridge.git.unstage(rootPath, staged); await refresh(); }
    finally { setBusy(null); }
  }

  async function handleDiscard(change: GitChange) {
    if (!bridge?.git || !rootPath) return;
    if (!window.confirm(`Discard changes to ${basename(change.path)}? This cannot be undone.`)) return;
    setBusy(change.path);
    try {
      await bridge.git.discard(rootPath, change.path);
      await refresh();
    } finally { setBusy(null); }
  }

  async function handleCommit() {
    if (!bridge?.git || !rootPath || !message.trim()) return;
    setBusy('__commit__');
    setError('');
    try {
      const out = await bridge.git.commit(rootPath, message.trim());
      if (out.error) {
        setError(out.error);
        toast.error('Commit failed', out.error);
      } else {
        setMessage('');
        toast.success('Committed', message.trim().slice(0, 60));
        await refresh();
        if (autoSyncOnCommit && bridge.sync) {
          const projectName = rootPath.split(/[\/\\]/).filter(Boolean).pop() ?? 'Project';
          if (navigator.onLine) {
            const result = await bridge.sync.push(rootPath, projectName);
            if (result.ok) {
              toast.success('Synced to Drive', `Pushed after commit`);
            } else if (result.error?.toLowerCase().includes('conflict')) {
              toast.warning('Drive conflict', result.error);
            } else {
              enqueue(rootPath, projectName);
              toast.warning('Drive sync queued', 'Will push when network is available.');
            }
          } else {
            enqueue(rootPath, projectName);
            toast.warning('Offline — commit queued for Drive', 'Will push when back online.');
          }
        }
      }
    } finally { setBusy(null); }
  }

  async function handleInit() {
    if (!bridge?.git || !rootPath) return;
    setBusy('__init__');
    try { await bridge.git.init(rootPath); await refresh(); }
    finally { setBusy(null); }
  }

  async function openChange(path: string) {
    if (!bridge?.fs || !rootPath) return;
    try {
      const abs = joinAbs(rootPath, path);
      const content = await bridge.fs.readFile(abs);
      openFile({ path: abs, name: basename(path), content, savedContent: content });
    } catch { /* ignore */ }
  }

  const staged = useMemo(() => status?.changes.filter((c) => c.staged) ?? [], [status]);
  const unstaged = useMemo(() => status?.changes.filter((c) => !c.staged) ?? [], [status]);

  if (!bridge?.git) {
    return <div className="panel-placeholder">Source control is available only in the desktop window.</div>;
  }
  if (!rootPath) {
    return <div className="panel-placeholder"><p>Open a folder to use Source Control.</p></div>;
  }
  if (status && !status.isRepo) {
    return (
      <div className="panel-placeholder" style={{ flexDirection: 'column', gap: 12 }}>
        <p>The folder isn't a Git repository.</p>
        <button
          className="explorer__primary-button"
          onClick={() => void handleInit()}
          disabled={busy === '__init__'}
          style={{ width: 'auto' }}
          type="button"
        >
          {busy === '__init__' ? 'Initializing…' : 'Initialize Repository'}
        </button>
      </div>
    );
  }

  return (
    <div className="scm-panel">
      <div className="scm-panel__commit">
        <textarea
          className="scm-panel__message"
          placeholder={`Message (Ctrl+Enter to commit on '${status?.branch ?? 'HEAD'}')`}
          value={message}
          rows={2}
          onChange={(e) => setMessage(e.target.value)}
          onKeyDown={(e) => {
            if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
              e.preventDefault();
              void handleCommit();
            }
          }}
        />
        <button
          className="scm-panel__commit-button"
          onClick={() => void handleCommit()}
          disabled={!message.trim() || staged.length === 0 || busy === '__commit__'}
          type="button"
        >
          <Codicon name="check" size={14} />
          {busy === '__commit__' ? 'Committing…' : `Commit${staged.length ? ` (${staged.length})` : ''}`}
        </button>
        {error ? <div className="scm-panel__error">{error}</div> : null}
      </div>

      <div className="scm-panel__toolbar">
        <span className="scm-panel__branch" title="Current branch">
          <Codicon name="git-branch" size={12} />
          {status?.branch ?? 'HEAD'}
        </span>
        <div className="scm-panel__toolbar-actions">
          <button
            className="explorer__icon-button"
            onClick={() => void handleStageAll()}
            title="Stage All Changes"
            type="button"
            disabled={unstaged.length === 0}
          >
            <Codicon name="add" size={14} />
          </button>
          <button
            className="explorer__icon-button"
            onClick={() => setViewMode((v) => (v === 'list' ? 'tree' : 'list'))}
            title={viewMode === 'list' ? 'View as Tree' : 'View as List'}
            type="button"
          >
            <Codicon name={viewMode === 'list' ? 'list-tree' : 'list-flat'} size={14} />
          </button>
          <button
            className="explorer__icon-button"
            onClick={() => void refresh()}
            title="Refresh"
            type="button"
          >
            <Codicon name="refresh" size={14} spin={loading} />
          </button>
        </div>
      </div>

      {staged.length > 0 ? (
        <ChangeSection
          title="Staged Changes"
          count={staged.length}
          changes={staged}
          mode={viewMode}
          busy={busy}
          isStaged
          onOpen={openChange}
          onPrimary={(c) => void handleUnstage(c.path)}
          onDiscard={undefined}
          onSectionAction={() => void handleUnstageAll()}
          sectionActionLabel="Unstage All"
          sectionActionIcon="dash"
        />
      ) : null}

      {unstaged.length > 0 ? (
        <ChangeSection
          title="Changes"
          count={unstaged.length}
          changes={unstaged}
          mode={viewMode}
          busy={busy}
          isStaged={false}
          onOpen={openChange}
          onPrimary={(c) => void handleStage(c.path)}
          onDiscard={(c) => void handleDiscard(c)}
        />
      ) : null}

      {staged.length === 0 && unstaged.length === 0 && status?.isRepo ? (
        <div className="panel-placeholder" style={{ minHeight: 80 }}>
          <p>No changes.</p>
        </div>
      ) : null}
    </div>
  );
}

interface ChangeSectionProps {
  title: string;
  count: number;
  changes: GitChange[];
  mode: ViewMode;
  busy: string | null;
  isStaged: boolean;
  onOpen(path: string): void;
  onPrimary(change: GitChange): void;
  onDiscard?(change: GitChange): void;
  onSectionAction?(): void;
  sectionActionLabel?: string;
  sectionActionIcon?: string;
}

function ChangeSection(props: ChangeSectionProps) {
  const {
    title, count, changes, mode, busy, isStaged,
    onOpen, onPrimary, onDiscard,
    onSectionAction, sectionActionLabel, sectionActionIcon,
  } = props;

  return (
    <div className="scm-section">
      <div className="scm-section__header">
        <span>{title.toUpperCase()}</span>
        <span className="scm-section__count">{count}</span>
        {onSectionAction ? (
          <button
            className="explorer__icon-button scm-section__action"
            onClick={onSectionAction}
            title={sectionActionLabel}
            type="button"
          >
            <Codicon name={sectionActionIcon ?? 'add'} size={14} />
          </button>
        ) : null}
      </div>
      {mode === 'list'
        ? changes.map((c) => (
            <ChangeRow
              key={`${isStaged ? 's' : 'u'}-${c.path}`}
              change={c}
              busy={busy === c.path}
              isStaged={isStaged}
              indent={0}
              onOpen={() => onOpen(c.path)}
              onPrimary={() => onPrimary(c)}
              onDiscard={onDiscard ? () => onDiscard(c) : undefined}
            />
          ))
        : (
          <ChangeTree
            changes={changes}
            busy={busy}
            isStaged={isStaged}
            onOpen={onOpen}
            onPrimary={onPrimary}
            onDiscard={onDiscard}
          />
        )}
    </div>
  );
}

// === Tree builder + recursive renderer ===

interface TreeDir {
  type: 'dir';
  name: string;
  path: string;
  children: Array<TreeDir | TreeFile>;
}
interface TreeFile {
  type: 'file';
  name: string;
  change: GitChange;
}

function buildTree(changes: GitChange[]): TreeDir {
  const root: TreeDir = { type: 'dir', name: '', path: '', children: [] };
  for (const change of changes) {
    const parts = change.path.replace(/\\/g, '/').split('/').filter(Boolean);
    if (parts.length === 0) continue;
    let current = root;
    for (let i = 0; i < parts.length - 1; i++) {
      const segment = parts[i];
      const accPath = current.path ? `${current.path}/${segment}` : segment;
      let dir = current.children.find((c) => c.type === 'dir' && c.name === segment) as TreeDir | undefined;
      if (!dir) {
        dir = { type: 'dir', name: segment, path: accPath, children: [] };
        current.children.push(dir);
      }
      current = dir;
    }
    current.children.push({ type: 'file', name: parts[parts.length - 1], change });
  }
  // Sort: dirs first, then alphabetical.
  const sortRecursive = (dir: TreeDir) => {
    dir.children.sort((a, b) => {
      if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
      return a.name.localeCompare(b.name);
    });
    for (const child of dir.children) {
      if (child.type === 'dir') sortRecursive(child);
    }
  };
  sortRecursive(root);
  return root;
}

interface ChangeTreeProps {
  changes: GitChange[];
  busy: string | null;
  isStaged: boolean;
  onOpen(path: string): void;
  onPrimary(change: GitChange): void;
  onDiscard?(change: GitChange): void;
}

function ChangeTree({ changes, busy, isStaged, onOpen, onPrimary, onDiscard }: ChangeTreeProps) {
  const tree = useMemo(() => buildTree(changes), [changes]);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());

  const toggle = (path: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path); else next.add(path);
      return next;
    });
  };

  const renderNode = (node: TreeDir | TreeFile, depth: number): React.ReactNode => {
    if (node.type === 'file') {
      return (
        <ChangeRow
          key={`${isStaged ? 's' : 'u'}-${node.change.path}`}
          change={node.change}
          busy={busy === node.change.path}
          isStaged={isStaged}
          indent={depth}
          onOpen={() => onOpen(node.change.path)}
          onPrimary={() => onPrimary(node.change)}
          onDiscard={onDiscard ? () => onDiscard(node.change) : undefined}
        />
      );
    }
    const isCollapsed = collapsed.has(node.path);
    return (
      <div key={`d-${node.path}`}>
        <button
          className="scm-change scm-change--dir"
          onClick={() => toggle(node.path)}
          style={{ paddingLeft: depth * 14 + 12 }}
          type="button"
        >
          <Codicon name={isCollapsed ? 'chevron-right' : 'chevron-down'} size={14} className="scm-change__chevron" />
          <Codicon name={isCollapsed ? 'folder' : 'folder-opened'} size={14} style={{ color: '#dcb67a' }} />
          <span className="scm-change__name">{node.name}</span>
        </button>
        {!isCollapsed ? node.children.map((child) => renderNode(child, depth + 1)) : null}
      </div>
    );
  };

  return <>{tree.children.map((c) => renderNode(c, 0))}</>;
}

// === Single-row component ===

interface ChangeRowProps {
  change: GitChange;
  busy: boolean;
  isStaged: boolean;
  indent: number;
  onOpen(): void;
  onPrimary(): void;
  onDiscard?(): void;
}

function ChangeRow({ change, busy, isStaged, indent, onOpen, onPrimary, onDiscard }: ChangeRowProps) {
  const meta = STATUS_META[change.status];
  const dir = dirname(change.path);
  const name = basename(change.path);
  const file = fileIconFor(name);
  const isDeleted = change.status === 'deleted';

  return (
    <div
      className={`scm-change ${busy ? 'is-busy' : ''} ${isDeleted ? 'is-deleted' : ''}`}
      onClick={onOpen}
      title={change.path}
      style={{ paddingLeft: indent * 14 + 12 }}
    >
      <Codicon name={file.icon} size={14} style={{ color: file.color, flexShrink: 0 }} />
      <span className="scm-change__name" style={{ color: meta.color }}>{name}</span>
      {dir ? <span className="scm-change__dir">{dir}</span> : null}

      <div className="scm-change__actions">
        {onDiscard ? (
          <button
            className="explorer__icon-button"
            title="Discard Changes"
            onClick={(e) => { e.stopPropagation(); onDiscard(); }}
            type="button"
          >
            <Codicon name="discard" size={14} />
          </button>
        ) : null}
        <button
          className="explorer__icon-button"
          title={isStaged ? 'Unstage Changes' : 'Stage Changes'}
          onClick={(e) => { e.stopPropagation(); onPrimary(); }}
          type="button"
        >
          <Codicon name={isStaged ? 'dash' : 'add'} size={14} />
        </button>
      </div>

      <span className="scm-change__status" title={meta.title} style={{ color: meta.color }}>
        {meta.letter}
      </span>
    </div>
  );
}
