/**
 * Token persistence for the browser session.
 *
 * SECURITY TRADEOFF (deliberate, documented):
 * Tokens are kept in `localStorage`. That makes them readable by any script running on the
 * origin, so a successful XSS gives an attacker the refresh token (30 day lifetime) as well as
 * the access token. The strictly safer arrangement is an httpOnly + SameSite cookie for the
 * refresh token and an in-memory access token, but `POST /api/auth/login` returns both tokens in
 * the JSON body and never sets a cookie, so that option needs a backend change first.
 * In-memory-only storage was rejected because it logs the user out on every page reload.
 *
 * Everything token-related goes through this module, so migrating to cookies later means
 * rewriting this file and nothing else.
 */
import type { TokenPair } from "@/types";

const ACCESS_TOKEN_KEY = "flowforge.accessToken";
const REFRESH_TOKEN_KEY = "flowforge.refreshToken";

/** `localStorage` is unavailable during server rendering and may throw in private modes. */
function safeStorage(): Storage | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function read(key: string): string | null {
  return safeStorage()?.getItem(key) ?? null;
}

export function getAccessToken(): string | null {
  return read(ACCESS_TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return read(REFRESH_TOKEN_KEY);
}

/**
 * Persists a freshly issued token pair. Refresh tokens are single-use and rotate on every
 * refresh, so the new refresh token MUST replace the old one or the next refresh fails with 401.
 */
export function setTokens(tokens: TokenPair): void {
  const storage = safeStorage();
  if (!storage) {
    return;
  }
  storage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken);
  storage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
}

export function clearTokens(): void {
  const storage = safeStorage();
  if (!storage) {
    return;
  }
  storage.removeItem(ACCESS_TOKEN_KEY);
  storage.removeItem(REFRESH_TOKEN_KEY);
}

export function hasSession(): boolean {
  return getAccessToken() !== null || getRefreshToken() !== null;
}
