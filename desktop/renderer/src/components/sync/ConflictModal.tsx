import Codicon from '../common/Codicon';

interface ConflictModalProps {
  open: boolean;
  remoteVersion?: number;
  localVersion?: number;
  message?: string;
  onPullThenPush(): void;
  onForcePush(): void;
  onCancel(): void;
}

export default function ConflictModal({
  open,
  remoteVersion,
  localVersion,
  message,
  onPullThenPush,
  onForcePush,
  onCancel,
}: ConflictModalProps) {
  if (!open) return null;

  return (
    <div className="modal-backdrop" onClick={onCancel}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal__header">
          <Codicon name="warning" size={18} style={{ color: '#cc7833' }} />
          <h2>Sync conflict</h2>
        </div>
        <div className="modal__body">
          <p>
            {message ?? `Remote is at version ${remoteVersion ?? '?'}; your last sync was version ${localVersion ?? '?'}.`}
          </p>
          <p>Another device pushed changes since your last sync. Pick how to resolve:</p>
          <dl className="modal__choices">
            <dt>Pull, then push</dt>
            <dd>
              Safe. Downloads remote changes first, merges with your edits on disk
              (last-write-wins per file), then uploads. Your local edits to files the
              remote also changed will be overwritten.
            </dd>
            <dt>Force push (overwrite remote)</dt>
            <dd>
              Destructive. Uploads your local state and replaces what's on Drive. Other
              devices will lose any unsynced work next time they pull.
            </dd>
          </dl>
        </div>
        <div className="modal__footer">
          <button type="button" className="modal__button" onClick={onCancel}>
            Cancel
          </button>
          <button type="button" className="modal__button" onClick={onPullThenPush}>
            <Codicon name="cloud-download" size={14} />
            Pull, then push
          </button>
          <button
            type="button"
            className="modal__button modal__button--danger"
            onClick={onForcePush}
          >
            <Codicon name="warning" size={14} />
            Force push
          </button>
        </div>
      </div>
    </div>
  );
}
