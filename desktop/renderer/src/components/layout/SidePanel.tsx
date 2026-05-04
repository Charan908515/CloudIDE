import FileTree from '../explorer/FileTree';
import SearchPanel from '../search/SearchPanel';
import SourceControlPanel from '../git/SourceControlPanel';

interface SidePanelProps {
  activePanel: 'files' | 'search' | 'git' | 'run' | 'extensions';
}

const titles: Record<SidePanelProps['activePanel'], string> = {
  files: 'Explorer',
  search: 'Search',
  git: 'Source Control',
  run: 'Run and Debug',
  extensions: 'Extensions',
};

export default function SidePanel({ activePanel }: SidePanelProps) {
  return (
    <section className="side-panel">
      <header className="side-panel__header">
        <h2 className="side-panel__title">{titles[activePanel]}</h2>
      </header>
      <div className="side-panel__body">
        {activePanel === 'files' ? <FileTree /> : null}
        {activePanel === 'search' ? <SearchPanel /> : null}
        {activePanel === 'git' ? <SourceControlPanel /> : null}
        {activePanel === 'run' ? (
          <div className="panel-placeholder">
            <p>Launch configurations and debugging tools will appear here.</p>
          </div>
        ) : null}
        {activePanel === 'extensions' ? (
          <div className="panel-placeholder">
            <p>Extensions aren't supported yet. The bundled language services (TypeScript, JSON, CSS, HTML) work out of the box. A real marketplace is on the roadmap.</p>
          </div>
        ) : null}
      </div>
    </section>
  );
}
