import { create } from 'zustand';

export type ShellKind = 'powershell' | 'pwsh' | 'cmd' | 'bash';

export interface TerminalTab {
  id: string;
  shellKind: ShellKind;
  title: string;
}

interface TerminalsState {
  terminals: TerminalTab[];
  activeId: string | null;
  add: (shellKind: ShellKind) => string;
  remove: (id: string) => void;
  setActive: (id: string) => void;
  setTitle: (id: string, title: string) => void;
}

let counter = 1;

export const useTerminalsStore = create<TerminalsState>((set) => ({
  terminals: [],
  activeId: null,
  add: (shellKind) => {
    const id = `term-${Date.now()}-${counter++}`;
    const title = `${shellKind === 'cmd' ? 'cmd' : shellKind === 'bash' ? 'bash' : shellKind === 'pwsh' ? 'pwsh' : 'PowerShell'}`;
    set((state) => ({
      terminals: [...state.terminals, { id, shellKind, title }],
      activeId: id,
    }));
    return id;
  },
  remove: (id) =>
    set((state) => {
      const idx = state.terminals.findIndex((t) => t.id === id);
      const next = state.terminals.filter((t) => t.id !== id);
      let activeId = state.activeId;
      if (activeId === id) {
        const fallbackIdx = Math.min(idx, next.length - 1);
        activeId = next[fallbackIdx]?.id ?? null;
      }
      return { terminals: next, activeId };
    }),
  setActive: (id) => set({ activeId: id }),
  setTitle: (id, title) =>
    set((state) => ({
      terminals: state.terminals.map((t) => (t.id === id ? { ...t, title } : t)),
    })),
}));
