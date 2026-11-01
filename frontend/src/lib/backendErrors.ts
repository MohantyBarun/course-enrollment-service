/**
 * After a merge, Vercel ships the new frontend in under a minute, but Render needs several minutes
 * to build and start the new backend. Until then, new pages call endpoints the old backend lacks.
 */
export function explainApiError(message: string | null): string | null {
  if (!message) return null;
  if (message.startsWith('Endpoint not found')) {
    return 'The server doesn\'t have this page yet: a new backend version is probably still deploying '
      + '(this takes a few minutes after an update). Try again shortly.';
  }
  return message;
}
