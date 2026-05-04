import { useEditorStore } from '../../store/editorStore';
import Codicon from '../common/Codicon';

function getFileIconName(name: string): string {
  if (/\.(tsx?|jsx?|mjs|cjs)$/i.test(name)) return 'symbol-class';
  if (/\.json$/i.test(name)) return 'json';
  if (/\.(md|markdown)$/i.test(name)) return 'markdown';
  if (/\.(html?|xml)$/i.test(name)) return 'code';
  if (/\.(css|scss|sass|less)$/i.test(name)) return 'symbol-color';
  if (/\.(png|jpe?g|gif|svg|webp|ico)$/i.test(name)) return 'file-media';
  if (/\.(sh|bash|zsh|ps1|cmd|bat)$/i.test(name)) return 'terminal';
  if (/\.(py|go|rs|java|kt|c|cpp|h|hpp)$/i.test(name)) return 'symbol-method';
  return 'file';
}

export default function EditorTabs() {
  const { tabs, activePath, setActivePath, closeTab } = useEditorStore();

  function handleClose(path: string) {
    const tab = tabs.find((item) => item.path === path);
    if (!tab) return;
    if (tab.content !== tab.savedContent && !window.confirm(`Close ${tab.name} without saving?`)) {
      return;
    }
    closeTab(path);
  }

  if (tabs.length === 0) {
    return <div className="editor-area__tabs" />;
  }

  return (
    <div className="editor-area__tabs">
      {tabs.map((tab) => {
        const dirty = tab.content !== tab.savedContent;
        const active = activePath === tab.path;
        return (
          <button
            key={tab.path}
            className={`editor-area__tab ${active ? 'is-active' : ''}`}
            onClick={() => setActivePath(tab.path)}
            type="button"
          >
            <Codicon name={getFileIconName(tab.name)} size={14} style={{ color: '#cccccc' }} />
            <span>{tab.name}</span>
            <span className={`editor-area__dirty ${dirty ? 'is-visible' : ''}`} />
            <span
              role="button"
              aria-label={`Close ${tab.name}`}
              className="editor-area__close"
              onClick={(event) => {
                event.stopPropagation();
                handleClose(tab.path);
              }}
            >
              <Codicon name={dirty ? 'circle-filled' : 'close'} size={12} />
            </span>
          </button>
        );
      })}
    </div>
  );
}
