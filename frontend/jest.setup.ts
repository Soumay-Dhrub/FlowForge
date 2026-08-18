import "@testing-library/jest-dom";
import { destroyTestQueryClients } from "@/test/renderWithQuery";

// Testing Library unmounts the tree after each test, but a QueryClient outlives its provider: it keeps
// garbage-collection timers, and any query with a refetchInterval keeps rescheduling itself. Left
// behind, those handles stop the Jest worker exiting -- harmless noise locally, a hung job on CI.
// Clients created through the test helper register themselves, so this disposes of all of them.
afterEach(() => {
  destroyTestQueryClients();
});
