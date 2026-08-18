"use client";

/**
 * Sidebar navigation. Links the caller's role cannot use are not rendered at all rather than
 * disabled: a MANAGER has no business seeing a greyed-out "Users" entry advertising a page that
 * would answer them 403.
 *
 * This is presentation, not authorization. The API enforces RBAC on every request
 * (Requirement 3.2), so hiding a link removes a dead end from the UI — it does not protect the
 * endpoint behind it.
 */
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  BarChart3,
  ClipboardList,
  GitBranch,
  LayoutDashboard,
  ScrollText,
  Users,
  type LucideIcon,
} from "lucide-react";
import type { RoleName } from "@/types";

interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  /** Roles allowed to see the link; `undefined` means every authenticated role. */
  roles?: readonly RoleName[];
}

export const NAV_ITEMS: readonly NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutDashboard },
  { href: "/workflows", label: "Workflows", icon: GitBranch },
  { href: "/tasks", label: "Tasks", icon: ClipboardList },
  { href: "/reports", label: "Reports", icon: BarChart3, roles: ["ADMIN", "MANAGER"] },
  { href: "/users", label: "Users", icon: Users, roles: ["ADMIN"] },
  { href: "/audit-logs", label: "Audit Logs", icon: ScrollText, roles: ["ADMIN"] },
];

/** The links a role may use. An unknown or absent role gets only the unrestricted ones. */
export function navItemsForRole(role: RoleName | undefined): readonly NavItem[] {
  return NAV_ITEMS.filter((item) => !item.roles || (role !== undefined && item.roles.includes(role)));
}

/** True for the item's own page and anything below it, so `/workflows/123/edit` still highlights. */
function isActive(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function SidebarNav({ role }: { role: RoleName | undefined }) {
  const pathname = usePathname() ?? "";
  const items = navItemsForRole(role);

  return (
    <nav aria-label="Main navigation" className="px-3 py-3">
      <ul className="space-y-0.5">
        {items.map(({ href, label, icon: Icon }) => {
          const active = isActive(pathname, href);
          return (
            <li key={href}>
              <Link
                href={href}
                aria-current={active ? "page" : undefined}
                className={`group relative flex items-center gap-3 rounded-md px-3 py-2 text-sm transition-colors ${
                  active
                    ? "bg-primary-50 font-semibold text-primary-700"
                    : "font-medium text-gray-600 hover:bg-gray-100 hover:text-gray-900"
                }`}
              >
                {/*
                  A bar as well as the tint. The tint alone is a ~4% brightness difference, which is
                  invisible on a dim or badly calibrated monitor and carries nothing for anyone who
                  cannot separate the hues — and "which page am I on" is the one thing navigation must
                  never be ambiguous about.
                */}
                {active ? (
                  <span
                    aria-hidden
                    className="absolute inset-y-1.5 left-0 w-0.5 rounded-full bg-primary-600"
                  />
                ) : null}
                <Icon
                  aria-hidden="true"
                  className={`h-4 w-4 shrink-0 transition-colors ${
                    active ? "text-primary-600" : "text-gray-400 group-hover:text-gray-600"
                  }`}
                />
                {label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

export default SidebarNav;
