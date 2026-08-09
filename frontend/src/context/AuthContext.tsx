"use client";

/**
 * Holds the authenticated session: tokens (via `tokenStorage`) and the user profile.
 * Navigation is intentionally left to the consuming components so this provider stays testable
 * and router-agnostic.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { onSessionExpired } from "@/lib/api";
import * as authApi from "@/lib/authApi";
import {
  clearTokens,
  getRefreshToken,
  hasSession,
  setTokens,
} from "@/lib/tokenStorage";
import type { User } from "@/types";

export type AuthStatus = "loading" | "authenticated" | "unauthenticated";

export interface AuthContextValue {
  user: User | null;
  status: AuthStatus;
  /** Authenticates, stores the token pair and loads the profile. Throws on failure. */
  login: (email: string, password: string) => Promise<void>;
  /** Revokes the refresh token server side (best effort) and clears local state. */
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<AuthStatus>("loading");
  const mounted = useRef(true);

  const endSession = useCallback(() => {
    clearTokens();
    setUser(null);
    setStatus("unauthenticated");
  }, []);

  // Restore the session on first paint: stored tokens are meaningless without a profile, and a
  // stale access token is transparently refreshed by the API interceptor.
  useEffect(() => {
    mounted.current = true;

    if (!hasSession()) {
      setStatus("unauthenticated");
      return () => {
        mounted.current = false;
      };
    }

    authApi
      .fetchCurrentUser()
      .then((profile) => {
        if (!mounted.current) {
          return;
        }
        setUser(profile);
        setStatus("authenticated");
      })
      .catch(() => {
        if (mounted.current) {
          endSession();
        }
      });

    return () => {
      mounted.current = false;
    };
  }, [endSession]);

  // A refresh that fails is unrecoverable: drop the session rather than leave a signed-in shell.
  useEffect(() => onSessionExpired(endSession), [endSession]);

  const login = useCallback(async (email: string, password: string) => {
    const tokens = await authApi.login(email, password);
    setTokens(tokens);
    try {
      const profile = await authApi.fetchCurrentUser();
      setUser(profile);
      setStatus("authenticated");
    } catch (error) {
      // Credentials were fine but the profile did not load — do not leave half a session behind.
      clearTokens();
      setUser(null);
      setStatus("unauthenticated");
      throw error;
    }
  }, []);

  const logout = useCallback(async () => {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
      try {
        await authApi.logout(refreshToken);
      } catch {
        // The local session is dropped regardless; the token expires server side on its own.
      }
    }
    clearTokens();
    setUser(null);
    setStatus("unauthenticated");
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, status, login, logout }),
    [user, status, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used inside an AuthProvider");
  }
  return context;
}
