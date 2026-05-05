import { useState } from 'react';
import Codicon from '../common/Codicon';
import { FONT_FAMILY_OPTIONS, useSettingsStore } from '../../store/settingsStore';
import { useSyncStore, AutoSyncTrigger } from '../../store/syncStore';

interface SettingsModalProps {
  open: boolean;
  onClose(): void;
}

type Tab = 'editor' | 'sync' | 'about';

export default function SettingsModal({ open, onClose }: SettingsModalProps) {
  const [tab, setTab] = useState<Tab>('editor');
  if (!open) return null;

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="settings-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <div className="settings-modal__header">
          <Codicon name="settings-gear" size={16} />
          <h2>Settings</h2>
          <button type="button" className="settings-modal__close" onClick={onClose} title="Close">
            <Codicon name="close" size={14} />
          </button>
        </div>
        <div className="settings-modal__body">
          <nav className="settings-modal__tabs">
            <TabButton label="Editor" id="editor" tab={tab} onClick={setTab} />
            <TabButton label="Sync" id="sync" tab={tab} onClick={setTab} />
            <TabButton label="About" id="about" tab={tab} onClick={setTab} />
          </nav>
          <div className="settings-modal__content">
            {tab === 'editor' ? <EditorSettings /> : null}
            {tab === 'sync' ? <SyncSettings /> : null}
            {tab === 'about' ? <AboutSettings /> : null}
          </div>
        </div>
      </div>
    </div>
  );
}

function TabButton({
  label, id, tab, onClick,
}: { label: string; id: Tab; tab: Tab; onClick: (id: Tab) => void }) {
  return (
    <button
      type="button"
      className={`settings-modal__tab ${tab === id ? 'is-active' : ''}`}
      onClick={() => onClick(id)}
    >
      {label}
    </button>
  );
}

