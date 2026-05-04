import OutputPanel from '../execution/OutputPanel';
import TerminalPanel from '../terminal/TerminalPanel';
import { useState } from 'react';
import Codicon from '../common/Codicon';

type BottomTab = 'problems' | 'output' | 'terminal';

const tabs: Array<{ id: BottomTab; label: string }> = [
  { id: 'problems', label: 'PROBLEMS' },
  { id: 'output', label: 'OUTPUT' },
  { id: 'terminal', label: 'TERMINAL' },
];

interface BottomPanelProps {
  open: boolean;
  onToggle(): void;
}

export default function BottomPanel({ open, onToggle }: BottomPanelProps) {
  const [activeTab, setActiveTab] = useState<BottomTab>('terminal');

  if (!open) {
    // collapsed — render an empty placeholder so grid still allocates the area
    return <section className="bottom-panel" style={{ display: 'none' }} />;
  }

  return (
    <section className="bottom-panel">
      <div className="bottom-panel__tabs">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            className={`bottom-panel__tab ${activeTab === tab.id ? 'is-active' : ''}`}
            onClick={() => setActiveTab(tab.id)}
            type="button"
          >
            {tab.label}
          </button>
        ))}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
          <button
            type="button"
            className="explorer__icon-button"
            onClick={onToggle}
            title="Hide Panel"
          >
            <Codicon name="chevron-down" size={14} />
          </button>
        </div>
      </div>
      <div className="bottom-panel__body">
        <div className="bottom-panel__pane" style={{ display: activeTab === 'terminal' ? 'block' : 'none' }}>
          <TerminalPanel />
        </div>
        <div className="bottom-panel__pane" style={{ display: activeTab === 'output' ? 'block' : 'none' }}>
          <OutputPanel />
        </div>
        {activeTab === 'problems' ? (
          <div className="panel-placeholder">
            <p>No problems have been detected in the workspace.</p>
          </div>
        ) : null}
      </div>
    </section>
  );
}
