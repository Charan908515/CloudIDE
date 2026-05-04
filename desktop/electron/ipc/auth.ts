import { ipcMain } from 'electron';
import { AuthService } from '../services/AuthService';

export function createAuthService() {
  return new AuthService();
}

export function registerAuthIpc(authService: AuthService) {
  ipcMain.handle('auth:status', async () => {
    const token = await authService.getValidAccessToken();
    return {
      signedIn: !!token,
      user: authService.getUser() ?? null,
    };
  });

  ipcMain.handle('auth:signIn', async () => {
    try {
      const session = await authService.authenticate();
      return { ok: true, user: session.user };
    } catch (error) {
      return { ok: false, error: error instanceof Error ? error.message : String(error) };
    }
  });
}
