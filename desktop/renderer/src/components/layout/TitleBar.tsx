import { useEffect, useRef, useState } from 'react';
import { useEditorStore } from '../../store/editorStore';
import { useExecutionStore } from '../../store/executionStore';

type Action = () => void;
type MenuEntry =
  | { kind: 'item'; label: string; shortcut?: string; action: Action; disabled?: boolean }
  | { kind: 'separator' };

interface MenuDef {
  label: string;
  items: MenuEntry[];
}

function dispatch(name: string, detail?: unknown) {
  window.dispatchEvent(new CustomEvent(name, { detail }));
}

function newFileAction() {
  // Open an unsaved scratch buffer.
  const ts = Date.now();
  const path = `untitled:Untitled-${ts}`;
  useEditorStore.getState().openFile({ path, name: `Untitled-${ts}`, content: '', savedContent: '' });
}

async function saveActiveAction() {
  const { tabs, activePath, markSaved } = useEditorStore.getState();
  const tab = tabs.find((t) => t.path === activePath);
  if (!tab) return;
  if (tab.path.startsWith('untitled:')) {
    window.alert('Save As is not implemented yet — paste the contents into a new file via the explorer.');
    return;
  }
  if (window.cloudide?.fs) {
    await window.cloudide.fs.writeFile(tab.path, tab.content);
    markSaved(tab.path, tab.content);
  }
}

function closeActiveAction() {
  const { tabs, activePath, closeTab } = useEditorStore.getState();
  const tab = tabs.find((t) => t.path === activePath);
  if (!tab) return;
  if (tab.content !== tab.savedContent && !window.confirm(`Close ${tab.name} without saving?`)) return;
  closeTab(tab.path);
}

function buildMenus(): MenuDef[] {
  return [
    {
      label: 'File',
      items: [
        { kind: 'item', label: 'New File', shortcut: 'Ctrl+N', action: newFileAction },
        { kind: 'separator' },
        { kind: 'item', label: 'Open Folder...', shortcut: 'Ctrl+K Ctrl+O', action: () => dispatch('cloudide:openFolder') },
        { kind: 'item', label: 'Clone Cloud Project...', action: () => dispatch('cloudide:cloneCloudProject') },
        { kind: 'separator' },
        { kind: 'item', label: 'Save', shortcut: 'Ctrl+S', action: () => void saveActiveAction() },
        { kind: 'item', label: 'Close Editor', shortcut: 'Ctrl+W', action: closeActiveAction },
        { kind: 'separator' },
        { kind: 'item', label: 'Settings', shortcut: 'Ctrl+,', action: () => dispatch('cloudide:openSettings') },
        { kind: 'separator' },
        { kind: 'item', label: 'Exit', action: () => window.close() },
      ],
    },
    {
      label: 'Edit',
      items: [
        { kind: 'item', label: 'Undo', shortcut: 'Ctrl+Z', action: () => document.execCommand('undo') },
        { kind: 'item', label: 'Redo', shortcut: 'Ctrl+Y', action: () => document.execCommand('redo') },
        { kind: 'separator' },
        { kind: 'item', label: 'Cut', shortcut: 'Ctrl+X', action: () => document.execCommand('cut') },
        { kind: 'item', label: 'Copy', shortcut: 'Ctrl+C', action: () => document.execCommand('copy') },
        { kind: 'item', label: 'Paste', shortcut: 'Ctrl+V', action: () => document.execCommand('paste') },
        { kind: 'separator' },
        { kind: 'item', label: 'Find', shortcut: 'Ctrl+F', action: () => dispatch('cloudide:editorAction', 'actions.find') },
        { kind: 'item', label: 'Replace', shortcut: 'Ctrl+H', action: () => dispatch('cloudide:editorAction', 'editor.action.startFindReplaceAction') },
      ],
    },
    {
      label: 'Selection',
      items: [
        { kind: 'item', label: 'Select All', shortcut: 'Ctrl+A', action: () => dispatch('cloudide:editorAction', 'editor.action.selectAll') },
        { kind: 'item', label: 'Expand Selection', shortcut: 'Shift+Alt+Right', action: () => dispatch('cloudide:editorAction', 'editor.action.smartSelect.expand') },
        { kind: 'item', label: 'Shrink Selection', shortcut: 'Shift+Alt+Left', action: () => dispatch('cloudide:editorAction', 'editor.action.smartSelect.shrink') },
        { kind: 'separator' },
        { kind: 'item', label: 'Copy Line Down', shortcut: 'Shift+Alt+Down', action: () => dispatch('cloudide:editorAction', 'editor.action.copyLinesDownAction') },
        { kind: 'item', label: 'Copy Line Up', shortcut: 'Shift+Alt+Up', action: () => dispatch('cloudide:editorAction', 'editor.action.copyLinesUpAction') },
      ],
    },
    {
      label: 'View',
      items: [
        { kind: 'item', label: 'Toggle Side Bar', shortcut: 'Ctrl+B', action: () => dispatch('cloudide:toggleSidebar') },
        { kind: 'item', label: 'Toggle Panel', shortcut: 'Ctrl+J', action: () => dispatch('cloudide:togglePanel') },
        { kind: 'item', label: 'Toggle Terminal', shortcut: 'Ctrl+`', action: () => dispatch('cloudide:toggleTerminal') },
        { kind: 'separator' },
        { kind: 'item', label: 'Explorer', shortcut: 'Ctrl+Shift+E', action: () => dispatch('cloudide:activityPanel', 'files') },
        { kind: 'item', label: 'Search', shortcut: 'Ctrl+Shift+F', action: () => dispatch('cloudide:activityPanel', 'search') },
        { kind: 'item', label: 'Source Control', shortcut: 'Ctrl+Shift+G', action: () => dispatch('cloudide:activityPanel', 'git') },
        { kind: 'item', label: 'Run and Debug', shortcut: 'Ctrl+Shift+D', action: () => dispatch('cloudide:activityPanel', 'run') },
      ],
    },
    {
      label: 'Go',
      items: [
        { kind: 'item', label: 'Go to File...', shortcut: 'Ctrl+P', action: () => dispatch('cloudide:openQuickFile') },
        { kind: 'item', label: 'Go to Line/Column...', shortcut: 'Ctrl+G', action: () => dispatch('cloudide:editorAction', 'editor.action.gotoLine') },
      ],
    },
    {
      label: 'Run',
      items: [
        { kind: 'item', label: 'Run File', action: () => void useExecutionStore.getState().runCurrentFile() },
        { kind: 'item', label: 'Stop', action: () => void useExecutionStore.getState().killRun() },
      ],
    },
    {
      label: 'Terminal',
      items: [
        { kind: 'item', label: 'New Terminal', shortcut: 'Ctrl+Shift+`', action: () => dispatch('cloudide:toggleTerminal') },
      ],
    },
    {
      label: 'Help',
      items: [
        {
          kind: 'item',
          label: 'About CloudIDE',
          action: () => window.alert('CloudIDE — VS Code-style desktop shell.'),
        },
      ],
    },
  ];
}

