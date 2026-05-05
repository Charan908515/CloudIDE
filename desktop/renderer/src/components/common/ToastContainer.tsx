import { useEffect, useRef } from 'react';
import { useToastStore, Toast, ToastType } from '../../store/toastStore';
import Codicon from './Codicon';

const ICONS: Record<ToastType, { name: string; color: string }> = {
  success: { name: 'check-all', color: '#4ec9b0' },
  error:   { name: 'error',     color: '#f48771' },
  warning: { name: 'warning',   color: '#cca700' },
  info:    { name: 'info',      color: '#4daafc' },
};

function ToastItem({ toast, onDismiss }: { toast: Toast; onDismiss(): void }) {
  const progressRef = useRef<HTMLDivElement>(null);
  const icon = ICONS[toast.type];

  useEffect(() => {
    if (toast.duration <= 0 || !progressRef.current) return;
    const el = progressRef.current;
    el.style.transition = 'none';
    el.style.width = '100%';
    // Force reflow
    void el.getBoundingClientRect().width;
    el.style.transition = `width ${toast.duration}ms linear`;
    el.style.width = '0%';
  }, [toast.duration]);

  return (
    <div className={`toast toast--${toast.type}`} role="alert">
      <div className="toast__icon">
        <Codicon name={icon.name} size={16} style={{ color: icon.color }} />
      </div>
      <div className="toast__body">
        <div className="toast__title">{toast.title}</div>
        {toast.message && <div className="toast__message">{toast.message}</div>}
      </div>
      <button
        type="button"
        className="toast__close"
        onClick={onDismiss}
        aria-label="Dismiss"
      >
        <Codicon name="close" size={14} />
      </button>
      {toast.duration > 0 && (
        <div className="toast__progress-track">
          <div ref={progressRef} className="toast__progress-bar" />
        </div>
      )}
    </div>
  );
}

export default function ToastContainer() {
  const { toasts, dismiss } = useToastStore();
  return (
    <div className="toast-container" aria-live="polite">
      {toasts.map((t) => (
        <ToastItem key={t.id} toast={t} onDismiss={() => dismiss(t.id)} />
      ))}
    </div>
  );
}
