import fs from 'fs';
import path from 'path';
import { DEFAULT_JUDGE0_URL } from '../../shared/constants';

const envCache = new Map<string, string>();

function loadEnvFile() {
  const envPath = path.resolve(__dirname, '../../../../.env');
  if (!fs.existsSync(envPath)) {
    return;
  }

  const contents = fs.readFileSync(envPath, 'utf8');
  for (const line of contents.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) {
      continue;
    }

    const separatorIndex = trimmed.indexOf('=');
    if (separatorIndex <= 0) {
      continue;
    }

    const key = trimmed.slice(0, separatorIndex).trim();
    const value = trimmed.slice(separatorIndex + 1).trim();
    if (!(key in process.env)) {
      process.env[key] = value;
    }
    envCache.set(key, value);
  }
}

loadEnvFile();

function getEnv(key: string, fallback = '') {
  return process.env[key] || envCache.get(key) || fallback;
}

export const desktopConfig = {
  googleClientId: getEnv('VITE_GOOGLE_CLIENT_ID'),
  googleClientSecret: getEnv('VITE_GOOGLE_CLIENT_SECRET'),
  googleRedirectUri: getEnv('VITE_GOOGLE_REDIRECT_URI', 'http://localhost:42813/callback'),
  apiBaseUrl: getEnv('VITE_API_BASE_URL'),
  judge0Url: getEnv('VITE_JUDGE0_URL', DEFAULT_JUDGE0_URL),
  pistonUrl: getEnv('VITE_PISTON_URL', DEFAULT_JUDGE0_URL),
  driveRootName: getEnv('VITE_DRIVE_CLOUDIDE_FOLDER', 'CloudIDE'),
};
