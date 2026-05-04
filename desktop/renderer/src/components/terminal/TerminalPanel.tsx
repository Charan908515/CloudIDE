import { useEffect, useRef, useState } from 'react';
import { useTerminalsStore, ShellKind } from '../../store/terminalsStore';
import TerminalInstance from './TerminalInstance';
import Codicon from '../common/Codicon';

const isWindows = typeof navigator !== 'undefined' && /Windows/i.test(navigator.userAgent);

const SHELL_OPTIONS: Array<{ id: ShellKind; label: string }> = isWindows
  ? [
      { id: 'powershell', label: 'PowerShell' },
      { id: 'pwsh', label: 'pwsh' },
      { id: 'cmd', label: 'Command Prompt' },
    ]
  : [{ id: 'bash', label: 'bash' }];

export default function TerminalPanel() {
  const bridge = window.cloudide;
  const terminals = useTerminalsStore((s) => s.terminals);
  const activeId = useTerminalsStore((s) => s.activeId);
  const add = useTerminalsStore((s) => s.add);
  const remove = useTerminalsStore((s) => s.remove);
  const setActive = useTerminalsStore((s) => s.setActive);
  const [showShellMenu, setShowShellMenu] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);
  const initRef = useRef(false);

  // Spawn one terminal on first mount.
  useEffect(() => {
    if (initRef.current) return;
    initRef.current = true;
    if (terminals.length === 0) {
      add(isWindows ? 'powershell' : 'bash');
    }
  }, [terminals.length, add]);

  useEffect(() => {
    if (!showShellMenu) return;
    function clickAway(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setShowShellMenu(false);
      }
    }
    document.addEventListener('mousedown', clickAway);
    return () => document.removeEventListener('mousedown', clickAway);
  }, [showShellMenu]);

  if (!bridge?.pty) {
    return (
      <div className="panel-placeholder">
        <p>Terminal bridge unavailable. Run via Electron (npm run dev), not the bare Vite URL.</p>
      </div>
    );
  }

  return (
    <div className="terminal-shell">
      <div className="terminal-shell__layout">
        <div className="terminal-shell__list">
          {terminals.map((t, idx) => (
            <div
              key={t.id}
              className={`terminal-shell__item ${activeId === t.id ? 'is-active' : ''}`}
              onClick={() => setActive(t.id)}
              title={t.title}
            >
              <Codicon name="terminal" size={14} />
              <span className="terminal-shell__item-label">{idx + 1}: {t.title}</span>
              <button
                type="button"
                className="terminal-shell__item-close"
                onClick={(e) => {
                  e.stopPropagation();
                  remove(t.id);
                }}
                title="Kill Terminal"
              >
                <Codicon name="trash" size={12} />
              </button>
            </div>
          ))}

          <div className="terminal-shell__add" ref={menuRef}>
            <button
              type="button"
              className="terminal-shell__add-button"
              onClick={() => add(isWindows ? 'powershell' : 'bash')}
              title="New Terminal"
            >
              <Codicon name="add" size={14} />
            </button>
            <button
              type="button"
              className="terminal-shell__add-dropdown"
              onClick={() => setShowShellMenu((v) => !v)}
              title="Select Shell"
            >
              <Codicon name="chevron-down" size={10} />
            </button>
            {showShellMenu ? (
              <div className="terminal-shell__shell-menu">
                {SHELL_OPTIONS.map((opt) => (
                  <button
                    key={opt.id}
                    type="button"
                    className="terminal-shell__shell-menu-item"
                    onClick={() => {
                      add(opt.id);
                      setShowShellMenu(false);
                    }}
                  >
                    {opt.label}
                  </button>
                ))}
              </div>
            ) : null}
          </div>
        </div>

        <div className="terminal-shell__view">
          {terminals.length === 0 ? (
            <div className="panel-placeholder">No terminals open.</div>
          ) : (
            terminals.map((t) => (
              <TerminalInstance
                key={t.id}
                id={t.id}
                shellKind={t.shellKind}
                visible={t.id === activeId}
                onExit={() => remove(t.id)}
              />
            ))
          )}
        </div>
      </div>
    </div>
  );
}
