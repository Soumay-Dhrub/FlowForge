import { render, screen } from "@testing-library/react";
import SidebarNav, { navItemsForRole } from "@/components/layout/SidebarNav";

let pathname = "/dashboard";

jest.mock("next/navigation", () => ({
  usePathname: () => pathname,
}));

describe("SidebarNav", () => {
  beforeEach(() => {
    pathname = "/dashboard";
  });

  it("shows every section to an ADMIN", () => {
    render(<SidebarNav role="ADMIN" />);

    ["Dashboard", "Workflows", "Tasks", "Reports", "Users", "Audit Logs"].forEach((label) => {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    });
  });

  it.each(["MANAGER", "EMPLOYEE"] as const)("hides the ADMIN-only sections from a %s", (role) => {
    render(<SidebarNav role={role} />);

    expect(screen.getByRole("link", { name: "Dashboard" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Workflows" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Tasks" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Users" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Audit Logs" })).not.toBeInTheDocument();
  });

  // Analytics are ADMIN/MANAGER: an aggregate still describes how an organisation's reviewers
  // perform, which is not something every employee should be able to pull.
  it("offers Reports to a MANAGER", () => {
    render(<SidebarNav role="MANAGER" />);

    expect(screen.getByRole("link", { name: "Reports" })).toBeInTheDocument();
  });

  it("hides Reports from an EMPLOYEE", () => {
    render(<SidebarNav role="EMPLOYEE" />);

    expect(screen.queryByRole("link", { name: "Reports" })).not.toBeInTheDocument();
  });

  it("shows only the unrestricted sections when the role is not yet known", () => {
    expect(navItemsForRole(undefined).map((item) => item.label)).toEqual([
      "Dashboard",
      "Workflows",
      "Tasks",
    ]);
  });

  it("marks the current section, including nested pages under it", () => {
    pathname = "/workflows/abc-123/edit";
    render(<SidebarNav role="ADMIN" />);

    expect(screen.getByRole("link", { name: "Workflows" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Dashboard" })).not.toHaveAttribute("aria-current");
  });
});
