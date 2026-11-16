import { redirect } from "next/navigation";

export default async function ProjectIndex({ params }: PageProps<"/[project]">) {
  redirect(`/${(await params).project}/overview`);
}
