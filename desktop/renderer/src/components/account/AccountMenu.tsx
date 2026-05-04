import { useEffect, useRef, useState } from 'react';
import Codicon from '../common/Codicon';
import { useSyncStore } from '../../store/syncStore';

interface AccountMenuProps {
  open: boolean;
  onClose(): void;
  /** Pixel y-position of the activity bar button that triggered this menu. */
  anchorY: number;
}

export default function AccountMenu({ open, onClose, anchorY }: AccountMenuProps) {
  const bridge = window.cloudide;
  const signedIn = useSyncStore((s) => s.signedIn);
  const user = useSyncStore((s) => s.user);
  const setAuth = useSyncStore((s) => s.setAuth);
  const ref = useRef<HTMLDivElement | null>(null);
  const [busy, setBusy] = useState<'signin' | null>(null);

  useEffect(() => {
    if (!open) return;
    const click = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) onClose();
    };
    const key = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('mousedown', click);
    document.addEventListener('keydown', key);
    return () => {
      document.removeEventListener('mousedown', click);
      document.removeEventListener('keydown', key);
    };
  }, [open, onClose]);

  if (!open) return null;

  async function handleSignIn() {
    if (!bridge?.auth) return;
    setBusy('signin');
    try {
      const result = await bridge.auth.signIn();
      if (result.ok) setAuth(true, result.user);
      else window.alert(`Sign-in failed: ${result.error}`);
    } finally {
      setBusy(null);
      onClose();
    }
  }

  async function handleSignOut() {
    setAuth(false, null);
    onClose();
    // No actual revoke step on the desktop side — clearing in-memory state is enough.
    // To re-grant, the user picks the account again on next sign-in.
  }

  return (
    <div
      ref={ref}
      className="account-menu"
      style={{ top: anchorY }}
      role="menu"
    >
      <div className="account-menu__user">
        {signedIn && user ? (
          <>
            {user.picture ? (
              <img className="account-menu__avatar" src={user.picture} alt="" />
            ) : (
              <div className="account-menu__avatar account-menu__avatar--fallback">
                <Codicon name="account" size={20} />
              </div>
            )}
            <div className="account-menu__identity">
              <div className="account-menu__name">{user.name || user.email}</div>
              <div className="account-menu__email">{user.email}</div>
            </div>
          </>
        ) : (
          <>
            <div className="account-menu__avatar account-menu__avatar--fallback">
              <Codicon name="account" size={20} />
            </div>
            <div className="account-menu__identity">
              <div className="account-menu__name">Not signed in</div>
              <div className="account-menu__email">Sign in to sync with Google Drive</div>
            </div>
          </>
        )}
      </div>

      <div className="account-menu__sep" />

      {signedIn ? (
        <>
          <button
            type="button"
            className="account-menu__item"
            onClick={() => {
              window.dispatchEvent(new CustomEvent('cloudide:openSyncMenu'));
              onClose();
            }}
          >
            <Codicon name="cloud" size={14} />
            Sync settings
          </button>
          <button
            type="button"
            className="account-menu__item"
            onClick={() => {
              window.open('https://drive.google.com/drive/u/0/search?q=CloudIDE', '_blank');
              onClose();
            }}
          >
            <Codicon name="link-external" size={14} />
            Open Drive folder
          </button>
          <div className="account-menu__sep" />
          <button type="button" className="account-menu__item" onClick={() => void handleSignOut()}>
            <Codicon name="sign-out" size={14} />
            Sign out
          </button>
        </>
      ) : (
        <button
          type="button"
          className="account-menu__item account-menu__item--primary"
          onClick={() => void handleSignIn()}
          disabled={busy === 'signin'}
        >
          <Codicon name="sign-in" size={14} />
          {busy === 'signin' ? 'Opening browser…' : 'Sign in with Google'}
        </button>
      )}
    </div>
  );
}
