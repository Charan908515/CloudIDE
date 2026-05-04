import crypto from 'crypto';
import http from 'http';
import { shell } from 'electron';
import Store from 'electron-store';
import keytar from 'keytar';
import { desktopConfig } from '../utils/config';
import { logError, logInfo } from '../utils/logger';

const GOOGLE_AUTH_URL = 'https://accounts.google.com/o/oauth2/v2/auth';
const GOOGLE_TOKEN_URL = 'https://oauth2.googleapis.com/token';
const KEYTAR_SERVICE = 'cloudide';
const REFRESH_TOKEN_ACCOUNT = 'google-refresh-token';

type AuthStore = {
  googleAccessToken?: string;
  googleIdToken?: string;
  googleTokenExpiry?: number;
  appJwt?: string;
  user?: {
    email: string;
    name: string;
    picture: string;
  };
};

export interface AuthSession {
  googleAccessToken: string;
  googleRefreshToken: string;
  idToken: string;
  appJwt: string;
  expiresAt: number;
  user: {
    email: string;
    name: string;
    picture: string;
  };
}

function base64Url(buffer: Buffer) {
  return buffer
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function decodeJwtPayload(token: string) {
  const parts = token.split('.');
  if (parts.length < 2) {
    throw new Error('Invalid JWT payload');
  }

  return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')) as {
    email?: string;
    name?: string;
    picture?: string;
  };
}

export class AuthService {
  private store = new Store<AuthStore>({ name: 'cloudide-auth' });

  async authenticate(): Promise<AuthSession> {
    const codeVerifier = this.generateCodeVerifier();
    const codeChallenge = this.generateCodeChallenge(codeVerifier);
    const authUrl = this.buildAuthUrl(codeChallenge);

    logInfo('Starting Google OAuth PKCE flow');
    const authCode = await this.waitForAuthCode(authUrl);
    const googleTokens = await this.exchangeCodeForTokens(authCode, codeVerifier);
    const refreshToken = googleTokens.refresh_token || (await this.getRefreshToken());

    if (!refreshToken) {
      throw new Error('Google refresh token was not returned');
    }

    await keytar.setPassword(KEYTAR_SERVICE, REFRESH_TOKEN_ACCOUNT, refreshToken);

    const payload = decodeJwtPayload(googleTokens.id_token);
    const expiresAt = Date.now() + Number(googleTokens.expires_in || 0) * 1000;

    // Backend is optional — when apiBaseUrl is empty we trust the Google ID token directly.
    // The backend's job is push-notifying mobile companions; not needed for desktop-only flow.
    let appAuth: { jwt: string; user: { email: string; name: string; picture: string } } = {
      jwt: '',
      user: {
        email: payload.email || '',
        name: payload.name || '',
        picture: payload.picture || '',
      },
    };
    if (desktopConfig.apiBaseUrl) {
      try {
        appAuth = await this.verifyWithBackend(googleTokens.id_token);
      } catch (error) {
        logError('Backend verification failed (continuing without it)', error);
      }
    }

    this.store.set({
      googleAccessToken: googleTokens.access_token,
      googleIdToken: googleTokens.id_token,
      googleTokenExpiry: expiresAt,
      appJwt: appAuth.jwt,
      user: {
        email: appAuth.user.email || payload.email || '',
        name: appAuth.user.name || payload.name || '',
        picture: appAuth.user.picture || payload.picture || '',
      },
    });

    return {
      googleAccessToken: googleTokens.access_token,
      googleRefreshToken: refreshToken,
      idToken: googleTokens.id_token,
      appJwt: appAuth.jwt,
      expiresAt,
      user: appAuth.user,
    };
  }

  async getValidAccessToken(): Promise<string | null> {
    const accessToken = this.store.get('googleAccessToken');
    const expiresAt = this.store.get('googleTokenExpiry', 0);

    if (accessToken && expiresAt - Date.now() > 5 * 60 * 1000) {
      return accessToken;
    }

    const refreshToken = await this.getRefreshToken();
    if (!refreshToken) {
      return null;
    }

    const refreshed = await this.refreshAccessToken(refreshToken);
    const nextExpiry = Date.now() + Number(refreshed.expires_in || 0) * 1000;

    this.store.set('googleAccessToken', refreshed.access_token);
    this.store.set('googleTokenExpiry', nextExpiry);
    if (refreshed.id_token) {
      this.store.set('googleIdToken', refreshed.id_token);
    }

    return refreshed.access_token;
  }