function EditorSettings() {
  const editor = useSettingsStore((s) => s.editor);
  const set = useSettingsStore((s) => s.setEditor);

  return (
    <div className="settings-section">
      <Field label="Font family" hint="Pick a monospace font installed on your system.">
        <select
          className="settings-input"
          value={editor.fontFamily}
          onChange={(e) => set('fontFamily', e.target.value)}
        >
          {FONT_FAMILY_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </Field>

      <Field label="Font size" hint={`${editor.fontSize}px`}>
        <input
          type="range"
          min={10} max={28} step={1}
          value={editor.fontSize}
          onChange={(e) => set('fontSize', Number(e.target.value))}
        />
      </Field>

      <Field label="Tab size" hint={`${editor.tabSize} spaces`}>
        <input
          type="range"
          min={1} max={8} step={1}
          value={editor.tabSize}
          onChange={(e) => set('tabSize', Number(e.target.value))}
        />
      </Field>

      <ToggleField
        label="Font ligatures"
        hint="Renders programming ligatures like => and != as connected glyphs."
        checked={editor.fontLigatures}
        onChange={(v) => set('fontLigatures', v)}
      />

      <ToggleField
        label="Word wrap"
        hint="Wrap long lines instead of horizontal scrolling."
        checked={editor.wordWrap === 'on'}
        onChange={(v) => set('wordWrap', v ? 'on' : 'off')}
      />

      <ToggleField
        label="Minimap"
        hint="Shows a thumbnail of the file on the right edge."
        checked={editor.minimap}
        onChange={(v) => set('minimap', v)}
      />

      <Field label="Line numbers">
        <select
          className="settings-input"
          value={editor.lineNumbers}
          onChange={(e) => set('lineNumbers', e.target.value as 'on' | 'off' | 'relative')}
        >
          <option value="on">Show</option>
          <option value="relative">Relative</option>
          <option value="off">Hide</option>
        </select>
      </Field>

      <ToggleField
        label="Sticky scroll"
        hint="Pin the current scope (function, class) to the top of the editor."
        checked={editor.stickyScroll}
        onChange={(v) => set('stickyScroll', v)}
      />

      <Field label="Render whitespace">
        <select
          className="settings-input"
          value={editor.renderWhitespace}
          onChange={(e) => set('renderWhitespace', e.target.value as 'none' | 'boundary' | 'selection' | 'trailing' | 'all')}
        >
          <option value="none">None</option>
          <option value="boundary">Boundary</option>
          <option value="selection">In selection</option>
          <option value="trailing">Trailing</option>
          <option value="all">All</option>
        </select>
      </Field>
    </div>
  );
}

function SyncSettings() {
  const triggers = useSyncStore((s) => s.triggers);
  const intervalMinutes = useSyncStore((s) => s.intervalMinutes);
  const autoSyncOnCommit = useSyncStore((s) => s.autoSyncOnCommit);
  const toggleTrigger = useSyncStore((s) => s.toggleTrigger);
  const setIntervalMinutes = useSyncStore((s) => s.setIntervalMinutes);
  const setAutoSyncOnCommit = useSyncStore((s) => s.setAutoSyncOnCommit);
  const autoPullOnOpen = useSettingsStore((s) => s.ui.autoPullOnOpen);
  const setUi = useSettingsStore((s) => s.setUi);

  const triggerList: Array<{ id: AutoSyncTrigger; label: string; hint: string }> = [
    { id: 'on-save', label: 'After every save', hint: 'Push to Drive whenever a file is saved (Ctrl+S).' },
    { id: 'on-run', label: 'After running a file', hint: 'Push when you click Run.' },
    { id: 'interval', label: 'On a schedule', hint: 'Push every N minutes if there are changes.' },
  ];

  return (
    <div className="settings-section">
      <h3 className="settings-heading">Auto-push triggers</h3>
      {triggerList.map((t) => (
        <ToggleField
          key={t.id}
          label={t.label}
          hint={t.hint}
          checked={triggers.has(t.id)}
          onChange={() => toggleTrigger(t.id)}
        />
      ))}

      {triggers.has('interval') ? (
        <Field label="Interval" hint="Minutes between auto-pushes when there are changes.">
          <input
            type="number"
            min={1} max={120}
            className="settings-input settings-input--narrow"
            value={intervalMinutes}
            onChange={(e) => setIntervalMinutes(Math.max(1, Number(e.target.value) || 1))}
          />
        </Field>
      ) : null}

      <h3 className="settings-heading">On open</h3>
      <ToggleField
        label="Auto-pull when opening a project"
        hint="If the remote is ahead when you open a folder, automatically pull the latest."
        checked={autoPullOnOpen}
        onChange={(v) => setUi('autoPullOnOpen', v)}
      />

      <h3 className="settings-heading">Version Control</h3>
      <ToggleField
        label="Auto-sync to Drive on Git commit"
        hint="Automatically push changes to Drive whenever you commit via Source Control."
        checked={autoSyncOnCommit}
        onChange={(v) => setAutoSyncOnCommit(v)}
      />
    </div>
  );
}

function AboutSettings() {
  return (
    <div className="settings-section settings-about">
      <div className="settings-about__brand">
        <Codicon name="cloud" size={32} />
        <div>
          <h3>CloudIDE</h3>
          <div className="settings-about__version">version 1.0.0</div>
        </div>
      </div>
      <p>A VS Code-style desktop IDE with end-to-end Google Drive project sync.</p>
      <ul className="settings-about__shortcuts">
        <li><kbd>Ctrl+S</kbd> — save active file</li>
        <li><kbd>Ctrl+B</kbd> — toggle sidebar</li>
        <li><kbd>Ctrl+J</kbd> — toggle bottom panel</li>
        <li><kbd>Ctrl+`</kbd> — open terminal</li>
        <li><kbd>Ctrl+Tab</kbd> / <kbd>Ctrl+Shift+Tab</kbd> — cycle tabs</li>
      </ul>
    </div>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="settings-field">
      <div className="settings-field__label">
        <span>{label}</span>
        {hint ? <span className="settings-field__hint">{hint}</span> : null}
      </div>
      <div className="settings-field__control">{children}</div>
    </div>
  );
}

function ToggleField({
  label, hint, checked, onChange,
}: { label: string; hint?: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div className="settings-field">
      <div className="settings-field__label">
        <span>{label}</span>
        {hint ? <span className="settings-field__hint">{hint}</span> : null}
      </div>
      <label className="settings-toggle">
        <input
          type="checkbox"
          checked={checked}
          onChange={(e) => onChange(e.target.checked)}
        />
        <span className="settings-toggle__slider" />
      </label>
    </div>
  );
}
