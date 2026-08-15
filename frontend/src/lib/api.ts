/**
 * Shared Axios instance for the FlowForge API.
 *
 * Responsibilities:
 *  - attach the JWT access token to every outgoing request;
 *  - on a 401, refresh the access token exactly once and replay the original request;
 *  - persist the rotated refresh token (refresh tokens are strictly single-use server side);
 *  - collapse concurrent 401s onto a single in-flight refresh, because a second refresh call
 *    using the same (already consumed) refresh token would be rejected with 401.
 */
import axios, {
  AxiosError,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from "axios";
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from "@/lib/tokenStorage";
import type { ApiResponse, TokenPair } from "@/types";

/**
 * Relative by default: `next.config.mjs` rewrites `/api/:path*` onto the backend, which keeps the
 * browser on a single origin and avoids CORS entirely.
 */
export const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "/api";

/** Internal request flags used to keep the refresh machinery from recursing. */
type AuthAwareConfig = InternalAxiosRequestConfig & {
  /** Set once the request has already been replayed after a refresh. */
  _retry?: boolean;
  /** Set on the refresh call itself so it never triggers another refresh. */
  _skipAuthRefresh?: boolean;
};

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: { "Content-Type": "application/json" },
});

api.interceptors.request.use((config) => {
  const token = getAccessToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// ── Session-expiry notification ────────────────────────────────────────────────
// The API layer has no view of React state, so it announces an unrecoverable 401 and lets
// AuthProvider drop the user object.

type SessionExpiredListener = () => void;
const sessionExpiredListeners = new Set<SessionExpiredListener>();

/** Subscribe to "the session is gone, stop pretending the user is signed in". */
export function onSessionExpired(listener: SessionExpiredListener): () => void {
  sessionExpiredListeners.add(listener);
  return () => {
    sessionExpiredListeners.delete(listener);
  };
}

function announceSessionExpired(): void {
  sessionExpiredListeners.forEach((listener) => listener());
}

// ── Single-flight refresh ──────────────────────────────────────────────────────

let inFlightRefresh: Promise<string> | null = null;

async function requestNewTokenPair(): Promise<string> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    throw new Error("No refresh token available");
  }
  const response = await api.post<ApiResponse<TokenPair>>(
    "/auth/refresh",
    { refreshToken },
    { _skipAuthRefresh: true } as AuthAwareConfig,
  );
  const tokens = response.data.data;
  if (!tokens) {
    throw new Error("Refresh response did not contain a token pair");
  }
  // The old refresh token is dead the moment this call succeeds — persist the rotated one.
  setTokens(tokens);
  return tokens.accessToken;
}

/** Refreshes at most once concurrently; every caller awaits the same promise. */
function refreshAccessToken(): Promise<string> {
  if (!inFlightRefresh) {
    inFlightRefresh = requestNewTokenPair().finally(() => {
      inFlightRefresh = null;
    });
  }
  return inFlightRefresh;
}

/** `/auth/*` is public: a 401 there is a real credential failure, not an expired token. */
function isAuthEndpoint(url: string | undefined): boolean {
  return (url ?? "").includes("/auth/");
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const config = error.config as AuthAwareConfig | undefined;

    const cannotRecover =
      error.response?.status !== 401 ||
      !config ||
      config._retry ||
      config._skipAuthRefresh ||
      isAuthEndpoint(config.url) ||
      !getRefreshToken();

    if (cannotRecover) {
      return Promise.reject(error);
    }

    config._retry = true;
    try {
      const accessToken = await refreshAccessToken();
      config.headers.Authorization = `Bearer ${accessToken}`;
      return api.request(config);
    } catch {
      clearTokens();
      announceSessionExpired();
      return Promise.reject(error);
    }
  },
);

/** Unwraps the `ApiResponse` envelope, failing loudly when the payload is missing. */
export function unwrap<T>(response: AxiosResponse<ApiResponse<T>>): T {
  const body = response.data;
  if (body?.data === undefined || body.data === null) {
    throw new Error(body?.message ?? "Response contained no data");
  }
  return body.data;
}

/** True when the request failed with the given HTTP status. */
export function isStatusError(error: unknown, status: number): boolean {
  return axios.isAxiosError(error) && error.response?.status === status;
}

/**
 * True for "you are authenticated, but not allowed to do this" (Requirement 3.2).
 *
 * Pages use this to draw a deliberate "not authorized" state instead of leaking a raw error: a
 * MANAGER who types `/users` in the address bar has made an understandable mistake, not hit a bug.
 */
export function isForbiddenError(error: unknown): boolean {
  return isStatusError(error, 403);
}

/**
 * Best available human-readable message for a failed request. The backend already returns
 * user-safe copy in `message` (for example the deliberately generic "Invalid email or password"),
 * so it is surfaced verbatim.
 */
export function extractErrorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiResponse<unknown> | undefined;
    if (body?.message) {
      return body.message;
    }
    if (body?.errors?.length) {
      return body.errors.map((fieldError) => fieldError.message).join(", ");
    }
    if (!error.response) {
      return "Could not reach the server. Check your connection and try again.";
    }
  }
  return fallback;
}

export default api;
