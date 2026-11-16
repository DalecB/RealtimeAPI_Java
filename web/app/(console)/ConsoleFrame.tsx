"use client";

import { useParams, usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { AppShell } from "@/components/AppShell";
import { IncidentBanner } from "@/components/IncidentBanner";
import { PipelineStrip } from "@/components/PipelineStrip";
import { SidebarNav } from "@/components/SidebarNav";
import { TopBar } from "@/components/TopBar";
import { getLeaderboard, getProject } from "@/lib/api";
import { fmtLocalTime, fmtUtcTime } from "@/lib/format";
import { POLL_MS, useNow, usePoll, useSession } from "@/lib/hooks";
import { PipelineProvider, usePipeline } from "@/lib/pipeline";
import type { Session } from "@/lib/session";

const SECTIONS: Record<string, string> = {
  overview: "Overview",
  leaderboards: "Leaderboards",
  delivery: "Delivery",
  incidents: "Incidents",
  "api-keys": "API keys",
  sandbox: "Sandbox",
};

export function ConsoleFrame({ children }: { children: ReactNode }) {
  const session = useSession();
  const router = useRouter();
  const pathname = usePathname();
  useEffect(() => {
    if (session === null) router.replace(`/login?next=${encodeURIComponent(pathname)}`);
  }, [session, router, pathname]);
  if (!session) return null;
  return (
    <PipelineProvider>
      <Frame session={session}>{children}</Frame>
    </PipelineProvider>
  );
}

function Frame({ session, children }: { session: Session; children: ReactNode }) {
  const { project, id } = useParams<{ project: string; id?: string }>();
  const section = usePathname().split("/")[2] ?? "overview";
  const pipeline = usePipeline();
  const now = useNow();
  const projectPoll = usePoll(() => getProject(project), 60_000, project);
  const boardPoll = usePoll(() => (id ? getLeaderboard(id) : Promise.resolve(null)), 60_000, id ?? "");

  const base = `/${project}`;
  const projectName = projectPoll.data?.name ?? null;
  const crumbs = [projectName ?? project, SECTIONS[section] ?? section];
  if (id) crumbs.push(boardPoll.data?.name ?? id);

  const pollFailing = !pipeline.apiReachable;
  const lastOk = pipeline.lastPollAt;

  return (
    <AppShell
      sidebar={
        <SidebarNav
          project={{ id: project, name: projectName }}
          activeHref={SECTIONS[section] ? `${base}/${section}` : null}
          nav={[
            { label: "Overview", href: `${base}/overview` },
            { label: "Leaderboards", href: `${base}/leaderboards` },
            { label: "Delivery", href: `${base}/delivery` },
            { label: "Incidents", href: `${base}/incidents` },
          ]}
          build={[
            { label: "API keys", href: `${base}/api-keys` },
            { label: "Sandbox", href: `${base}/sandbox` },
          ]}
          signedInAs={session.externalId}
        />
      }
      topBar={<TopBar crumbs={crumbs} role={session.role} lastPollAt={lastOk} pollFailing={pollFailing} now={now} />}
      strip={<PipelineStrip hops={pipeline.hops} dlt={pipeline.dlt} deliveryHref={`${base}/delivery`} />}
      banner={
        pollFailing ? (
          <IncidentBanner
            severity="warn"
            title="The console cannot reach the API"
            body={
              lastOk
                ? `Values marked with a clock were last read at ${fmtUtcTime(lastOk)} · ${fmtLocalTime(lastOk)}. Polling continues every ${POLL_MS / 1000} s.`
                : `No status has been read yet. Polling continues every ${POLL_MS / 1000} s.`
            }
          />
        ) : undefined
      }
    >
      {children}
    </AppShell>
  );
}
