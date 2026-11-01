/** Mirrors the backend policy in ChangePasswordRequestDTO. */
export const PASSWORD_RULES: { label: string; test: (pw: string) => boolean }[] = [
  { label: '8–20 characters', test: (pw) => pw.length >= 8 && pw.length <= 20 },
  { label: 'An uppercase letter', test: (pw) => /[A-Z]/.test(pw) },
  { label: 'A lowercase letter', test: (pw) => /[a-z]/.test(pw) },
  { label: 'A number', test: (pw) => /\d/.test(pw) },
  { label: 'A special character (@$!%*?&)', test: (pw) => /[@$!%*?&]/.test(pw) },
  { label: 'Only letters, numbers and @$!%*?&', test: (pw) => /^[A-Za-z\d@$!%*?&]*$/.test(pw) },
];

export function isPasswordValid(pw: string): boolean {
  return PASSWORD_RULES.every((rule) => rule.test(pw));
}
