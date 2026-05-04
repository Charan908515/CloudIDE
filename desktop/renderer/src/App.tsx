import AppShell from './components/layout/AppShell';
import BridgeNotice from './components/auth/BridgeNotice';
import ErrorBoundary from './components/auth/ErrorBoundary';
import SyncManager from './components/sync/SyncManager';

export default function App() {
  return (
    <ErrorBoundary>
      <BridgeNotice />
      <SyncManager />
      <AppShell />
    </ErrorBoundary>
  );
}
