import type { Role } from '../api/types';

/** Claims issued by the backend's JwtUtil. The signature is verified server-side; we only read claims. */
export interface AccessTokenClaims {
  sub: string; // email
  role: Role;
  type: 'ACCESS' | 'REFRESH';
  pwdChangeRequired?: boolean;
  exp: number; // seconds since epoch
  iat: number;
}

function base64UrlDecode(segment: string): string {
  const base64 = segment.replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
  const binary = atob(padded);
  // Decode UTF-8 safely
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

export function decodeJwt<T = AccessTokenClaims>(token: string): T | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    return JSON.parse(base64UrlDecode(parts[1])) as T;
  } catch {
    return null;
  }
}

/** True if the token is unreadable or expires within `skewSeconds`. */
export function isExpired(token: string, skewSeconds = 30, now = Date.now()): boolean {
  const claims = decodeJwt(token);
  if (!claims?.exp) return true;
  return claims.exp * 1000 <= now + skewSeconds * 1000;
}
