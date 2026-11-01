import { describe, expect, it } from 'vitest';
import { fakeJwt } from '../test/helpers';
import { formatDate, roleLabel } from './format';
import { decodeJwt, isExpired } from './jwt';
import { isPasswordValid } from './passwordRules';

describe('jwt', () => {
  it('decodes the backend claims', () => {
    const claims = decodeJwt(fakeJwt({ sub: 'a@x.com', role: 'ROLE_STUDENT', pwdChangeRequired: true }));
    expect(claims).toMatchObject({ sub: 'a@x.com', role: 'ROLE_STUDENT', pwdChangeRequired: true });
  });

  it('returns null for malformed tokens', () => {
    expect(decodeJwt('not-a-jwt')).toBeNull();
    expect(decodeJwt('a.b@d!.c')).toBeNull();
  });

  it('treats tokens expiring within the skew window as expired', () => {
    expect(isExpired(fakeJwt({}, 900))).toBe(false);
    expect(isExpired(fakeJwt({}, 10))).toBe(true); // inside the 30s skew
    expect(isExpired(fakeJwt({}, -60))).toBe(true);
    expect(isExpired('garbage')).toBe(true);
  });
});

describe('password rules (mirror the backend policy)', () => {
  it.each([
    ['Good@Pass1', true],
    ['short@1A', true],
    ['noupper@1', false],
    ['NOLOWER@1', false],
    ['NoNumber@x', false],
    ['NoSpecial1x', false],
    ['Has Space@1', false],
    ['TooLong@1234567890abc', false],
  ])('%s -> %s', (pw, valid) => expect(isPasswordValid(pw)).toBe(valid));
});

describe('format', () => {
  it('formats date-only values without shifting the day across time zones', () => {
    expect(formatDate('2003-05-14')).toContain('14');
    expect(formatDate(null)).toBe('—');
  });

  it('labels roles', () => {
    expect(roleLabel('ROLE_STUDENT')).toBe('Student');
    expect(roleLabel('ROLE_ADMIN')).toBe('Admin');
  });
});
