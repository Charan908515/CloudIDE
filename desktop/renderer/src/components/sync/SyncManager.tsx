import { useEffect, useRef } from 'react';
import { useSyncStore } from '../../store/syncStore';
import { useWorkspaceStore } from '../../store/workspaceStore';

export default function SyncManager() {
  const bridge = window.cloudide;
  const setAuth = useSyncStore((s) => s.setAuth);
  const setState = useSyncStore((s) => s.setState);
  const setMeta = useSyncStore((s) => s.setMeta);
  const setRemote = useSyncStore((s) => s.setRemote);
  const setDiff = useSyncStore((s) => s.setDiff);
  const triggers = useSyncStore((s) => s.triggers);
  const intervalMinutes = useSyncStore((s) => s.intervalMinutes);
  const signedIn = useSyncStore((s) => s.signedIn);
  const meta = useSyncStore((s) => s.meta);
  const rootPath = useWorkspaceStore((s) => s.rootPath);
  const inFlightRef = useRef(false);

  useEffect(() => {
    if (!bridge?.auth) return;
    void bridge.auth.status().then((status) => setAuth(status.signedIn, status.user));
  }, [bridge, setAuth]);

  useEffect(() => {
    if (!bridge?.sync) return;
    return bridge.sync.onState((payload) => {
      setState(payload.state as SyncStateName, payload.message);
    });
  }, [bridge, setState]);

  // Refresh meta + remote when project changes.
  useEffect(() => {
    if (!bridge?.sync || !rootPath) {
      setMeta(null);
      setRemote(null);
      setDiff(null);
      return;
    }
    void bridge.sync.meta(rootPath).then(setMeta);
    if (signedIn) {
      void bridge.sync.remoteManifest(rootPath).then(setRemote).catch(() => undefined);
      void bridge.sync.diff(rootPath).then(setDiff).catch(() => undefined);
    }
  }, [bridge, rootPath, signedIn, setMeta, setRemote, setDiff]);

  // Recompute diff (debounced) on file system changes.
  useEffect(() => {
    if (!bridge?.fs || !bridge?.sync || !rootPath || !signedIn) return;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const remove = bridge.fs.onChange(() => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(async () => {
        try {
          const next = await bridge.sync.diff(rootPath);
          setDiff(next);
        } catch {
          /* ignore */
        }
      }, 600);
    });
    return () => {
      remove();
      if (timer) clearTimeout(timer);
    };
  }, [bridge, rootPath, signedIn, setDiff]);

  async function autoPush() {
    if (inFlightRef.current) return;
    if (!bridge?.sync || !rootPath || !signedIn || !meta) return;
    inFlightRef.current = true;
    try {
      const projectName = rootPath.split(/[\\/]/).filter(Boolean).pop() ?? 'Project';
      await bridge.sync.push(rootPath, projectName);
      setMeta(await bridge.sync.meta(rootPath));
      setRemote(await bridge.sync.remoteManifest(rootPath));
      setDiff(await bridge.sync.diff(rootPath));
    } finally {
      inFlightRef.current = false;
    }
  }

  // on-save trigger.
  useEffect(() => {
    if (!triggers.has('on-save')) return;
    const handler = () => void autoPush();
    window.addEventListener('cloudide:fileSaved', handler);
    return () => window.removeEventListener('cloudide:fileSaved', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [triggers, signedIn, rootPath, meta]);

  // on-run trigger.
  useEffect(() => {
    if (!triggers.has('on-run')) return;
    const handler = () => void autoPush();
    window.addEventListener('cloudide:fileRan', handler);
    return () => window.removeEventListener('cloudide:fileRan', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [triggers, signedIn, rootPath, meta]);

  // interval trigger.
  useEffect(() => {
    if (!triggers.has('interval') || !signedIn || !meta) return;
    const id = window.setInterval(
      () => void autoPush(),
      Math.max(1, intervalMinutes) * 60_000
    );
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [triggers, intervalMinutes, signedIn, rootPath, meta]);

  // Poll remote manifest every 60s so we know if another device pushed.
  useEffect(() => {
    if (!bridge?.sync || !signedIn || !rootPath || !meta) return;
    const id = window.setInterval(async () => {
      try {
        const remote = await bridge.sync.remoteManifest(rootPath);
        setRemote(remote);
        if (remote && remote.version > meta.lastSyncedVersion) {
          // Remote diverged — refresh diff so UI shows the "remote ahead" state.
          const next = await bridge.sync.diff(rootPath);
          setDiff(next);
        }
      } catch {
        /* ignore */
      }
    }, 60_000);
    return () => window.clearInterval(id);
  }, [bridge, signedIn, rootPath, meta, setRemote, setDiff]);

  return null;
}
