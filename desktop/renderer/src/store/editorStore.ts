import { create } from 'zustand';

interface EditorTab {
  path: string;
  name: string;
  content: string;
  savedContent: string;
}

interface EditorState {
  tabs: EditorTab[];
  activePath: string | null;
  openFile: (tab: EditorTab) => void;
  setActivePath: (path: string) => void;
  updateContent: (path: string, content: string) => void;
  markSaved: (path: string, content: string) => void;
  closeTab: (path: string) => void;
  cycleTabs: (direction: 1 | -1) => void;
}

export const useEditorStore = create<EditorState>((set, get) => ({
  tabs: [],
  activePath: null,
  openFile: (tab) =>
    set((state) => {
      const existing = state.tabs.find((item) => item.path === tab.path);
      if (existing) {
        return { activePath: tab.path };
      }

      return {
        tabs: [...state.tabs, tab],
        activePath: tab.path,
      };
    }),
  setActivePath: (path) => set({ activePath: path }),
  updateContent: (path, content) =>
    set((state) => ({
      tabs: state.tabs.map((tab) => (tab.path === path ? { ...tab, content } : tab)),
    })),
  markSaved: (path, content) =>
    set((state) => ({
      tabs: state.tabs.map((tab) => (tab.path === path ? { ...tab, content, savedContent: content } : tab)),
    })),
  closeTab: (path) =>
    set((state) => {
      const nextTabs = state.tabs.filter((tab) => tab.path !== path);
      const nextActive =
        state.activePath === path ? nextTabs[Math.max(0, nextTabs.length - 1)]?.path ?? null : state.activePath;
      return {
        tabs: nextTabs,
        activePath: nextActive,
      };
    }),
  cycleTabs: (direction) => {
    const { tabs, activePath } = get();
    if (tabs.length <= 1 || !activePath) {
      return;
    }

    const currentIndex = tabs.findIndex((tab) => tab.path === activePath);
    const nextIndex = (currentIndex + direction + tabs.length) % tabs.length;
    set({ activePath: tabs[nextIndex].path });
  },
}));
