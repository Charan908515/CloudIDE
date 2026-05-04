import { useCallback, useEffect, useState } from 'react';
import ActivityBar from './ActivityBar';
import BottomPanel from './BottomPanel';
import EditorArea from './EditorArea';
import SidePanel from './SidePanel';
import StatusBar from './StatusBar';
import TitleBar from './TitleBar';
import Splitter from './Splitter';
import AccountMenu from '../account/AccountMenu';
import SettingsModal from '../settings/SettingsModal';

type ActivityPanel = 'files' | 'search' | 'git' | 'run' | 'extensions';

const SIDEBAR_MIN = 170;
const SIDEBAR_MAX = 600;
const BOTTOM_MIN = 80;
const BOTTOM_MAX_PERCENT = 0.75;

export default function AppShell() {
  const [activePanel, setActivePanel] = useState<ActivityPanel>('files');
  const [sidePanelOpen, setSidePanelOpen] = useState(true);
  const [bottomPanelOpen, setBottomPanelOpen] = useState(true);
  const [sideBarWidth, setSideBarWidth] = useState(260);
  const [bottomPanelHeight, setBottomPanelHeight] = useState(280);
  const [accountAnchorY, setAccountAnchorY] = useState<number | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const syncState = 'idle' as const;

  const handlePanelSelect = (panel: ActivityPanel) => {
    if (panel === activePanel) {
      setSidePanelOpen((value) => !value);
      return;
    }
    setActivePanel(panel);
    setSidePanelOpen(true);
  };

  const handleSidebarResize = useCallback((delta: number) => {
    setSideBarWidth((current) => Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, current + delta)));
  }, []);

  const handleBottomResize = useCallback((delta: number) => {
    setBottomPanelHeight((current) => {
      const max = Math.floor(window.innerHeight * BOTTOM_MAX_PERCENT);
      return Math.min(max, Math.max(BOTTOM_MIN, current - delta));
    });
  }, []);

  useEffect(() => {
    function handleKey(event: KeyboardEvent) {
      const mod = event.ctrlKey || event.metaKey;
      if (!mod) return;

      const key = event.key.toLowerCase();
      if (key === 'b') {
        event.preventDefault();
        setSidePanelOpen((v) => !v);
      } else if (key === 'j') {
        event.preventDefault();
        setBottomPanelOpen((v) => !v);
      } else if (event.key === '`') {
        event.preventDefault();
        setBottomPanelOpen(true);
      } else if (event.key === ',') {
        // Ctrl+, opens settings, just like VS Code.
        event.preventDefault();
        setSettingsOpen(true);
      }
    }
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, []);

  useEffect(() => {
    const onToggleSidebar = () => setSidePanelOpen((v) => !v);
    const onTogglePanel = () => setBottomPanelOpen((v) => !v);
    const onToggleTerminal = () => setBottomPanelOpen(true);
    const onActivityPanel = (event: Event) => {
      const detail = (event as CustomEvent<ActivityPanel>).detail;
      if (detail) {
        setActivePanel(detail);
        setSidePanelOpen(true);
      }
    };
    const onOpenSettings = () => setSettingsOpen(true);
    window.addEventListener('cloudide:toggleSidebar', onToggleSidebar);
    window.addEventListener('cloudide:togglePanel', onTogglePanel);
    window.addEventListener('cloudide:toggleTerminal', onToggleTerminal);
    window.addEventListener('cloudide:activityPanel', onActivityPanel);
    window.addEventListener('cloudide:openSettings', onOpenSettings);
    return () => {
      window.removeEventListener('cloudide:toggleSidebar', onToggleSidebar);
      window.removeEventListener('cloudide:togglePanel', onTogglePanel);
      window.removeEventListener('cloudide:toggleTerminal', onToggleTerminal);
      window.removeEventListener('cloudide:activityPanel', onActivityPanel);
      window.removeEventListener('cloudide:openSettings', onOpenSettings);
    };
  }, []);

  const layoutStyle = {
    ['--sideBar-width' as string]: `${sideBarWidth}px`,
    ['--bottom-panel-height' as string]: `${bottomPanelHeight}px`,
  } as React.CSSProperties;

  const shellClass = [
    'app-shell',
    !sidePanelOpen ? 'is-sidebar-collapsed' : '',
    !bottomPanelOpen ? 'is-bottom-collapsed' : '',
  ].filter(Boolean).join(' ');

  return (
    <div className={shellClass} style={layoutStyle}>
      <TitleBar />
      <ActivityBar
        activePanel={activePanel}
        sidePanelOpen={sidePanelOpen}
        changedFilesCount={0}
        syncState={syncState}
        onSelect={handlePanelSelect}
        onAccount={(y) => setAccountAnchorY(y)}
        onSettings={() => setSettingsOpen(true)}
        onSyncIcon={() => window.dispatchEvent(new CustomEvent('cloudide:openSyncMenu'))}
      />
      <SidePanel activePanel={activePanel} />
      {sidePanelOpen ? <Splitter orientation="vertical" onResize={handleSidebarResize} className="splitter--side" /> : <div className="splitter--side" />}
      <EditorArea />
      {bottomPanelOpen ? <Splitter orientation="horizontal" onResize={handleBottomResize} className="splitter--bottom" /> : <div className="splitter--bottom" />}
      <BottomPanel onToggle={() => setBottomPanelOpen((v) => !v)} open={bottomPanelOpen} />
      <StatusBar />
      <AccountMenu
        open={accountAnchorY !== null}
        anchorY={accountAnchorY ?? 0}
        onClose={() => setAccountAnchorY(null)}
      />
      <SettingsModal open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </div>
  );
}
