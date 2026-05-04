import { BrowserWindow, ipcMain } from 'electron';
import { ExecutionService } from '../services/ExecutionService';

export function registerExecutionIpc(getMainWindow: () => BrowserWindow | null) {
  const service = new ExecutionService(getMainWindow);

  ipcMain.handle('exec:run', async (_event, filePath: string, stdin?: string) => {
    return service.runFile(filePath, stdin);
  });

  ipcMain.handle('exec:kill', async (_event, sessionId: string) => {
    return service.kill(sessionId);
  });

  ipcMain.handle('exec:stdin', async (_event, sessionId: string, input: string) => {
    return service.writeStdin(sessionId, input);
  });
}
