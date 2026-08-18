/**
 * Rendering a component that uses React Query, without leaking timers.
 *
 * Every test file used to build its own `QueryClient` inline and never dispose of it. Testing Library
 * unmounts the tree after each test, which stops in-flight fetches, but the client itself survives
 * holding garbage-collection timers — five minutes by default — and a `refetchInterval` on any polling
 * query keeps rescheduling. Jest then reports "a worker process has failed to exit gracefully", and on
 * CI that is the difference between a suite that finishes and one that hangs until the job times out.
 *
 * So the client is created here with caching effectively switched off and registered for teardown. The
 * settings are deliberate rather than convenient:
 *
 *  - `retry: false` — a test asserting an error state should see it on the first failure, not after
 *    three silent retries and a timeout.
 *  - `gcTime: 0` — nothing is kept after the last observer unmounts, so no cleanup timer is armed.
 *  - `staleTime: 0` — each test starts from a cold cache, so tests cannot pass because an earlier one
 *    happened to populate the same key.
 */
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, type RenderOptions, type RenderResult } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";

/** Clients handed out during the current test, torn down by the global afterEach in jest.setup.ts. */
const liveClients = new Set<QueryClient>();

/**
 * A QueryClient configured for tests and registered for automatic teardown.
 *
 * Use this directly only when a test needs the client itself — to seed a cache entry, or to assert an
 * invalidation. Otherwise prefer {@link renderWithQuery}.
 */
export function createTestQueryClient(): QueryClient {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0, staleTime: 0 },
      mutations: { retry: false },
    },
  });
  liveClients.add(client);
  return client;
}

/**
 * Stop and forget every client created during the test.
 *
 * `cancelQueries` first, so a request still in flight does not settle into a cleared cache and warn
 * about setting state on an unmounted tree; then `clear`, which drops the cache and its timers;
 * then `unmount`, which detaches the client's own listeners.
 */
export function destroyTestQueryClients(): void {
  liveClients.forEach((client) => {
    void client.cancelQueries();
    client.clear();
    client.unmount();
  });
  liveClients.clear();
}

/**
 * Render a component inside a disposable QueryClientProvider.
 *
 * @param ui      the element under test
 * @param options passed through to Testing Library, minus the wrapper this supplies
 * @returns the usual render result, plus the `queryClient` for tests that need it
 */
export function renderWithQuery(
  ui: ReactElement,
  options?: Omit<RenderOptions, "wrapper">,
): RenderResult & { queryClient: QueryClient } {
  const queryClient = createTestQueryClient();

  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  }

  return { ...render(ui, { wrapper: Wrapper, ...options }), queryClient };
}
