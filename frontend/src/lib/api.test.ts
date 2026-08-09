import { AxiosError, AxiosHeaders } from "axios";
import type { AxiosResponse, InternalAxiosRequestConfig } from "axios";
import api from "@/lib/api";
import { clearTokens, getAccessToken, getRefreshToken, setTokens } from "@/lib/tokenStorage";

function ok<T>(config: InternalAxiosRequestConfig, data: T): AxiosResponse<T> {
  return { data, status: 200, statusText: "OK", headers: new AxiosHeaders(), config };
}

function unauthorized(config: InternalAxiosRequestConfig): AxiosError {
  const response = {
    data: { success: false, message: "Authentication required" },
    status: 401,
    statusText: "Unauthorized",
    headers: new AxiosHeaders(),
    config,
  } as AxiosResponse;
  return new AxiosError("Unauthorized", AxiosError.ERR_BAD_REQUEST, config, null, response);
}

const tokenPair = (n: number) => ({
  accessToken: `access-${n}`,
  refreshToken: `refresh-${n}`,
  tokenType: "Bearer",
  expiresIn: 900,
});

describe("api client 401 handling", () => {
  const originalAdapter = api.defaults.adapter;

  beforeEach(() => {
    clearTokens();
    setTokens(tokenPair(1));
  });

  afterEach(() => {
    api.defaults.adapter = originalAdapter;
    clearTokens();
  });

  it("refreshes once on 401, persists the rotated refresh token and replays the request", async () => {
    const seen: Array<{ url?: string; authorization?: string }> = [];
    let profileCalls = 0;

    api.defaults.adapter = async (config) => {
      seen.push({
        url: config.url,
        authorization: config.headers.Authorization as string | undefined,
      });
      if (config.url === "/auth/refresh") {
        return ok(config, { success: true, data: tokenPair(2) });
      }
      profileCalls += 1;
      if (profileCalls === 1) {
        throw unauthorized(config);
      }
      return ok(config, { success: true, data: { id: "u1" } });
    };

    const response = await api.get("/users/me");

    expect(response.status).toBe(200);
    // The rotated refresh token replaced the consumed one — otherwise the next refresh 401s.
    expect(getRefreshToken()).toBe("refresh-2");
    expect(getAccessToken()).toBe("access-2");
    expect(seen.map((call) => call.url)).toEqual(["/users/me", "/auth/refresh", "/users/me"]);
    expect(seen[0].authorization).toBe("Bearer access-1");
    expect(seen[2].authorization).toBe("Bearer access-2");
  });

  it("queues concurrent 401s behind a single refresh call", async () => {
    let refreshCalls = 0;
    const failedOnce = new Set<string>();

    api.defaults.adapter = async (config) => {
      if (config.url === "/auth/refresh") {
        refreshCalls += 1;
        // Force both original requests to be waiting before the refresh settles.
        await new Promise((resolve) => setTimeout(resolve, 10));
        return ok(config, { success: true, data: tokenPair(2) });
      }
      const url = config.url ?? "";
      if (!failedOnce.has(url)) {
        failedOnce.add(url);
        throw unauthorized(config);
      }
      return ok(config, { success: true, data: url });
    };

    const [tasks, notifications] = await Promise.all([api.get("/tasks"), api.get("/notifications")]);

    expect(refreshCalls).toBe(1);
    expect(tasks.data).toEqual({ success: true, data: "/tasks" });
    expect(notifications.data).toEqual({ success: true, data: "/notifications" });
  });

  it("does not attempt a refresh when the login endpoint returns 401", async () => {
    clearTokens();
    let refreshCalls = 0;

    api.defaults.adapter = async (config) => {
      if (config.url === "/auth/refresh") {
        refreshCalls += 1;
        return ok(config, { success: true, data: tokenPair(2) });
      }
      throw unauthorized(config);
    };

    await expect(api.post("/auth/login", { email: "a@b.c", password: "nope" })).rejects.toThrow();
    expect(refreshCalls).toBe(0);
  });
});
