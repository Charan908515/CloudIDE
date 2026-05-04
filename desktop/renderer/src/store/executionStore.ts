import { create } from 'zustand';

interface ExecutionState {
  activeSessionId: string | null;
  isRunning: boolean;
  output: string;
  runCurrentFile: () => Promise<void>;
  appendOutput: (chunk: string) => void;
  finishRun: () => void;
  killRun: () => Promise<void>;
}

export const useExecutionStore = create<ExecutionState>((set, get) => ({
  activeSessionId: null,
  isRunning: false,
  output: '',
  runCurrentFile: async () => {
    const bridge = window.cloudide;
    if (!bridge?.exec) {
      return;
    }

    const editorState = await import('./editorStore');
    const { activePath } = editorState.useEditorStore.getState();
    if (!activePath) {
      return;
    }

    const { sessionId } = await bridge.exec.run(activePath);
    set({ activeSessionId: sessionId, isRunning: true, output: '' });
  },
  appendOutput: (chunk) => set((state) => ({ output: state.output + chunk })),
  finishRun: () => set({ isRunning: false }),
  killRun: async () => {
    const bridge = window.cloudide;
    const { activeSessionId } = get();
    if (!activeSessionId || !bridge?.exec) {
      return;
    }
    await bridge.exec.kill(activeSessionId);
    set({ isRunning: false });
  },
}));
