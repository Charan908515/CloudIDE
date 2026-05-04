import { useState } from 'react';
import { FileNode } from '@shared/types';
import { useEditorStore } from '../../store/editorStore';
import { useWorkspaceStore } from '../../store/workspaceStore';
import Codicon from '../common/Codicon';

interface FileTreeItemProps {
  node: FileNode;
  depth?: number;
}

const FILE_ICON_MAP: Array<{ test: RegExp; icon: string; color?: string }> = [
  { test: /\.(tsx?|jsx?)$/i, icon: 'symbol-class', color: '#519aba' },
  { test: /\.json$/i, icon: 'json', color: '#cbcb41' },
  { test: /\.(md|markdown)$/i, icon: 'markdown', color: '#519aba' },
  { test: /\.(html?|xml)$/i, icon: 'code', color: '#e44d26' },
  { test: /\.(css|scss|sass|less)$/i, icon: 'symbol-color', color: '#519aba' },
  { test: /\.(png|jpe?g|gif|svg|webp|ico|bmp)$/i, icon: 'file-media', color: '#a074c4' },
  { test: /\.(mp4|mov|webm|avi|mkv)$/i, icon: 'device-camera-video', color: '#a074c4' },
  { test: /\.(mp3|wav|flac|ogg)$/i, icon: 'unmute', color: '#a074c4' },
  { test: /\.(zip|tar|gz|7z|rar)$/i, icon: 'file-zip', color: '#cbcb41' },
  { test: /\.(py)$/i, icon: 'symbol-method', color: '#3572A5' },
  { test: /\.(go)$/i, icon: 'symbol-method', color: '#00ADD8' },
  { test: /\.(rs)$/i, icon: 'symbol-method', color: '#dea584' },
  { test: /\.(java|kt)$/i, icon: 'symbol-method', color: '#cc7832' },
  { test: /\.(c|cc|cpp|cxx|h|hpp)$/i, icon: 'symbol-method', color: '#519aba' },
  { test: /\.(sh|bash|zsh|ps1|cmd|bat)$/i, icon: 'terminal', color: '#89e051' },
  { test: /\.(sql)$/i, icon: 'database', color: '#dad8d8' },
  { test: /\.(env|yml|yaml|toml|ini|cfg|conf)$/i, icon: 'settings-gear', color: '#cbcb41' },
  { test: /\.(lock|gitignore|gitattributes)$/i, icon: 'git-commit', color: '#858585' },
];

function getFileIcon(name: string) {
  for (const entry of FILE_ICON_MAP) {
    if (entry.test.test(name)) return entry;
  }
  return { icon: 'file', color: '#cccccc' };
}

export default function FileTreeItem({ node, depth = 0 }: FileTreeItemProps) {
  const bridge = window.cloudide;
  const openFile = useEditorStore((state) => state.openFile);
  const activePath = useEditorStore((state) => state.activePath);
  const expandedSet = useWorkspaceStore((s) => s.expanded);
  const childrenCache = useWorkspaceStore((s) => s.childrenCache);
  const setExpanded = useWorkspaceStore((s) => s.setExpanded);
  const setChildren = useWorkspaceStore((s) => s.setChildren);

  const [isLoading, setIsLoading] = useState(false);
  const expanded = expandedSet.has(node.path);
  const cachedChildren = childrenCache.get(node.path);
  const loadedChildren = cachedChildren ?? node.children;

  const indent = depth * 8 + 8;

  if (node.type === 'directory') {
    const handleToggle = async () => {
      const nextExpanded = !expanded;
      setExpanded(node.path, nextExpanded);

      if (nextExpanded && !cachedChildren && bridge?.fs && !isLoading) {
        setIsLoading(true);
        try {
          const directoryNode = await bridge.fs.readDir(node.path);
          setChildren(node.path, directoryNode.children ?? []);
        } catch {
          setChildren(node.path, []);
        } finally {
          setIsLoading(false);
        }
      }
    };

    return (
      <div>
        <button
          className="file-tree__row file-tree__row--directory"
          onClick={() => void handleToggle()}
          style={{ paddingLeft: indent }}
          type="button"
        >
          <Codicon
            name={expanded ? 'chevron-down' : 'chevron-right'}
            size={14}
            className="file-tree__chevron"
          />
          <Codicon
            name={expanded ? 'folder-opened' : 'folder'}
            size={14}
            style={{ color: '#dcb67a' }}
          />
          <span className="file-tree__label">{node.name}</span>
        </button>
        {expanded ? (
          isLoading && !cachedChildren ? (
            <div className="file-tree__loading" style={{ paddingLeft: indent + 36 }}>
              Loading…
            </div>
          ) : (
            loadedChildren?.map((child) => (
              <FileTreeItem key={child.path} node={child} depth={depth + 1} />
            ))
          )
        ) : null}
      </div>
    );
  }

  const fileIcon = getFileIcon(node.name);

  return (
    <button
      className={`file-tree__row file-tree__row--file ${activePath === node.path ? 'is-active' : ''}`}
      onClick={async () => {
        if (!bridge?.fs) return;
        try {
          const content = await bridge.fs.readFile(node.path);
          openFile({
            path: node.path,
            name: node.name,
            content,
            savedContent: content,
          });
        } catch (error) {
          window.alert(error instanceof Error ? error.message : 'Failed to open file');
        }
      }}
      style={{ paddingLeft: indent }}
      type="button"
    >
      <span className="file-tree__chevron" />
      <Codicon name={fileIcon.icon} size={14} style={{ color: fileIcon.color }} />
      <span className="file-tree__label">{node.name}</span>
    </button>
  );
}
