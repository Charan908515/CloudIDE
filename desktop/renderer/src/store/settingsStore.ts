import { create } from 'zustand';

interface EditorSettings {
  fontSize: number;
  fontFamily: string;
  fontLigatures: boolean;
  wordWrap: 'on' | 'off';
  minimap: boolean;
  lineNumbers: 'on' | 'off' | 'relative';
  tabSize: number;
  renderWhitespace: 'none' | 'boundary' | 'selection' | 'trailing' | 'all';
  stickyScroll: boolean;
}

interface UiSettings {
  autoPullOnOpen: boolean;
}

interface SettingsState {
  editor: EditorSettings;
  ui: UiSettings;
  setEditor: <K extends keyof EditorSettings>(key: K, value: EditorSettings[K]) => void;
  setUi: <K extends keyof UiSettings>(key: K, value: UiSettings[K]) => void;
}

const defaults: { editor: EditorSettings; ui: UiSettings } = {
  editor: {
    fontSize: 14,
    fontFamily: "'Cascadia Code', 'Consolas', 'Courier New', monospace",
    fontLigatures: true,
    wordWrap: 'off',
    minimap: true,
    lineNumbers: 'on',
    tabSize: 2,
    renderWhitespace: 'selection',
    stickyScroll: true,
  },
  ui: {
    autoPullOnOpen: false,
  },
};

const STORAGE_KEY = 'cloudide-settings';

function load(): { editor: EditorSettings; ui: UiSettings } {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return defaults;
    const parsed = JSON.parse(raw) as Partial<{ editor: Partial<EditorSettings>; ui: Partial<UiSettings> }>;
    return {
      editor: { ...defaults.editor, ...(parsed.editor ?? {}) },
      ui: { ...defaults.ui, ...(parsed.ui ?? {}) },
    };
  } catch {
    return defaults;
  }
}

function save(state: { editor: EditorSettings; ui: UiSettings }) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ editor: state.editor, ui: state.ui }));
  } catch {
    /* ignore */
  }
}

const initial = load();

export const useSettingsStore = create<SettingsState>((set, get) => ({
  editor: initial.editor,
  ui: initial.ui,
  setEditor: (key, value) => {
    const next = { editor: { ...get().editor, [key]: value }, ui: get().ui };
    save(next);
    set({ editor: next.editor });
  },
  setUi: (key, value) => {
    const next = { editor: get().editor, ui: { ...get().ui, [key]: value } };
    save(next);
    set({ ui: next.ui });
  },
}));

export const FONT_FAMILY_OPTIONS = [
  { label: 'Cascadia Code', value: "'Cascadia Code', 'Consolas', monospace" },
  { label: 'JetBrains Mono', value: "'JetBrains Mono', 'Consolas', monospace" },
  { label: 'Fira Code', value: "'Fira Code', 'Consolas', monospace" },
  { label: 'Consolas', value: "'Consolas', 'Courier New', monospace" },
  { label: 'Courier New', value: "'Courier New', monospace" },
  { label: 'Monospace', value: 'monospace' },
];
