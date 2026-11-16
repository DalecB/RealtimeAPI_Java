"use client";

import { useParams, usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { IdCell } from "@/components/CopyButton";
import { FreshnessIndicator } from "@/components/FreshnessIndicator";
import { PageHeader } from "@/components/PageHeader";
import { StatusBadge } from "@/components/StatusBadge";
import { Tabs } from "@/components/Tabs";
import { getLeaderboard, getSnapshotEntries, getTops } from "@/lib/api";
import { fmtInt, secondsSince } from "@/lib/format";
import { useNow, usePoll } from "@/lib/hooks";
import { TestEvent } from "./TestEvent";

// Header and tabs shared by Live, Events and Snapshot.
export default function LeaderboardLayout({ children }: { children: ReactNode }) {
  const { project, id } = useParams<{ project: string; id: string }>();
  const pathname = usePathname();
  const now = useNow();
  const board = usePoll(() => getLeaderboard(id), 60_000, id);
  const tops = usePoll(() => getTops(id, 0, 1), undefined, id);
  const snap = usePoll(() => getSnapshotEntries(id, 1), 10_000, id);

  const base = `/${project}/leaderboards/${id}`;
  const tabs = [
    { label: "Live", href: base },
    { label: "Events", href: `${base}/events` },
    { label: "Snapshot", href: `${base}/snapshot` },
  ];
  const active = tabs.findLast((t) => pathname.startsWith(t.href))?.label ?? "Live";
  const total = tops.data?.total;

  return (
    <>
      <PageHeader
        title={board.data?.name ?? (board.error !== undefined ? "Leaderboard not found" : "…")}
        meta={
          <>
            <IdCell id={id} label="Copy leaderboard ID" />
            {total !== undefined && <span>{fmtInt(total)} participants</span>}
            {total !== undefined && (total > 0 ? <StatusBadge quiet status="ok" label="Active" /> : <StatusBadge quiet status="idle" label="Empty" />)}
            {snap.data !== undefined && <FreshnessIndicator lagSeconds={snap.data ? secondsSince(snap.data.snapshotAt, now) : null} />}
          </>
        }
        actions={board.data && <TestEvent project={project} board={board.data} participants={total} />}
      />
      <Tabs label="Leaderboard sections" items={tabs} active={active} />
      {children}
    </>
  );
}
