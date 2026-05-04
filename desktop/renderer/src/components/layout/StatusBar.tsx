import { useEffect, useState } from 'react';
import Codicon from '../common/Codicon';
import { useSyncStore } from '../../store/syncStore';
import { useWorkspaceStore } from '../../store/workspaceStore';
import ConflictModal from '../sync/ConflictModal';

function relativeTime(timestamp: number): string {
  if (!timestamp) return '';
  const seconds = Math.floor((Date.now() - timestamp) / 1000);
  if (seconds < 30) return 'just now';
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

export default function StatusBar() {
  const bridge = window.cloudide;
  const rootPath = useWorkspaceStore((s) => s.rootPath);
  const signedIn = useSyncStore((s) => s.signedIn);
  const user = useSyncStore((s) => s.user);
  const state = useSyncStore((s) => s.state);
  const message = useSyncStore((s) => s.message);
  const meta = useSyncStore((s) => s.meta);
  const remote = useSyncStore((s) => s.remote);
  const diff = useSyncStore((s) => s.diff);
  const triggers = useSyncStore((s) => s.triggers);
  const intervalMinutes = useSyncStore((s) => s.intervalMinutes);
  const setAuth = useSyncStore((s) => s.setAuth);
  const setMeta = useSyncStore((s) => s.setMeta);
  const setRemote = useSyncStore((s) => s.setRemote);
  const setDiff = useSyncStore((s) => s.setDiff);
  const toggleTrigger = useSyncStore((s) => s.toggleTrigger);
  const setIntervalMinutes = useSyncStore((s) => s.setIntervalMinutes);
  const [menuOpen, setMenuOpen] = useState(false);
  const [conflict, setConflict] = useState<{ open: boolean; message?: string }>({ open: false });

  useEffect(() => {
    if (!menuOpen) return;
    function clickAway(event: MouseEvent) {
      const target = event.target as Element | null;
      if (!target?.closest('.sync-menu') && !target?.closest('[data-sync-trigger]')) {
        setMenuOpen(false);
      }
    }
    document.addEventListener('mousedown', clickAway);
    return () => document.removeEventListener('mousedown', clickAway);
  }, [menuOpen]);

  // Activity bar's cloud icon dispatches this — open the menu (or trigger sign-in if not signed in).
  useEffect(() => {
    function open() {
      if (!signedIn) {
        void handleSignIn();
      } else {
        setMenuOpen(true);
      }
    }
    window.addEventListener('cloudide:openSyncMenu', open);
    return () => window.removeEventListener('cloudide:openSyncMenu', open);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signedIn]);

  const inFlight =
    state === 'snapshotting' ||
    state === 'uploading' ||
    state === 'downloading' ||
    state === 'extracting';
  const isError = state === 'error';

  let icon = 'cloud-offline';
  let label = 'Offline';
  if (signedIn) {
    if (inFlight) {
      icon = 'sync';
      label = state === 'uploading' ? 'Uploading…' : state === 'downloading' ? 'Downloading…' : 'Syncing…';
    } else if (isError) {
      icon = 'warning';
      label = 'Sync error';
    } else if (!rootPath) {
      icon = 'cloud';
      label = user?.name?.split(' ')[0] ?? 'Signed in';
    } else if (!meta) {
      icon = 'cloud-upload';
      label = 'Initialize Drive sync';
    } else if (remote && remote.version > meta.lastSyncedVersion) {
      icon = 'cloud-download';
      label = `Pull v${remote.version}`;
    } else if (diff && (diff.toUpload.length > 0 || diff.toDelete.length > 0)) {
      icon = 'cloud-upload';
      const parts: string[] = [];
      if (diff.toUpload.length) parts.push(`${diff.toUpload.length} changed`);
      if (diff.toDelete.length) parts.push(`${diff.toDelete.length} deleted`);
      label = `Sync ${parts.join(', ')}`;
    } else {
      icon = 'check';
      label = `v${meta.lastSyncedVersion} · ${relativeTime(meta.lastSyncedAt)}`;
    }
  }

  async function refreshAfter() {
    if (!rootPath || !bridge?.sync) return;
    setMeta(await bridge.sync.meta(rootPath));
    setRemote(await bridge.sync.remoteManifest(rootPath));
    setDiff(await bridge.sync.diff(rootPath));
  }

  async function handleSignIn() {
    if (!bridge?.auth) return;
    const result = await bridge.auth.signIn();
    if (result.ok) setAuth(true, result.user);
    else window.alert(`Sign-in failed: ${result.error}`);
  }

  async function handleInitialize() {
    if (!bridge?.sync || !rootPath) return;
    setMenuOpen(false);
    const projectName = rootPath.split(/[\\/]/).filter(Boolean).pop() ?? 'Project';
    const ok = window.confirm(`Initialize "${projectName}" on Google Drive? This uploads all files in the folder (excluding node_modules, .git, etc.).`);
    if (!ok) return;
    const result = await bridge.sync.initialize(rootPath, projectName);
    if (!result.ok) window.alert(result.error ?? 'Initialize failed');
    await refreshAfter();
  }

  async function handlePush(force = false) {
    if (!bridge?.sync || !rootPath) return;
    setMenuOpen(false);
    const projectName = rootPath.split(/[\\/]/).filter(Boolean).pop() ?? 'Project';
    const result = await bridge.sync.push(rootPath, projectName, force);
    if (!result.ok) {
      if (result.reason === 'conflict') {
        setConflict({ open: true, message: result.error });
      } else {
        window.alert(result.error ?? 'Push failed');
      }
    }
    await refreshAfter();
  }

  async function handlePullThenPush() {
    setConflict({ open: false });
    if (!bridge?.sync || !rootPath) return;
    const projectName = rootPath.split(/[\\/]/).filter(Boolean).pop() ?? 'Project';
    const pull = await bridge.sync.pull(rootPath);
    if (!pull.ok) {
      window.alert(pull.error ?? 'Pull failed');
      await refreshAfter();
      return;
    }
    const push = await bridge.sync.push(rootPath, projectName);
    if (!push.ok) window.alert(push.error ?? 'Push failed');
    await refreshAfter();
  }

  async function handleForcePush() {
    setConflict({ open: false });
    await handlePush(true);
  }

  async function handlePull() {
    if (!bridge?.sync || !rootPath) return;
    setMenuOpen(false);
    if (!window.confirm('Pull will overwrite local files with the latest from Drive. Continue?')) return;
    const result = await bridge.sync.pull(rootPath);
    if (!result.ok) window.alert(result.error ?? 'Pull failed');
    await refreshAfter();
  }

  async function handleUnlink() {
    if (!bridge?.sync || !rootPath) return;
    setMenuOpen(false);
    if (!window.confirm('Stop syncing this folder? Drive copy is kept; local marker is removed.')) return;
    await bridge.sync.unlink(rootPath);
    setMeta(null);
    setRemote(null);
    setDiff(null);
  }

  return (
    <footer className="status-bar">
      <div className="status-bar__group">
        <span className="status-bar__item status-bar__item--button" title="Source Control">
          <Codicon name="source-control" size={14} />
          main
        </span>
        <span
          className={`status-bar__item status-bar__item--button ${isError ? 'status-bar__item--error' : ''}`}
          title={message || label}
          data-sync-trigger
          onClick={() => {
            if (!signedIn) void handleSignIn();
            else setMenuOpen((v) => !v);
          }}
          style={{ position: 'relative' }}
        >
          <Codicon name={icon} size={14} spin={inFlight} />
          {label}
          {menuOpen && signedIn ? (
            <div className="sync-menu">
              <div className="sync-menu__user">
                <div>{user?.name}</div>
                <div className="sync-menu__user-email">{user?.email}</div>
              </div>
              <div className="sync-menu__sep" />

              {!rootPath ? (
                <div className="sync-menu__hint">Open a folder to sync.</div>
              ) : !meta ? (
                <button type="button" className="sync-menu__item" onClick={() => void handleInitialize()}>
                  <Codicon name="cloud-upload" size={14} />
                  Initialize Drive sync
                </button>
              ) : (
                <>
                  {diff?.remoteAhead ? (
                    <div className="sync-menu__remote-ahead">
                      Remote is ahead (v{diff.remoteVersion}). Pull first or use Force push.
                    </div>
                  ) : null}
                  <button
                    type="button"
                    className="sync-menu__item"
                    onClick={() => void handlePush()}
                    disabled={inFlight || !diff || (diff.toUpload.length === 0 && diff.toDelete.length === 0)}
                  >
                    <Codicon name="cloud-upload" size={14} />
                    {diff && (diff.toUpload.length || diff.toDelete.length)
                      ? `Sync now (${diff.toUpload.length} changed, ${diff.toDelete.length} deleted)`
                      : 'Up to date'}
                  </button>
                  <button
                    type="button"
                    className="sync-menu__item"
                    onClick={() => void handlePull()}
                    disabled={inFlight}
                  >
                    <Codicon name="cloud-download" size={14} />
                    Pull from Drive
                  </button>
                </>
              )}

              <div className="sync-menu__sep" />
              <div className="sync-menu__heading">Auto-sync</div>
              {(['on-save', 'on-run', 'interval'] as const).map((trigger) => {
                const labels = {
                  'on-save': 'On save',
                  'on-run': 'On run',
                  interval: `Every ${intervalMinutes} minute${intervalMinutes === 1 ? '' : 's'}`,
                };
                return (
                  <button
                    key={trigger}
                    type="button"
                    className="sync-menu__item"
                    onClick={() => toggleTrigger(trigger)}
                  >
                    <Codicon
                      name={triggers.has(trigger) ? 'check' : 'circle-large-outline'}
                      size={14}
                    />
                    {labels[trigger]}
                  </button>
                );
              })}
              {triggers.has('interval') ? (
                <div className="sync-menu__interval">
                  <label htmlFor="sync-interval-minutes">Interval (min)</label>
                  <input
                    id="sync-interval-minutes"
                    type="number"
                    min={1}
                    max={120}
                    value={intervalMinutes}
                    onChange={(e) => setIntervalMinutes(Math.max(1, Number(e.target.value) || 1))}
                  />
                </div>
              ) : null}

              {diff && diff.skippedLarge.length > 0 ? (
                <>
                  <div className="sync-menu__sep" />
                  <div className="sync-menu__skipped">
                    <strong>Skipped (too large, &gt;100 MB)</strong>
                    <ul>
                      {diff.skippedLarge.slice(0, 5).map((s) => (
                        <li key={s.path}>{s.path}</li>
                      ))}
                      {diff.skippedLarge.length > 5 ? <li>… and {diff.skippedLarge.length - 5} more</li> : null}
                    </ul>
                  </div>
                </>
              ) : null}

              {meta ? (
                <>
                  <div className="sync-menu__sep" />
                  <button
                    type="button"
                    className="sync-menu__item"
                    onClick={() => void handleUnlink()}
                  >
                    <Codicon name="debug-disconnect" size={14} />
                    Stop syncing this folder
                  </button>
                </>
              ) : null}
            </div>
          ) : null}
        </span>
        <span className="status-bar__item" title="Errors">
          <Codicon name="error" size={14} />
          0
          <Codicon name="warning" size={14} style={{ marginLeft: 6 }} />
          0
        </span>
      </div>
      <div className="status-bar__group status-bar__group--right">
        <span className="status-bar__item status-bar__item--button">Ln 1, Col 1</span>
        <span className="status-bar__item status-bar__item--button">Spaces: 2</span>
        <span className="status-bar__item status-bar__item--button">UTF-8</span>
        <span className="status-bar__item status-bar__item--button">LF</span>
        <span className="status-bar__item status-bar__item--button">Plain Text</span>
        <span className="status-bar__item status-bar__item--button" title="Notifications">
          <Codicon name="bell" size={14} />
        </span>
      </div>
      <ConflictModal
        open={conflict.open}
        message={conflict.message}
        remoteVersion={remote?.version}
        localVersion={meta?.lastSyncedVersion}
        onPullThenPush={() => void handlePullThenPush()}
        onForcePush={() => void handleForcePush()}
        onCancel={() => setConflict({ open: false })}
      />
    </footer>
  );
}
