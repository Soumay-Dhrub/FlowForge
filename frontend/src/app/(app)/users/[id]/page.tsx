/**
 * `/users/[id]` — profile edit form (task 38).
 *
 * Reachable by an ADMIN for anyone and by a user for their own record, which is exactly what
 * `GET/PATCH /api/users/{id}` permits.
 */
import UserProfileForm from "@/components/users/UserProfileForm";

export default function UserProfilePage({ params }: { params: { id: string } }) {
  return <UserProfileForm userId={params.id} />;
}
