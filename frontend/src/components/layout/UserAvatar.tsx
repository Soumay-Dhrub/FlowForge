"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { LogOut } from "lucide-react";
import { useAuth } from "@/context/AuthContext";
import usePopover from "@/lib/usePopover";

/** Up to two initials, so "Ada Lovelace" reads as AL and a single name still renders. */
export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return "?";
  }
  const letters = parts.length === 1 ? [parts[0][0]] : [parts[0][0], parts[parts.length - 1][0]];
  return letters.join("").toUpperCase();
}

export function UserAvatar() {
  const { user, logout } = useAuth();
  const router = useRouter();
  const { open, toggle, containerRef, triggerRef } = usePopover();
  const [signingOut, setSigningOut] = useState(false);

  if (!user) {
    return null;
  }

  const onLogout = async () => {
    setSigningOut(true);
    await logout();
    router.replace("/login");
  };

  return (
    <div className="relative" ref={containerRef}>
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls="profile-menu"
        className="flex items-center gap-2 rounded-full py-1 pl-1 pr-2 hover:bg-gray-100 focus:ring-offset-2"
      >
        <span
          aria-hidden="true"
          className="flex h-8 w-8 items-center justify-center rounded-full bg-primary-600 text-xs font-semibold text-white"
        >
          {initialsOf(user.name)}
        </span>
        <span className="hidden text-left sm:block">
          <span className="block text-sm font-medium leading-tight text-gray-900">{user.name}</span>
          <span className="block text-xs leading-tight text-gray-500">{user.roleName}</span>
        </span>
      </button>

      {open ? (
        <div
          id="profile-menu"
          className="absolute right-0 z-20 mt-2 w-56 rounded-xl border border-gray-200 bg-white shadow-popover"
        >
          <div className="border-b border-gray-200 px-4 py-3">
            <p className="text-sm font-medium text-gray-900">{user.name}</p>
            <p className="truncate text-xs text-gray-500">{user.email}</p>
            <p className="mt-1 text-xs font-medium text-primary-700">{user.roleName}</p>
          </div>
          <button
            type="button"
            onClick={onLogout}
            disabled={signingOut}
            aria-busy={signingOut}
            className="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-primary-500 disabled:cursor-not-allowed disabled:opacity-60"
          >
            <LogOut aria-hidden="true" className="h-4 w-4" />
            {signingOut ? "Signing out…" : "Sign out"}
          </button>
        </div>
      ) : null}
    </div>
  );
}

export default UserAvatar;
