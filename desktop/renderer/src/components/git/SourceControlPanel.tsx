import { useCallback, useEffect, useState } from 'react';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useEditorStore } from '../../store/editorStore';
import Codicon from '../common/Codicon';

const STATUS_LETTER: Record<GitChange['status'], { letter: string; color: string; title: string }> = {
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

export default function SourceControlPanel() {
  const bridge = window.cloudide;
  const rootPath = useWorkspaceStore((s) => s.rootPath);
  const openFile = useEditorStore((s) => s.openFile);

  const [status, setStatus] = useState<GitStatusReport | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');

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

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Watch fs changes to auto-refresh status (debounced).
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

  async function handleStage(filepath: string) {
    if (!bridge?.git || !rootPath) return;
    setBusy(filepath);
    try {
      await bridge.git.stage(rootPath, [filepath]);
      await refresh();
    } finally {
      setBusy(null);
    }
  }

  async function handleUnstage(filepath: string) {
    if (!bridge?.git || !rootPath) return;
    setBusy(filepath);
    try {
      await bridge.git.unstage(rootPath, [filepath]);
      await refresh();
    } finally {
      setBusy(null);
    }
  }

  async function handleStageAll() {
    if (!bridge?.git || !rootPath || !status) return;
    const unstaged = status.changes.filter((c) => !c.staged).map((c) => c.path);
    if (unstaged.length === 0) return;
    setBusy('__all__');
    try {
      await bridge.git.stage(rootPath, unstaged);
      await refresh();
    } finally {
      setBusy(null);
    }
  }

  async function handleCommit() {
    if (!bridge?.git || !rootPath || !message.trim()) return;
    setBusy('__commit__');
    setError('');
    try {
      const out = await bridge.git.commit(rootPath, message.trim());
      if (out.error) {
        setError(out.error);
      } else {
        setMessage('');
        await refresh();
      }
    } finally {
      setBusy(null);
    }
  }

  async function handleInit() {
    if (!bridge?.git || !rootPath) return;
    setBusy('__init__');
    try {
      await bridge.git.init(rootPath);
      await refresh();
    } finally {
      setBusy(null);
    }
  }

  async function openChange(filepath: string) {
    if (!bridge?.fs || !rootPath) return;
    const abs = `${rootPath}${rootPath.includes('\\') ? '\\' : '/'}${filepath.replace(/\//g, rootPath.includes('\\') ? '\\' : '/')}`;
    try {
      const content = await bridge.fs.readFile(abs);
      openFile({ path: abs, name: basename(filepath), content, savedContent: content });
    } catch {
      /* ignore */
    }
  }

  if (!bridge?.git) {
    return <div className="panel-placeholder">Source control is available only in the desktop window.</div>;
  }

  if (!rootPath) {
    return (
      <div className="panel-placeholder">
        <p>Open a folder to use Source Control.</p>
      </div>
    );
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

  const staged = status?.changes.filter((c) => c.staged) ?? [];
  const unstaged = status?.changes.filter((c) => !c.staged) ?? [];

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
        <span className="scm-panel__branch">
          <Codicon name="git-branch" size={12} />
          {status?.branch ?? 'HEAD'}
        </span>
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
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
            onClick={() => void refresh()}
            title="Refresh"
            type="button"
          >
            <Codicon name="refresh" size={14} spin={loading} />
          </button>
        </div>
      </div>

      {staged.length > 0 ? (
        <div className="scm-section">
          <div className="scm-section__header">
            STAGED CHANGES <span className="scm-section__count">{staged.length}</span>
          </div>
          {staged.map((change) => (
            <ScmChangeRow
              key={`s-${change.path}`}
              change={change}
              busy={busy === change.path}
              onClick={() => void openChange(change.path)}
              actions={
                <button
                  className="explorer__icon-button"
                  title="Unstage Changes"
                  onClick={(e) => {
                    e.stopPropagation();
                    void handleUnstage(change.path);
                  }}
                  type="button"
                >
                  <Codicon name="dash" size={14} />
                </button>
              }
            />
          ))}
        </div>
      ) : null}

      {unstaged.length > 0 ? (
        <div className="scm-section">
          <div className="scm-section__header">
            CHANGES <span className="scm-section__count">{unstaged.length}</span>
          </div>
          {unstaged.map((change) => (
            <ScmChangeRow
              key={`u-${change.path}`}
              change={change}
              busy={busy === change.path}
              onClick={() => void openChange(change.path)}
              actions={
                <button
                  className="explorer__icon-button"
                  title="Stage Changes"
                  onClick={(e) => {
                    e.stopPropagation();
                    void handleStage(change.path);
                  }}
                  type="button"
                >
                  <Codicon name="add" size={14} />
                </button>
              }
            />
          ))}
        </div>
      ) : null}

      {staged.length === 0 && unstaged.length === 0 && status?.isRepo ? (
        <div className="panel-placeholder" style={{ minHeight: 80 }}>
          <p>No changes.</p>
        </div>
      ) : null}
    </div>
  );
}

interface ScmChangeRowProps {
  change: GitChange;
  busy: boolean;
  onClick(): void;
  actions: React.ReactNode;
}

function ScmChangeRow({ change, busy, onClick, actions }: ScmChangeRowProps) {
  const meta = STATUS_LETTER[change.status];
  const dir = dirname(change.path);
  return (
    <div className={`scm-change ${busy ? 'is-busy' : ''}`} onClick={onClick} title={change.path}>
      <span className="scm-change__name">{basename(change.path)}</span>
      {dir ? <span className="scm-change__dir">{dir}</span> : null}
      <div className="scm-change__actions">{actions}</div>
      <span className="scm-change__status" title={meta.title} style={{ color: meta.color }}>
        {meta.letter}
      </span>
    </div>
  );
}
