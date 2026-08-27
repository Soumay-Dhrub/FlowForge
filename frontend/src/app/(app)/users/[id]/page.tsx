import UserProfileForm from "@/components/users/UserProfileForm";

export default function UserProfilePage({ params }: { params: { id: string } }) {
  return <UserProfileForm userId={params.id} />;
}
