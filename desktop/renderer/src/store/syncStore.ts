import { create } from 'zustand';

export type AutoSyncTrigger = 'manual' | 'on-save' | 'on-run' | 'interval';

interface SyncState {
  signedIn: boolean;
  user: AuthUser | null;
  state: SyncStateName | 'idle';
  message: string;
  meta: ProjectSyncMeta | null;
  remote: DriveManifest | null;
  diff: SyncDiffResult | null;
  triggers: Set<AutoSyncTrigger>;
  intervalMinutes: number;
  autoSyncOnCommit: boolean;
  setAuth: (signedIn: boolean, user: AuthUser | null) => void;
  setState: (state: SyncStateName | 'idle', message?: string) => void;
  setMeta: (meta: ProjectSyncMeta | null) => void;
  setRemote: (remote: DriveManifest | null) => void;
  setDiff: (diff: SyncDiffResult | null) => void;
  toggleTrigger: (trigger: AutoSyncTrigger) => void;
  setIntervalMinutes: (minutes: number) => void;
  setAutoSyncOnCommit: (value: boolean) => void;
}

const STORAGE_KEY = 'cloudide-sync-prefs';

function loadPrefs(): { triggers: Set<AutoSyncTrigger>; intervalMinutes: number; autoSyncOnCommit: boolean } {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { triggers: new Set(['manual']), intervalMinutes: 10, autoSyncOnCommit: false };
    const parsed = JSON.parse(raw) as { triggers?: AutoSyncTrigger[]; intervalMinutes?: number; autoSyncOnCommit?: boolean };
    return {
      triggers: new Set(parsed.triggers ?? ['manual']),
      intervalMinutes: parsed.intervalMinutes ?? 10,
      autoSyncOnCommit: parsed.autoSyncOnCommit ?? false,
    };
  } catch {
    return { triggers: new Set(['manual']), intervalMinutes: 10, autoSyncOnCommit: false };
  }
}

function savePrefs(triggers: Set<AutoSyncTrigger>, intervalMinutes: number, autoSyncOnCommit: boolean) {
  try {
    localStorage.setItem(
      STORAGE_KEY,
      JSON.stringify({ triggers: Array.from(triggers), intervalMinutes, autoSyncOnCommit })
    );
  } catch {
    /* ignore */
  }
}

const initialPrefs = loadPrefs();

export const useSyncStore = create<SyncState>((set, get) => ({
  signedIn: false,
  user: null,
  state: 'idle',
  message: '',
  meta: null,
  remote: null,
  diff: null,
  triggers: initialPrefs.triggers,
  intervalMinutes: initialPrefs.intervalMinutes,
  autoSyncOnCommit: initialPrefs.autoSyncOnCommit,
  setAuth: (signedIn, user) => set({ signedIn, user }),
  setState: (state, message = '') => set({ state, message }),
  setMeta: (meta) => set({ meta }),
  setRemote: (remote) => set({ remote }),
  setDiff: (diff) => set({ diff }),
  toggleTrigger: (trigger) => {
    const next = new Set(get().triggers);
    if (next.has(trigger)) next.delete(trigger);
    else next.add(trigger);
    if (next.size === 0) next.add('manual');
    savePrefs(next, get().intervalMinutes, get().autoSyncOnCommit);
    set({ triggers: next });
  },
  setIntervalMinutes: (minutes) => {
    savePrefs(get().triggers, minutes, get().autoSyncOnCommit);
    set({ intervalMinutes: minutes });
  },
  setAutoSyncOnCommit: (value) => {
    savePrefs(get().triggers, get().intervalMinutes, value);
    set({ autoSyncOnCommit: value });
  },
}));
