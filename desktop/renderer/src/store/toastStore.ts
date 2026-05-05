import { create } from 'zustand';

export type ToastType = 'info' | 'success' | 'error' | 'warning';

export interface Toast {
  id: string;
  type: ToastType;
  title: string;
  message?: string;
  duration: number; // ms, 0 = sticky
}

interface ToastStoreState {
  toasts: Toast[];
  push(toast: Omit<Toast, 'id'>): string;
  dismiss(id: string): void;
}

export const useToastStore = create<ToastStoreState>((set) => ({
  toasts: [],
  push: (t) => {
    const id = Math.random().toString(36).slice(2, 10);
    set((s) => ({ toasts: [...s.toasts, { ...t, id }] }));
    if (t.duration > 0) {
      setTimeout(() => set((s) => ({ toasts: s.toasts.filter((x) => x.id !== id) })), t.duration);
    }
    return id;
  },
  dismiss: (id) => set((s) => ({ toasts: s.toasts.filter((x) => x.id !== id) })),
}));

// Convenience API — call anywhere without a React hook
export const toast = {
  info(title: string, message?: string) {
    return useToastStore.getState().push({ type: 'info', title, message, duration: 4000 });
  },
  success(title: string, message?: string) {
    return useToastStore.getState().push({ type: 'success', title, message, duration: 4000 });
  },
  error(title: string, message?: string) {
    return useToastStore.getState().push({ type: 'error', title, message, duration: 0 });
  },
  warning(title: string, message?: string) {
    return useToastStore.getState().push({ type: 'warning', title, message, duration: 6000 });
  },
};
