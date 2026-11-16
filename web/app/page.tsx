"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { DataTable } from "@/components/DataTable";
import { IdCell } from "@/components/CopyButton";
import { Panel } from "@/components/Panel";
import { getProjects } from "@/lib/api";
import { fmtUtcTime } from "@/lib/format";
import { usePoll, useSession } from "@/lib/hooks";

// Project switcher: the sidebar's project button lands here.
export default function ProjectsPage() {
  const session = useSession();
  const router = useRouter();
  useEffect(() => {
    if (session === null) router.replace("/login");
  }, [session, router]);
  const projects = usePoll(getProjects, 30_000);
  if (!session) return null;
  return (
    <main className="LoginPage" id="content">
      <Panel
        title="Projects"
        className="is-wide"
        flush
        meta={<span>{projects.data ? projects.data.length : "…"}</span>}
        foot={projects.error !== undefined ? <span className="t-fail">Projects could not be loaded. Retrying every 30 s.</span> : undefined}
      >
        <DataTable
          columns={[
            { key: "name", header: "Name" },
            { key: "id", header: "Project ID" },
            { key: "created", header: "Created" },
          ]}
          rows={projects.data ?? []}
          rowKey={(p) => p.id}
          empty={projects.data ? "No projects yet. Create one with POST /projects." : "Loading…"}
          renderRow={(p) => (
            <tr>
              <td>
                <Link className="DataTable-link" href={`/${p.id}/overview`}>
                  {p.name}
                </Link>
              </td>
              <td>
                <IdCell id={p.id} label={`Copy ID of ${p.name}`} muted />
              </td>
              <td className="mono">{p.createdAt.slice(0, 10)} {fmtUtcTime(p.createdAt)}</td>
            </tr>
          )}
        />
      </Panel>
    </main>
  );
}
