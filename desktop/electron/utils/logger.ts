export function logInfo(message: string, meta?: unknown) {
  console.log(`[cloudide] ${message}`, meta ?? '');
}

export function logError(message: string, error?: unknown) {
  console.error(`[cloudide] ${message}`, error ?? '');
}
