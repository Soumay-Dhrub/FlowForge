"use client";

/**
 * Mounts React Query for the whole app. The login flow did not need it, so nothing had provided a
 * client until now; the notification bell is the first `useQuery` caller and would throw without
 * one.
 *
 * The client is created in state rather than at module scope so a fresh one is made per browser
 * session instead of being shared across requests during server rendering.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // A 401 is handled by the API interceptor (refresh + replay) and, if that fails, by
            // AuthProvider dropping the session. Retrying here would only delay that.
            retry: 1,
            staleTime: 10_000,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}

export default QueryProvider;