export default function TitleBar({ title = 'CloudIDE' }: { title?: string }) {
  const [openIndex, setOpenIndex] = useState<number | null>(null);
  const menuBarRef = useRef<HTMLDivElement | null>(null);
  const menus = buildMenus();

  useEffect(() => {
    if (openIndex === null) return;
    function handleClickAway(event: MouseEvent) {
      if (menuBarRef.current && !menuBarRef.current.contains(event.target as Node)) {
        setOpenIndex(null);
      }
    }
    function handleKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpenIndex(null);
    }
    document.addEventListener('mousedown', handleClickAway);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('mousedown', handleClickAway);
      document.removeEventListener('keydown', handleKey);
    };
  }, [openIndex]);

  function runItem(item: Extract<MenuEntry, { kind: 'item' }>) {
    setOpenIndex(null);
    try {
      item.action();
    } catch (error) {
      console.error('Menu action failed:', error);
    }
  }

  return (
    <header className="title-bar">
      <div className="title-bar__menu" ref={menuBarRef}>
        {menus.map((menu, index) => {
          const open = openIndex === index;
          return (
            <div key={menu.label} className="title-bar__menu-wrapper">
              <button
                type="button"
                className={`title-bar__menu-item ${open ? 'is-open' : ''}`}
                onClick={() => setOpenIndex(open ? null : index)}
                onMouseEnter={() => {
                  if (openIndex !== null && openIndex !== index) setOpenIndex(index);
                }}
              >
                {menu.label}
              </button>
              {open ? (
                <div className="title-bar__dropdown" role="menu">
                  {menu.items.map((entry, entryIndex) => {
                    if (entry.kind === 'separator') {
                      return <div key={`sep-${entryIndex}`} className="title-bar__dropdown-sep" />;
                    }
                    return (
                      <button
                        key={entry.label}
                        type="button"
                        role="menuitem"
                        className="title-bar__dropdown-item"
                        onClick={() => runItem(entry)}
                        disabled={entry.disabled}
                      >
                        <span>{entry.label}</span>
                        {entry.shortcut ? <span className="title-bar__dropdown-shortcut">{entry.shortcut}</span> : null}
                      </button>
                    );
                  })}
                </div>
              ) : null}
            </div>
          );
        })}
      </div>
      <div className="title-bar__title">{title}</div>
      <div className="title-bar__spacer" />
    </header>
  );
}
