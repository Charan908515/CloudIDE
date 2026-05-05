import { useEffect } from 'react';
import { useOfflineQueueStore } from '../../store/offlineQueueStore';
import { toast } from '../../store/toastStore';

/**
 * Invisible component that:
 * 1. Flushes queued pushes when the network comes back online.
 * 2. Warns when the user goes offline.
 * Mount once inside AppShell.
 */
export default function OfflineQueueManager() {
  const queue = useOfflineQueueStore((s) => s.queue);
  const dequeue = useOfflineQueueStore((s) => s.dequeue);
  const bridge = window.cloudide;

  async function flushQueue(items: typeof queue) {
    if (!bridge?.sync || items.length === 0) return;
    for (const item of items) {
      try {
        const result = await bridge.sync.push(item.projectRoot, item.projectName);
        if (result.ok) {
          dequeue(item.id);
          toast.success(
            'Offline queue flushed',
            `"${item.projectName}" pushed successfully.`
          );
        } else {
          // Still offline or auth issue — leave in queue
          toast.warning('Sync retry failed', result.error ?? 'Will retry when back online.');
          break; // Stop processing further items if one fails
        }
      } catch (err) {
        toast.warning('Sync retry failed', err instanceof Error ? err.message : String(err));
        break;
      }
    }
  }

  // On mount: flush any surviving queue items if already online
  useEffect(() => {
    if (navigator.onLine && queue.length > 0) {
      void flushQueue(queue);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Listen to browser online/offline events
  useEffect(() => {
    function handleOnline() {
      const current = useOfflineQueueStore.getState().queue;
      if (current.length > 0) {
        toast.info(
          'Back online',
          `Syncing ${current.length} queued project${current.length > 1 ? 's' : ''}…`
        );
        void flushQueue(current);
      } else {
        toast.info('Back online', 'Network connection restored.');
      }
    }

    function handleOffline() {
      toast.warning('You are offline', 'Drive sync will resume when the connection is restored.');
    }

    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return null; // no UI
}
