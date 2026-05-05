interface FileIcon {
  icon: string;
  color: string;
}

const FILE_ICON_MAP: Array<{ test: RegExp; icon: string; color: string }> = [
  { test: /\.(tsx?|jsx?|mjs|cjs)$/i, icon: 'symbol-class', color: '#519aba' },
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
  { test: /\.(java|kt|kts)$/i, icon: 'symbol-method', color: '#cc7832' },
  { test: /\.(c|cc|cpp|cxx|h|hpp)$/i, icon: 'symbol-method', color: '#519aba' },
  { test: /\.(sh|bash|zsh|ps1|cmd|bat)$/i, icon: 'terminal', color: '#89e051' },
  { test: /\.(sql)$/i, icon: 'database', color: '#dad8d8' },
  { test: /\.(toml|yml|yaml|ini|cfg|conf)$/i, icon: 'settings-gear', color: '#cbcb41' },
  { test: /^\.env/i, icon: 'settings-gear', color: '#cbcb41' },
  { test: /^Dockerfile$/i, icon: 'symbol-method', color: '#384d54' },
  { test: /\.(lock|gitignore|gitattributes)$/i, icon: 'git-commit', color: '#858585' },
  { test: /\.csv$/i, icon: 'table', color: '#cbcb41' },
];

const DEFAULT_ICON: FileIcon = { icon: 'file', color: '#cccccc' };

export function fileIconFor(name: string): FileIcon {
  for (const entry of FILE_ICON_MAP) {
    if (entry.test.test(name)) return { icon: entry.icon, color: entry.color };
  }
  return DEFAULT_ICON;
}
