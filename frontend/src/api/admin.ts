import { apiRequest } from './client';
import type { NotificationFeed, SystemSnapshot } from './types';

export const adminApi = {
  notifications: () => apiRequest<NotificationFeed>('/admin/notifications'),
  system: () => apiRequest<SystemSnapshot>('/admin/system'),
  sendTestEmail: (to?: string) =>
    apiRequest<{ recipient: string; message: string }>('/admin/notifications/test', { method: 'POST', body: { to: to || null } }),
};
