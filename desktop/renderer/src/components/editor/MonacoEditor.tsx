import Editor, { BeforeMount, OnChange, OnMount, loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';
import { useEffect, useMemo, useRef } from 'react';
import { useEditorStore } from '../../store/editorStore';
import { useSettingsStore } from '../../store/settingsStore';

// Pin @monaco-editor/react to the locally bundled monaco-editor so versions never drift.
// Without this it loads a different Monaco from CDN and ITextModel methods drift between
// the two copies (e.g. "getCustomLineHeightsDecorations is not a function").
loader.config({ monaco });

function buildEditorOptions(settings: ReturnType<typeof useSettingsStore.getState>['editor']) {
  return {
    fontFamily: settings.fontFamily,
    fontSize: settings.fontSize,
    fontLigatures: settings.fontLigatures,
    lineHeight: Math.round(settings.fontSize * 1.4),
    minimap: { enabled: settings.minimap, scale: 1, renderCharacters: true },
    scrollBeyondLastLine: true,
    wordWrap: settings.wordWrap,
    lineNumbers: settings.lineNumbers,
    tabSize: settings.tabSize,
    renderWhitespace: settings.renderWhitespace,
    renderLineHighlight: 'line',
    bracketPairColorization: { enabled: true },
    guides: { bracketPairs: true, indentation: true, highlightActiveIndentation: true },
    smoothScrolling: false,
    cursorBlinking: 'blink',
    cursorStyle: 'line',
    cursorWidth: 1,
    padding: { top: 8, bottom: 8 },
    stickyScroll: { enabled: settings.stickyScroll },
    'semanticHighlighting.enabled': true,
    roundedSelection: false,
    automaticLayout: true,
  } as monaco.editor.IStandaloneEditorConstructionOptions;
}

const LANG_BY_EXT: Record<string, string> = {
  ts: 'typescript', tsx: 'typescript',
  js: 'javascript', jsx: 'javascript', mjs: 'javascript', cjs: 'javascript',
  json: 'json',
  md: 'markdown', markdown: 'markdown',
  html: 'html', htm: 'html', xml: 'xml',
  css: 'css', scss: 'scss', less: 'less', sass: 'scss',
  py: 'python',
  go: 'go',
  rs: 'rust',
  java: 'java',
  kt: 'kotlin',
  c: 'c', h: 'c',
  cpp: 'cpp', cc: 'cpp', cxx: 'cpp', hpp: 'cpp',
  cs: 'csharp',
  rb: 'ruby',
  php: 'php',
  swift: 'swift',
  sh: 'shell', bash: 'shell', zsh: 'shell',
  ps1: 'powershell',
  cmd: 'bat', bat: 'bat',
  yml: 'yaml', yaml: 'yaml',
  toml: 'ini', ini: 'ini', cfg: 'ini', conf: 'ini',
  sql: 'sql',
  dockerfile: 'dockerfile',
};

function detectLanguage(name: string): string {
  if (/^Dockerfile$/i.test(name)) return 'dockerfile';
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  return LANG_BY_EXT[ext] ?? 'plaintext';
}

export default function MonacoEditor() {
  const bridge = window.cloudide;
  const { tabs, activePath, updateContent, markSaved, cycleTabs, closeTab } = useEditorStore();
  const activeTab = useMemo(() => tabs.find((tab) => tab.path === activePath) ?? null, [activePath, tabs]);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const settings = useSettingsStore((s) => s.editor);
  const editorOptions = useMemo(() => buildEditorOptions(settings), [settings]);

  const handleBeforeMount: BeforeMount = (monacoInstance) => {
    monacoInstance.editor.defineTheme('cloudide-dark', {
      base: 'vs-dark',
      inherit: true,
      rules: [
        { token: 'comment', foreground: '6A9955', fontStyle: 'italic' },
        { token: 'string', foreground: 'CE9178' },
        { token: 'string.escape', foreground: 'D7BA7D' },
        { token: 'number', foreground: 'B5CEA8' },
        { token: 'regexp', foreground: 'D16969' },
        { token: 'keyword', foreground: '569CD6' },
        { token: 'keyword.control', foreground: 'C586C0' },
        { token: 'storage.type', foreground: '569CD6' },
        { token: 'type', foreground: '4EC9B0' },
        { token: 'type.identifier', foreground: '4EC9B0' },
        { token: 'entity.name.type', foreground: '4EC9B0' },
        { token: 'entity.name.function', foreground: 'DCDCAA' },
        { token: 'function', foreground: 'DCDCAA' },
        { token: 'support.function', foreground: 'DCDCAA' },
        { token: 'variable', foreground: '9CDCFE' },
        { token: 'variable.parameter', foreground: '9CDCFE' },
        { token: 'constant', foreground: '4FC1FF' },
        { token: 'tag', foreground: '569CD6' },
        { token: 'attribute.name', foreground: '9CDCFE' },
        { token: 'attribute.value', foreground: 'CE9178' },
        { token: 'delimiter', foreground: 'D4D4D4' },
        { token: 'operator', foreground: 'D4D4D4' },
      ],
      colors: {
        'editor.background': '#1E1E1E',
        'editor.foreground': '#D4D4D4',
        'editorLineNumber.foreground': '#858585',
        'editorLineNumber.activeForeground': '#C6C6C6',
        'editor.selectionBackground': '#264F78',
        'editor.inactiveSelectionBackground': '#3A3D41',
        'editor.lineHighlightBackground': '#2A2A2A',
        'editorCursor.foreground': '#AEAFAD',
        'editorWhitespace.foreground': '#3B3B3B',
        'editorIndentGuide.background1': '#404040',
        'editorIndentGuide.activeBackground1': '#707070',
        'editor.findMatchBackground': '#515C6A',
        'editor.findMatchHighlightBackground': '#EA5C0055',
        'editorBracketMatch.background': '#0064001A',
        'editorBracketMatch.border': '#888888',
        'editorGutter.background': '#1E1E1E',
        'scrollbarSlider.background': '#79797966',
        'scrollbarSlider.hoverBackground': '#646464B3',
        'scrollbarSlider.activeBackground': '#BFBFBF66',
      },
    });
  };

  const handleMount: OnMount = (editorInstance) => {
    editorRef.current = editorInstance;
  };

  const handleChange: OnChange = (value) => {
    if (activeTab && typeof value === 'string') {
      updateContent(activeTab.path, value);
    }
  };

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (!activeTab) return;

      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        if (!bridge?.fs) return;
        if (activeTab.path.startsWith('untitled:')) return;
        void bridge.fs.writeFile(activeTab.path, activeTab.content).then(() => {
          markSaved(activeTab.path, activeTab.content);
          window.dispatchEvent(new CustomEvent('cloudide:fileSaved', { detail: activeTab.path }));
        });
      }

      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'w') {
        event.preventDefault();
        const dirty = activeTab.content !== activeTab.savedContent;
        if (!dirty || window.confirm(`Close ${activeTab.name} without saving?`)) {
          closeTab(activeTab.path);
        }
      }

      if ((event.ctrlKey || event.metaKey) && event.key === 'Tab') {
        event.preventDefault();
        cycleTabs(event.shiftKey ? -1 : 1);
      }
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [activeTab, bridge, closeTab, cycleTabs, markSaved]);

  useEffect(() => {
    function handleEditorAction(event: Event) {
      const action = (event as CustomEvent<string>).detail;
      const editor = editorRef.current;
      if (!editor || !action) return;
      editor.focus();
      try {
        const trigger = editor.getAction(action);
        if (trigger) {
          void trigger.run();
        } else {
          editor.trigger('menu', action, undefined);
        }
      } catch (error) {
        console.warn('Editor action failed:', action, error);
      }
    }
    window.addEventListener('cloudide:editorAction', handleEditorAction);
    return () => window.removeEventListener('cloudide:editorAction', handleEditorAction);
  }, []);

  useEffect(() => {
    function handleReveal(event: Event) {
      const detail = (event as CustomEvent<{ path: string; line: number; column: number }>).detail;
      const editor = editorRef.current;
      if (!editor || !detail) return;
      const apply = () => {
        editor.revealLineInCenter(detail.line);
        editor.setPosition({ lineNumber: detail.line, column: detail.column });
        editor.focus();
      };
      // The model may still be loading if the file was just opened.
      if (editor.getModel()) {
        apply();
      } else {
        const id = window.setInterval(() => {
          if (editor.getModel()) {
            window.clearInterval(id);
            apply();
          }
        }, 30);
        window.setTimeout(() => window.clearInterval(id), 1500);
      }
    }
    window.addEventListener('cloudide:revealEditorPosition', handleReveal);
    return () => window.removeEventListener('cloudide:revealEditorPosition', handleReveal);
  }, []);

  if (!activeTab) {
    return (
      <div className="editor-area__canvas">
        <div className="welcome-card">
          <span className="welcome-card__label">Get Started</span>
          <h1>CloudIDE</h1>
          <p>
            Open a folder from the Explorer or use <kbd>File &gt; Open Folder…</kbd> to begin.
            Save with <kbd>Ctrl+S</kbd>, toggle the sidebar with <kbd>Ctrl+B</kbd>, the panel with <kbd>Ctrl+J</kbd>.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="editor-area__canvas">
      <Editor
        path={activeTab.path}
        defaultLanguage={detectLanguage(activeTab.name)}
        defaultValue={activeTab.content}
        theme="cloudide-dark"
        beforeMount={handleBeforeMount}
        onMount={handleMount}
        onChange={handleChange}
        options={editorOptions}
        loading={<div className="panel-placeholder">Loading editor…</div>}
        keepCurrentModel
      />
    </div>
  );
}
