/**
 * Token persistence. localStorage keeps the user signed in across reloads.
 * Trade-off: tokens are readable by JavaScript, so XSS must be prevented (React escapes output
 * by default and we never inject raw HTML). httpOnly cookies would need backend changes.
 */
const ACCESS_KEY = 'sms.accessToken';
const REFRESH_KEY = 'sms.refreshToken';

export interface Tokens {
  accessToken: string;
  refreshToken: string;
}

export const tokenStore = {
  get(): Tokens | null {
    const accessToken = localStorage.getItem(ACCESS_KEY);
    const refreshToken = localStorage.getItem(REFRESH_KEY);
    return accessToken && refreshToken ? { accessToken, refreshToken } : null;
  },
  set(tokens: Tokens) {
    localStorage.setItem(ACCESS_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_KEY, tokens.refreshToken);
  },
  setAccessToken(accessToken: string) {
    localStorage.setItem(ACCESS_KEY, accessToken);
  },
  clear() {
    localStorage.removeItem(ACCESS_KEY);
    localStorage.removeItem(REFRESH_KEY);
  },
};
