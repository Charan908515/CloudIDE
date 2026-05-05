import { useEffect, useState } from 'react';
import Codicon from '../common/Codicon';

export default function CloudProjectModal() {
  const bridge = window.cloudide;
  const [open, setOpen] = useState(false);
  const [projects, setProjects] = useState<Array<{ id: string; name: string; modifiedTime?: string }>>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [cloningId, setCloningId] = useState<string | null>(null);

  useEffect(() => {
    function handleOpen() {
      setOpen(true);
      void loadProjects();
    }
    window.addEventListener('cloudide:cloneCloudProject', handleOpen);
    return () => window.removeEventListener('cloudide:cloneCloudProject', handleOpen);
  }, []);

  async function loadProjects() {
    if (!bridge?.sync) return;
    setLoading(true);
    setError('');
    try {
      const result = await bridge.sync.listCloudProjects();
      if ('error' in result) {
        setError(result.error);
      } else {
        setProjects(result);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }

  async function handleClone(project: { id: string; name: string }) {
    if (!bridge?.sync || !bridge?.fs) return;
    
    const pickedDir = await bridge.fs.pickDirectory();
    if (!pickedDir) return;
    
    // We append the project name to the picked directory to create a new folder for it
    const separator = pickedDir.includes('\\') ? '\\' : '/';
    const targetDir = `${pickedDir.replace(/[\\/]$/, '')}${separator}${project.name}`;
    
    setCloningId(project.id);
    try {
      const result = await bridge.sync.cloneCloudProject(project.id, project.name, targetDir);
      if (!result.ok) {
        window.alert(result.error ?? 'Clone failed');
      } else {
        setOpen(false);
        // Dispatch event to open the newly cloned folder
        window.dispatchEvent(new CustomEvent('cloudide:openSpecificFolder', { detail: targetDir }));
      }
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err));
    } finally {
      setCloningId(null);
    }
  }

  if (!open) return null;

  return (
    <div className="modal-backdrop" onClick={() => !cloningId && setOpen(false)}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        onClick={(e) => e.stopPropagation()}
        style={{ width: 500 }}
      >
        <div className="modal__header">
          <Codicon name="cloud-download" size={18} />
          <h2>Clone Cloud Project</h2>
        </div>
        <div className="modal__body" style={{ minHeight: 200, maxHeight: 400, overflowY: 'auto' }}>
          {loading ? (
            <div style={{ textAlign: 'center', padding: 20 }}>
              <Codicon name="sync" size={24} spin />
              <p style={{ marginTop: 10 }}>Loading projects from Google Drive...</p>
            </div>
          ) : error ? (
            <div style={{ color: '#cc3333', padding: 10, background: '#331111', borderRadius: 4 }}>
              {error}
            </div>
          ) : projects.length === 0 ? (
            <p>No CloudIDE projects found on your Google Drive.</p>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {projects.map((p) => (
                <div 
                  key={p.id} 
                  style={{ 
                    display: 'flex', 
                    alignItems: 'center', 
                    justifyContent: 'space-between',
                    padding: '8px 12px',
                    background: 'rgba(255,255,255,0.05)',
                    borderRadius: 4
                  }}
                >
                  <div style={{ display: 'flex', flexDirection: 'column' }}>
                    <strong>{p.name}</strong>
                    <small style={{ opacity: 0.6 }}>
                      {p.modifiedTime ? new Date(p.modifiedTime).toLocaleString() : 'Unknown date'}
                    </small>
                  </div>
                  <button
                    type="button"
                    className="modal__button"
                    onClick={() => void handleClone(p)}
                    disabled={cloningId !== null}
                  >
                    {cloningId === p.id ? (
                      <Codicon name="sync" size={14} spin />
                    ) : (
                      <Codicon name="repo-clone" size={14} />
                    )}
                    {cloningId === p.id ? 'Cloning...' : 'Clone'}
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="modal__footer">
          <button 
            type="button" 
            className="modal__button" 
            onClick={() => setOpen(false)}
            disabled={cloningId !== null}
          >
            Cancel
          </button>
          <button 
            type="button" 
            className="modal__button" 
            onClick={() => void loadProjects()}
            disabled={loading || cloningId !== null}
          >
            <Codicon name="refresh" size={14} />
            Refresh
          </button>
        </div>
      </div>
    </div>
  );
}
