/**
 * `/users` — user management (task 38). ADMIN only.
 *
 * The sidebar hides the link for everyone else, but that is presentation: a MANAGER can still type
 * the URL, so the page itself refuses deliberately with a "not authorized" panel rather than showing
 * a table that fails or a raw 403.
 */
import UserList from "@/components/users/UserList";

export default function UsersPage() {
  return <UserList />;
}
