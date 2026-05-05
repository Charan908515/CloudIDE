import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export interface PendingPush {
  id: string;
  projectRoot: string;
  projectName: string;
  addedAt: number;
}

interface OfflineQueueState {
  queue: PendingPush[];
  enqueue(projectRoot: string, projectName: string): void;
  dequeue(id: string): void;
  clear(): void;
}

export const useOfflineQueueStore = create<OfflineQueueState>()(
  persist(
    (set, get) => ({
      queue: [],
      enqueue: (projectRoot, projectName) => {
        const existing = get().queue.find((p) => p.projectRoot === projectRoot);
        if (existing) return; // already queued
        set((s) => ({
          queue: [
            ...s.queue,
            {
              id: Math.random().toString(36).slice(2, 10),
              projectRoot,
              projectName,
              addedAt: Date.now(),
            },
          ],
        }));
      },
      dequeue: (id) => set((s) => ({ queue: s.queue.filter((p) => p.id !== id) })),
      clear: () => set({ queue: [] }),
    }),
    { name: 'cloudide-offline-queue' }
  )
);
