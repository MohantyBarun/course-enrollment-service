import { apiRequest } from './client';
import type { LoginResponse } from './types';

export const authApi = {
  login: (email: string, password: string) =>
    apiRequest<LoginResponse>('/auth/login', { method: 'POST', body: { email, password }, auth: false }),

  logout: (refreshToken: string) =>
    apiRequest<string>('/auth/logout', { method: 'POST', body: { refreshToken }, auth: false }),

  changePassword: (oldPassword: string, newPassword: string, confirmNewPassword: string) =>
    apiRequest<string>('/auth/change-password', {
      method: 'POST',
      body: { oldPassword, newPassword, confirmNewPassword },
    }),
};
