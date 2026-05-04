import { create } from 'zustand';
import { FileNode } from '@shared/types';

interface WorkspaceState {
  rootPath: string | null;
  tree: FileNode | null;
  expanded: Set<string>;
  childrenCache: Map<string, FileNode[]>;
  setRootPath: (path: string | null) => void;
  setTree: (tree: FileNode | null) => void;
  setExpanded: (path: string, value: boolean) => void;
  setChildren: (path: string, children: FileNode[]) => void;
  resetTreeState: () => void;
}

export const useWorkspaceStore = create<WorkspaceState>((set) => ({
  rootPath: null,
  tree: null,
  expanded: new Set<string>(),
  childrenCache: new Map<string, FileNode[]>(),
  setRootPath: (path) => set({ rootPath: path }),
  setTree: (tree) => set({ tree }),
  setExpanded: (path, value) =>
    set((state) => {
      const next = new Set(state.expanded);
      if (value) next.add(path);
      else next.delete(path);
      return { expanded: next };
    }),
  setChildren: (path, children) =>
    set((state) => {
      const next = new Map(state.childrenCache);
      next.set(path, children);
      return { childrenCache: next };
    }),
  resetTreeState: () =>
    set({ tree: null, expanded: new Set<string>(), childrenCache: new Map<string, FileNode[]>() }),
}));
