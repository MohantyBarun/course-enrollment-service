/** Mirrors the backend policy (AuthDtos.PASSWORD_RULE). */
export const PASSWORD_RULES: { label: string; test: (pw: string) => boolean }[] = [
  { label: '8–64 characters', test: (pw) => pw.length >= 8 && pw.length <= 64 },
  { label: 'An uppercase letter', test: (pw) => /[A-Z]/.test(pw) },
  { label: 'A lowercase letter', test: (pw) => /[a-z]/.test(pw) },
  { label: 'A number', test: (pw) => /\d/.test(pw) },
  { label: 'A symbol', test: (pw) => /[^A-Za-z\d]/.test(pw) },
];

export function isPasswordValid(pw: string): boolean {
  return PASSWORD_RULES.every((rule) => rule.test(pw));
}
