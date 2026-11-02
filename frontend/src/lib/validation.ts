// Validation utilities for FollowWise

export function getUtf8ByteLength(str: string): number {
  return new TextEncoder().encode(str).length;
}

export function validatePassword(password: string): string | null {
  const bytes = getUtf8ByteLength(password);
  if (bytes < 10) {
    return "Password must be at least 10 bytes (characters)";
  }
  if (bytes > 72) {
    return "Password must not exceed 72 bytes";
  }
  return null;
}

export function validateEmail(email: string): string | null {
  const trimmed = email.trim();
  if (!trimmed) {
    return "Email is required";
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
    return "Invalid email address";
  }
  return null;
}