  getAppJwt() {
    return this.store.get('appJwt');
  }

  getUser() {
    return this.store.get('user');
  }

  private buildAuthUrl(codeChallenge: string) {
    const url = new URL(GOOGLE_AUTH_URL);
    url.searchParams.set('client_id', desktopConfig.googleClientId);
    url.searchParams.set('redirect_uri', desktopConfig.googleRedirectUri);
    url.searchParams.set('response_type', 'code');
    url.searchParams.set('scope', 'openid email profile https://www.googleapis.com/auth/drive');
    url.searchParams.set('code_challenge', codeChallenge);
    url.searchParams.set('code_challenge_method', 'S256');
    url.searchParams.set('access_type', 'offline');
    url.searchParams.set('prompt', 'consent');
    return url.toString();
  }

  private generateCodeVerifier() {
    return base64Url(crypto.randomBytes(64)).slice(0, 96);
  }

  private generateCodeChallenge(verifier: string) {
    return base64Url(crypto.createHash('sha256').update(verifier).digest());
  }

  private waitForAuthCode(authUrl: string): Promise<string> {
    return new Promise((resolve, reject) => {
      const redirect = new URL(desktopConfig.googleRedirectUri);
      const server = http.createServer((req, res) => {
        if (!req.url) {
          res.writeHead(400);
          res.end('Missing callback URL');
          return;
        }

        const callbackUrl = new URL(req.url, `${redirect.protocol}//${redirect.host}`);
        const code = callbackUrl.searchParams.get('code');
        const error = callbackUrl.searchParams.get('error');

        if (error) {
          res.writeHead(400, { 'Content-Type': 'text/plain' });
          res.end(`Authentication failed: ${error}`);
          server.close();
          reject(new Error(error));
          return;
        }

        if (!code) {
          res.writeHead(400, { 'Content-Type': 'text/plain' });
          res.end('Missing OAuth code');
          return;
        }

        res.writeHead(200, { 'Content-Type': 'text/html' });
        res.end('<html><body>You can close this window and return to CloudIDE.</body></html>');
        server.close();
        resolve(code);
      });

      server.on('error', (error) => {
        reject(error);
      });

      server.listen(Number(redirect.port || 42813), redirect.hostname, () => {
        void shell.openExternal(authUrl);
      });
    });
  }

  private async exchangeCodeForTokens(code: string, codeVerifier: string) {
    const params: Record<string, string> = {
      code,
      client_id: desktopConfig.googleClientId,
      redirect_uri: desktopConfig.googleRedirectUri,
      code_verifier: codeVerifier,
      grant_type: 'authorization_code',
    };
    if (desktopConfig.googleClientSecret) {
      params.client_secret = desktopConfig.googleClientSecret;
    }

    const response = await fetch(GOOGLE_TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(params),
    });

    if (!response.ok) {
      throw new Error(`Google token exchange failed: ${response.status}`);
    }

    return response.json() as Promise<{
      access_token: string;
      refresh_token?: string;
      id_token: string;
      expires_in: number;
    }>;
  }

  private async refreshAccessToken(refreshToken: string) {
    const refreshParams: Record<string, string> = {
      client_id: desktopConfig.googleClientId,
      refresh_token: refreshToken,
      grant_type: 'refresh_token',
    };
    if (desktopConfig.googleClientSecret) {
      refreshParams.client_secret = desktopConfig.googleClientSecret;
    }

    const response = await fetch(GOOGLE_TOKEN_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(refreshParams),
    });

    if (!response.ok) {
      throw new Error(`Google token refresh failed: ${response.status}`);
    }

    return response.json() as Promise<{
      access_token: string;
      id_token?: string;
      expires_in: number;
    }>;
  }

  private async verifyWithBackend(idToken: string) {
    const endpoint = `${desktopConfig.apiBaseUrl.replace(/\/$/, '')}/auth/verify`;
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ idToken }),
    });

    if (!response.ok) {
      const errorText = await response.text();
      logError('NinjaHost auth verification failed', errorText);
      throw new Error(`Backend auth verification failed: ${response.status}`);
    }

    return response.json() as Promise<{
      jwt: string;
      user: {
        email: string;
        name: string;
        picture: string;
      };
    }>;
  }

  private getRefreshToken() {
    return keytar.getPassword(KEYTAR_SERVICE, REFRESH_TOKEN_ACCOUNT);
  }
}
