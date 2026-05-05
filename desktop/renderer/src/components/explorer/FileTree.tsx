import { useCallback, useEffect, useRef, useState } from 'react';
import FileTreeItem from './FileTreeItem';
import Codicon from '../common/Codicon';
import { useWorkspaceStore } from '../../store/workspaceStore';

function getBaseName(targetPath: string) {
  const normalized = targetPath.replace(/\\/g, '/');
  const parts = normalized.split('/').filter(Boolean);
  return parts[parts.length - 1] || targetPath;
}

function joinPath(rootPath: string, childName: string) {
  const separator = rootPath.includes('\\') ? '\\' : '/';
  return `${rootPath.replace(/[\\/]$/, '')}${separator}${childName}`;
}

export default function FileTree() {
  const bridge = window.cloudide;
  const rootPath = useWorkspaceStore((s) => s.rootPath);
  const tree = useWorkspaceStore((s) => s.tree);
  const setWorkspaceRoot = useWorkspaceStore((s) => s.setRootPath);
  const setTree = useWorkspaceStore((s) => s.setTree);
  const resetTreeState = useWorkspaceStore((s) => s.resetTreeState);

  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const reloadDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const loadTree = useCallback(
    async (targetRoot: string, isInitialOpen = false) => {
      if (!bridge?.fs) return;
      setLoading(true);
      try {
        const nextTree = await bridge.fs.readDir(targetRoot);
        if (isInitialOpen) {
          resetTreeState();
        }
        setWorkspaceRoot(targetRoot);
        setTree(nextTree);
        setError('');
      } catch (loadError) {
        setError(loadError instanceof Error ? loadError.message : 'Failed to load files');
      } finally {
        setLoading(false);
      }
    },
    [bridge, setWorkspaceRoot, setTree, resetTreeState]
  );

  // On (re)mount, if a folder is open but the tree state has been cleared, refetch.
  useEffect(() => {
    if (rootPath && !tree && bridge?.fs && !loading) {
      void loadTree(rootPath);
    }
    // we want this to fire only when rootPath/tree existence change, not on every render
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rootPath, tree]);

  // Debounced refresh on fs:change events.
  useEffect(() => {
    if (!bridge?.fs || !rootPath) return;
    const removeListener = bridge.fs.onChange(() => {
      if (reloadDebounceRef.current) clearTimeout(reloadDebounceRef.current);
      reloadDebounceRef.current = setTimeout(() => {
        void bridge.fs.readDir(rootPath).then(setTree).catch(() => undefined);
      }, 120);
    });
    return () => {
      removeListener();
      if (reloadDebounceRef.current) clearTimeout(reloadDebounceRef.current);
    };
  }, [bridge, rootPath, setTree]);

  async function handleOpenFolder() {
    if (!bridge?.fs) return;
    const pickedPath = await bridge.fs.pickDirectory();
    if (!pickedPath) return;

    if (rootPath) {
      try { await bridge.fs.stopWatch(rootPath); } catch { /* ignore */ }
    }
    await loadTree(pickedPath, true);
    try { await bridge.fs.startWatch(pickedPath); } catch { /* ignore */ }
  }

  useEffect(() => {
    function handle() {
      void handleOpenFolder();
    }
    function handleOpenSpecific(e: Event) {
      const targetPath = (e as CustomEvent<string>).detail;
      if (!targetPath || !bridge?.fs) return;
      if (rootPath) {
        try { void bridge.fs.stopWatch(rootPath); } catch { /* ignore */ }
      }
      void loadTree(targetPath, true).then(() => {
        try { void bridge.fs.startWatch(targetPath); } catch { /* ignore */ }
      });
    }

    window.addEventListener('cloudide:openFolder', handle);
    window.addEventListener('cloudide:openSpecificFolder', handleOpenSpecific);
    return () => {
      window.removeEventListener('cloudide:openFolder', handle);
      window.removeEventListener('cloudide:openSpecificFolder', handleOpenSpecific);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rootPath, bridge]);

  async function handleCreateProject() {
    if (!bridge?.fs || !rootPath) return;
    const projectName = window.prompt('Project name');
    if (!projectName) return;
    const projectPath = joinPath(rootPath, projectName);
    await bridge.fs.createFolder(projectPath);
    await loadTree(rootPath);
  }

  async function handleRefresh() {
    if (!rootPath) return;
    await loadTree(rootPath);
  }

  if (!bridge?.fs) {
    return (
      <div className="panel-placeholder">
        <p>Explorer is available only in the Electron desktop window.</p>
      </div>
    );
  }

  return (
    <div className="explorer">
      {!rootPath || error ? (
        <div className="explorer__welcome">
          <p className="explorer__welcome-text">
            {error || 'You have not yet opened a folder.'}
          </p>
          <button className="explorer__primary-button" onClick={() => void handleOpenFolder()} type="button">
            Open Folder
          </button>
        </div>
      ) : (
        <>
          <div className="explorer__section-header">
            <span className="explorer__section-title">{getBaseName(rootPath)}</span>
            <div className="explorer__actions">
              <button
                className="explorer__icon-button"
                onClick={() => void handleCreateProject()}
                title="New Folder"
                type="button"
              >
                <Codicon name="new-folder" size={14} />
              </button>
              <button
                className="explorer__icon-button"
                onClick={() => void handleRefresh()}
                title="Refresh Explorer"
                type="button"
              >
                <Codicon name="refresh" size={14} spin={loading} />
              </button>
              <button
                className="explorer__icon-button"
                onClick={() => void handleOpenFolder()}
                title="Open Folder"
                type="button"
              >
                <Codicon name="folder-opened" size={14} />
              </button>
            </div>
          </div>

          <div className="file-tree">
            {tree?.children?.map((child) => (
              <FileTreeItem key={child.path} node={child} depth={0} />
            )) ?? null}
            {!tree && loading ? <div className="file-tree__loading">Loading…</div> : null}
          </div>
        </>
      )}
    </div>
  );
}
