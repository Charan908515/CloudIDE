import { useEffect, useRef, useState } from 'react';
import { useWorkspaceStore } from '../../store/workspaceStore';
import { useEditorStore } from '../../store/editorStore';
import Codicon from '../common/Codicon';

export default function SearchPanel() {
  const bridge = window.cloudide;
  const rootPath = useWorkspaceStore((s) => s.rootPath);
  const openFile = useEditorStore((s) => s.openFile);

  const [query, setQuery] = useState('');
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [wholeWord, setWholeWord] = useState(false);
  const [regex, setRegex] = useState(false);
  const [showOptions, setShowOptions] = useState(false);
  const [includeGlob, setIncludeGlob] = useState('');
  const [excludeGlob, setExcludeGlob] = useState('');
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const [result, setResult] = useState<SearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (!bridge?.search || !rootPath) {
      setResult(null);
      return;
    }
    if (!query.trim()) {
      setResult(null);
      return;
    }

    if (debounceRef.current) clearTimeout(debounceRef.current);
    setSearching(true);

    debounceRef.current = setTimeout(async () => {
      const id = ++requestIdRef.current;
      try {
        const next = await bridge.search.run({
          query,
          cwd: rootPath,
          caseSensitive,
          wholeWord,
          regex,
          includeGlob,
          excludeGlob,
        });
        if (id === requestIdRef.current) setResult(next);
      } finally {
        if (id === requestIdRef.current) setSearching(false);
      }
    }, 220);

    return () => {
      if (debounceRef.current) clearTimeout(debounceRef.current);
    };
  }, [bridge, rootPath, query, caseSensitive, wholeWord, regex, includeGlob, excludeGlob]);

  async function openMatch(filePath: string, line: number, column: number) {
    if (!bridge?.fs) return;
    try {
      const content = await bridge.fs.readFile(filePath);
      const name = filePath.split(/[\\/]/).pop() ?? filePath;
      openFile({ path: filePath, name, content, savedContent: content });
      window.dispatchEvent(
        new CustomEvent('cloudide:revealEditorPosition', { detail: { path: filePath, line, column } })
      );
    } catch {
      /* ignore */
    }
  }

  if (!bridge?.search) {
    return <div className="panel-placeholder">Search is available only in the desktop window.</div>;
  }

  if (!rootPath) {
    return (
      <div className="panel-placeholder">
        <p>Open a folder to search across files.</p>
      </div>
    );
  }

  return (
    <div className="search-panel">
      <div className="search-panel__row">
        <input
          autoFocus
          className="search-panel__input"
          placeholder="Search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <button
          className={`search-panel__toggle ${caseSensitive ? 'is-active' : ''}`}
          title="Match Case"
          onClick={() => setCaseSensitive((v) => !v)}
        >
          Aa
        </button>
        <button
          className={`search-panel__toggle ${wholeWord ? 'is-active' : ''}`}
          title="Match Whole Word"
          onClick={() => setWholeWord((v) => !v)}
        >
          ab
        </button>
        <button
          className={`search-panel__toggle ${regex ? 'is-active' : ''}`}
          title="Use Regular Expression"
          onClick={() => setRegex((v) => !v)}
        >
          .*
        </button>
      </div>

      <button className="search-panel__more" onClick={() => setShowOptions((v) => !v)} type="button">
        <Codicon name={showOptions ? 'chevron-down' : 'chevron-right'} size={12} />
        <span>{showOptions ? 'Hide' : 'Show'} include/exclude</span>
      </button>

      {showOptions ? (
        <div className="search-panel__options">
          <label>
            <span>files to include</span>
            <input
              className="search-panel__input"
              placeholder="e.g. *.ts, src/**"
              value={includeGlob}
              onChange={(e) => setIncludeGlob(e.target.value)}
            />
          </label>
          <label>
            <span>files to exclude</span>
            <input
              className="search-panel__input"
              placeholder="e.g. **/*.test.ts"
              value={excludeGlob}
              onChange={(e) => setExcludeGlob(e.target.value)}
            />
          </label>
        </div>
      ) : null}

      <div className="search-panel__status">
        {result?.error ? <span style={{ color: '#f48771' }}>{result.error}</span> : null}
        {searching ? 'Searching…' : null}
        {!searching && result && !result.error
          ? `${result.total} ${result.total === 1 ? 'result' : 'results'} in ${result.results.length} ${result.results.length === 1 ? 'file' : 'files'}${result.hitLimit ? ' (limit hit)' : ''}`
          : null}
      </div>

      <div className="search-panel__results">
        {result?.results.map((file) => {
          const isCollapsed = collapsed[file.path];
          return (
            <div key={file.path} className="search-result-file">
              <button
                className="search-result-file__header"
                onClick={() => setCollapsed((c) => ({ ...c, [file.path]: !isCollapsed }))}
                type="button"
              >
                <Codicon name={isCollapsed ? 'chevron-right' : 'chevron-down'} size={12} />
                <Codicon name="file" size={14} />
                <span className="search-result-file__name">{file.relativePath}</span>
                <span className="search-result-file__count">{file.matches.length}</span>
              </button>
              {!isCollapsed
                ? file.matches.map((match, idx) => (
                    <button
                      key={`${file.path}-${match.line}-${match.column}-${idx}`}
                      className="search-result-line"
                      onClick={() => void openMatch(file.path, match.line, match.column)}
                      type="button"
                    >
                      <span className="search-result-line__num">{match.line}</span>
                      <span className="search-result-line__text">
                        {match.text.slice(0, match.matchStart)}
                        <mark>{match.text.slice(match.matchStart, match.matchEnd)}</mark>
                        {match.text.slice(match.matchEnd)}
                      </span>
                    </button>
                  ))
                : null}
            </div>
          );
        })}
      </div>
    </div>
  );
}
