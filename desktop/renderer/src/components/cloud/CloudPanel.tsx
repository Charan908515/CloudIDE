import { useEffect, useState } from 'react';
import Codicon from '../common/Codicon';
import { useSyncStore } from '../../store/syncStore';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { toast } from '../../store/toastStore';
import { useOfflineQueueStore } from '../../store/offlineQueueStore';

type CloudProject = { id: string; name: string; modifiedTime?: string };

function relativeTime(iso: string): string {
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

export default function CloudPanel() {
  const bridge = window.cloudide;
  const signedIn = useSyncStore((s) => s.signedIn);
  const user = useSyncStore((s) => s.user);
  const diff = useSyncStore((s) => s.diff);
  const meta = useSyncStore((s) => s.meta);
  const remote = useSyncStore((s) => s.remote);
  const state = useSyncStore((s) => s.state);
  const setMeta = useSyncStore((s) => s.setMeta);
  const setRemote = useSyncStore((s) => s.setRemote);
  const setDiff = useSyncStore((s) => s.setDiff);
  const rootPath = useWorkspaceStore((s) => s.rootPath);

  const [projects, setProjects] = useState<CloudProject[]>([]);
  const [loadingProjects, setLoadingProjects] = useState(false);
  const [projectsError, setProjectsError] = useState('');
  const [cloningId, setCloningId] = useState<string | null>(null);
  const [pushingOrPulling, setPushingOrPulling] = useState(false);

  useEffect(() => {
    if (signedIn) void loadProjects();
    else { setProjects([]); setProjectsError(''); }
  }, [signedIn]);

  async function loadProjects() {
    if (!bridge?.sync) return;
    setLoadingProjects(true);
    setProjectsError('');
    try {
      const result = await bridge.sync.listCloudProjects();
      if ('error' in result) setProjectsError(result.error);
      else setProjects(result);
    } catch (e) {
      setProjectsError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoadingProjects(false);
    }
  }

  async function handleClone(project: CloudProject) {
    if (!bridge?.sync || !bridge?.fs) return;
    const pickedDir = await bridge.fs.pickDirectory();
    if (!pickedDir) return;
    const sep = pickedDir.includes('\\') ? '\\' : '/';
    const targetDir = `${pickedDir.replace(/[\\/]$/, '')}${sep}${project.name}`;
    setCloningId(project.id);
    try {
      const result = await bridge.sync.cloneCloudProject(project.id, project.name, targetDir);
      if (!result.ok) {
        toast.error('Clone failed', result.error ?? 'Unknown error');
        return;
      }
      toast.success('Project cloned', `"${project.name}" opened locally.`);
      window.dispatchEvent(new CustomEvent('cloudide:openSpecificFolder', { detail: targetDir }));
    } catch (e) {
      toast.error('Clone failed', e instanceof Error ? e.message : String(e));
    } finally {
      setCloningId(null);
    }
  }

  async function handleSignIn() {
    if (!bridge?.auth) return;
    const result = await bridge.auth.signIn();
    if (!result.ok) toast.error('Sign-in failed', result.error);
  }

  async function refreshAfter() {
    if (!rootPath || !bridge?.sync) return;
    setMeta(await bridge.sync.meta(rootPath));
    try { setRemote(await bridge.sync.remoteManifest(rootPath)); } catch { /* ignore */ }
    try { setDiff(await bridge.sync.diff(rootPath)); } catch { /* ignore */ }
  }

  async function handlePush() {
    if (!bridge?.sync || !rootPath) return;
    setPushingOrPulling(true);
    try {
      const projectName = rootPath.split(/[\\/]/).filter(Boolean).pop() ?? 'Project';
      const result = await bridge.sync.push(rootPath, projectName);
      if (!result.ok) window.alert(result.error ?? 'Push failed');
      await refreshAfter();
    } finally { setPushingOrPulling(false); }
  }

  async function handlePull() {
    if (!bridge?.sync || !rootPath) return;
    if (!window.confirm('Pull from Drive? This will overwrite local files.')) return;
    setPushingOrPulling(true);
    try {
      const result = await bridge.sync.pull(rootPath);
      if (!result.ok) window.alert(result.error ?? 'Pull failed');
      await refreshAfter();
    } finally { setPushingOrPulling(false); }
  }

  async function handleInitialize() {
    if (!bridge?.sync || !rootPath) return;
    const projectName = rootPath.split(/[\\/]/).filter(Boolean).pop() ?? 'Project';
    if (!window.confirm(`Initialize "${projectName}" on Google Drive?`)) return;
    const result = await bridge.sync.initialize(rootPath, projectName);
    if (!result.ok) window.alert(result.error ?? 'Initialize failed');
    await refreshAfter();
    void loadProjects(); // refresh the list
  }

  const inFlight = state === 'uploading' || state === 'downloading' || state === 'snapshotting' || state === 'extracting';
  const changed = diff ? diff.toUpload.length + diff.toDelete.length : 0;
  const remoteAhead = !!(remote && meta && remote.version > meta.lastSyncedVersion);

  if (!bridge?.sync) {
    return (
      <div className="panel-placeholder">
        <p>Cloud sync is only available in the Electron app.</p>
      </div>
    );
  }

  if (!signedIn) {
    return (
      <div className="cloud-panel">
        <div className="cloud-panel__sign-in">
          <div className="cloud-panel__sign-in-icon">
            <Codicon name="cloud" size={32} />
          </div>
          <p className="cloud-panel__sign-in-title">Sign in to Google</p>
          <p className="cloud-panel__sign-in-subtitle">Sync your projects to Google Drive — works like git push/pull.</p>
          <button type="button" className="cloud-panel__sign-in-btn" onClick={() => void handleSignIn()}>
            <Codicon name="account" size={14} />
            Sign in with Google
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="cloud-panel">
      {/* ── Current project sync status ───────────────────── */}
      {rootPath && (
        <div className="cloud-panel__section">
          <div className="cloud-panel__section-header">
            <span className="cloud-panel__section-title">Current Project</span>
            <button
              className="explorer__icon-button"
              type="button"
              title="Refresh"
              onClick={() => void refreshAfter()}
              disabled={inFlight || pushingOrPulling}
            >
              <Codicon name="refresh" size={13} spin={inFlight} />
            </button>
          </div>

          <div className="cloud-panel__current">
            <div className="cloud-panel__current-name">
              <Codicon name="folder" size={14} />
              {rootPath.split(/[\\/]/).filter(Boolean).pop()}
            </div>

            {/* Status row */}
            {!meta ? (
              <div className="cloud-panel__status cloud-panel__status--warn">
                <Codicon name="cloud-upload" size={13} />
                Not linked to Drive
              </div>
            ) : remoteAhead ? (
              <div className="cloud-panel__status cloud-panel__status--info">
                <Codicon name="cloud-download" size={13} />
                Remote ahead (v{remote!.version})
              </div>
            ) : changed > 0 ? (
              <div className="cloud-panel__status cloud-panel__status--changed">
                <Codicon name="circle-filled" size={10} />
                {diff!.toUpload.length} changed{diff!.toDelete.length > 0 ? `, ${diff!.toDelete.length} deleted` : ''}
              </div>
            ) : meta ? (
              <div className="cloud-panel__status cloud-panel__status--ok">
                <Codicon name="check" size={13} />
                v{meta.lastSyncedVersion} · synced
              </div>
            ) : null}

            {/* Changed files list */}
            {diff && diff.toUpload.length > 0 && (
              <div className="cloud-panel__changed-files">
                {diff.toUpload.slice(0, 8).map((f) => (
                  <div key={f} className="cloud-panel__changed-file">
                    <Codicon name="circle-filled" size={8} style={{ color: '#4ec9b0' }} />
                    <span>{f}</span>
                  </div>
                ))}
                {diff.toDelete.slice(0, 4).map((f) => (
                  <div key={f} className="cloud-panel__changed-file cloud-panel__changed-file--deleted">
                    <Codicon name="circle-filled" size={8} style={{ color: '#f44747' }} />
                    <span>{f}</span>
                  </div>
                ))}
                {(diff.toUpload.length > 8 || diff.toDelete.length > 4) && (
                  <div className="cloud-panel__changed-more">…and more</div>
                )}
              </div>
            )}

            {/* Action buttons */}
            <div className="cloud-panel__actions">
              {!meta ? (
                <button
                  type="button"
                  className="cloud-panel__action-btn cloud-panel__action-btn--primary"
                  onClick={() => void handleInitialize()}
                  disabled={inFlight || pushingOrPulling}
                >
                  <Codicon name="cloud-upload" size={13} />
                  Add to Drive
                </button>
              ) : (
                <>
                  <button
                    type="button"
                    className="cloud-panel__action-btn cloud-panel__action-btn--primary"
                    onClick={() => void handlePush()}
                    disabled={inFlight || pushingOrPulling || (changed === 0 && !remoteAhead)}
                    title="Push local changes to Drive"
                  >
                    <Codicon name={inFlight && state === 'uploading' ? 'sync' : 'cloud-upload'} size={13} spin={inFlight && state === 'uploading'} />
                    Push
                  </button>
                  <button
                    type="button"
                    className="cloud-panel__action-btn"
                    onClick={() => void handlePull()}
                    disabled={inFlight || pushingOrPulling}
                    title="Pull latest from Drive"
                  >
                    <Codicon name={inFlight && state === 'downloading' ? 'sync' : 'cloud-download'} size={13} spin={inFlight && state === 'downloading'} />
                    Pull
                  </button>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── Cloud projects list ───────────────────────────── */}
      <div className="cloud-panel__section">
        <div className="cloud-panel__section-header">
          <span className="cloud-panel__section-title">Drive Projects</span>
          <div style={{ display: 'flex', gap: 2 }}>
            <button
              className="explorer__icon-button"
              type="button"
              title="Refresh list"
              onClick={() => void loadProjects()}
              disabled={loadingProjects}
            >
              <Codicon name="refresh" size={13} spin={loadingProjects} />
            </button>
          </div>
        </div>

        {loadingProjects ? (
          <div className="cloud-panel__loading">
            <Codicon name="sync" size={16} spin />
            <span>Loading projects…</span>
          </div>
        ) : projectsError ? (
          <div className="cloud-panel__error">{projectsError}</div>
        ) : projects.length === 0 ? (
          <div className="cloud-panel__empty">
            <Codicon name="cloud" size={24} />
            <p>No Drive projects yet. Open a folder and click "Add to Drive" to start.</p>
          </div>
        ) : (
          <div className="cloud-panel__project-list">
            {projects.map((p) => {
              const isCurrentProject = meta?.driveFolderId === p.id;
              return (
                <div
                  key={p.id}
                  className={`cloud-panel__project-item ${isCurrentProject ? 'cloud-panel__project-item--active' : ''}`}
                >
                  <div className="cloud-panel__project-item-icon">
                    <Codicon name="repo" size={14} />
                  </div>
                  <div className="cloud-panel__project-item-info">
                    <span className="cloud-panel__project-item-name">{p.name}</span>
                    {p.modifiedTime && (
                      <span className="cloud-panel__project-item-time">
                        {relativeTime(p.modifiedTime)}
                      </span>
                    )}
                  </div>
                  {!isCurrentProject && (
                    <button
                      type="button"
                      className="cloud-panel__clone-btn"
                      title="Clone to local folder"
                      onClick={() => void handleClone(p)}
                      disabled={cloningId !== null}
                    >
                      {cloningId === p.id ? (
                        <Codicon name="sync" size={12} spin />
                      ) : (
                        <Codicon name="repo-clone" size={12} />
                      )}
                    </button>
                  )}
                  {isCurrentProject && (
                    <span className="cloud-panel__project-item-badge">open</span>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* ── Footer user info ──────────────────────────────── */}
      <div className="cloud-panel__footer">
        <Codicon name="account" size={13} />
        <span>{user?.email}</span>
      </div>
    </div>
  );
}
