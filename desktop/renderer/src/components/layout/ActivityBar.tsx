import { MutableRefObject, useRef } from 'react';
import Codicon from '../common/Codicon';

type ActivityPanel = 'files' | 'search' | 'git' | 'run' | 'extensions';

interface ActivityBarProps {
  activePanel: ActivityPanel;
  sidePanelOpen: boolean;
  changedFilesCount: number;
  syncState: 'idle' | 'syncing' | 'error';
  onSelect(panel: ActivityPanel): void;
  onAccount(anchorY: number): void;
  onSettings(): void;
  onSyncIcon(): void;
}

const items: Array<{ id: ActivityPanel; label: string; icon: string }> = [
  { id: 'files', label: 'Explorer (Ctrl+Shift+E)', icon: 'files' },
  { id: 'search', label: 'Search (Ctrl+Shift+F)', icon: 'search' },
  { id: 'git', label: 'Source Control (Ctrl+Shift+G)', icon: 'source-control' },
  { id: 'run', label: 'Run and Debug (Ctrl+Shift+D)', icon: 'debug-alt' },
];

export default function ActivityBar(props: ActivityBarProps) {
  const {
    activePanel, sidePanelOpen, changedFilesCount, syncState,
    onSelect, onAccount, onSettings, onSyncIcon,
  } = props;
  const accountButtonRef: MutableRefObject<HTMLButtonElement | null> = useRef(null);

  const handleAccount = () => {
    const rect = accountButtonRef.current?.getBoundingClientRect();
    onAccount(rect ? rect.top : 0);
  };

  return (
    <aside className="activity-bar">
      <div className="activity-bar__stack">
        {items.map((item) => {
          const active = sidePanelOpen && activePanel === item.id;
          return (
            <button
              key={item.id}
              className={`activity-bar__button ${active ? 'is-active' : ''}`}
              onClick={() => onSelect(item.id)}
              title={item.label}
              type="button"
            >
              <Codicon name={item.icon} size={24} />
              {item.id === 'git' && changedFilesCount > 0 ? (
                <span className="activity-bar__badge">{changedFilesCount}</span>
              ) : null}
            </button>
          );
        })}
      </div>

      <div className="activity-bar__stack">
        <button
          className={`activity-bar__button ${activePanel === 'extensions' && sidePanelOpen ? 'is-active' : ''}`}
          type="button"
          title="Extensions"
          onClick={() => onSelect('extensions')}
        >
          <Codicon name="extensions" size={24} />
        </button>
        <button
          className="activity-bar__button"
          type="button"
          title={`Cloud Sync (${syncState})`}
          onClick={onSyncIcon}
        >
          <Codicon
            name={syncState === 'error' ? 'cloud-offline' : syncState === 'syncing' ? 'sync' : 'cloud'}
            size={22}
            spin={syncState === 'syncing'}
          />
        </button>
        <button
          ref={accountButtonRef}
          className="activity-bar__button"
          type="button"
          title="Accounts"
          onClick={handleAccount}
        >
          <Codicon name="account" size={24} />
        </button>
        <button
          className="activity-bar__button"
          type="button"
          title="Manage settings"
          onClick={onSettings}
        >
          <Codicon name="settings-gear" size={24} />
        </button>
      </div>
    </aside>
  );
}
