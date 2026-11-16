"use client";

import Link from "next/link";
import { DataTable } from "@/components/DataTable";
import { IdCell } from "@/components/CopyButton";
import { FreshnessIndicator } from "@/components/FreshnessIndicator";
import { Panel } from "@/components/Panel";
import { StatusBadge } from "@/components/StatusBadge";
import { getLeaderboards, getSnapshotEntries, getTops, SNAPSHOT } from "@/lib/api";
import { fmtInt, secondsSince } from "@/lib/format";
import { usePoll } from "@/lib/hooks";

// ponytail: one tops and one snapshot call per leaderboard on every poll (N+1). Fine for tens of boards;
// the upgrade is a list endpoint that returns participants, top score and snapshotAt per leaderboard.
async function loadBoards(projectId: string) {
  const boards = await getLeaderboards(projectId);
  return Promise.all(
    boards.map(async (board) => {
      const [tops, snap] = await Promise.all([
        getTops(board.id, 0, 1).catch(() => undefined),
        getSnapshotEntries(board.id, 1).catch(() => undefined),
      ]);
      return { board, tops, snap };
    }),
  );
}

export function useBoards(projectId: string) {
  return usePoll(() => loadBoards(projectId), 5000, projectId);
}

export function LeaderboardsPanel({ projectId, rows, now }: { projectId: string; rows: ReturnType<typeof useBoards>; now: number }) {
  return (
    <Panel
      title="Leaderboards"
      flush
      meta={<span>{rows.data ? fmtInt(rows.data.length) : "…"}</span>}
      foot={
        <span>
          {rows.error !== undefined && <span className="t-fail">Leaderboards could not be refreshed; showing the last list. </span>}
          Participants is the live count in Redis. Snapshots cover the top {fmtInt(SNAPSHOT.topN)} of each leaderboard; an empty leaderboard is never snapshotted.
        </span>
      }
    >
      <DataTable
        columns={[
          { key: "name", header: "Name" },
          { key: "id", header: "Leaderboard ID" },
          { key: "participants", header: "Participants", align: "right" },
          { key: "top", header: "Top score", align: "right" },
          { key: "snapshot", header: "Snapshot" },
          { key: "status", header: "Status" },
        ]}
        rows={rows.data ?? []}
        rowKey={(r) => r.board.id}
        empty={rows.data ? "No leaderboards in this project yet." : rows.error !== undefined ? "Leaderboards could not be loaded." : "Loading…"}
        renderRow={({ board, tops, snap }) => {
          const total = tops?.total;
          return (
            <tr>
              <td>
                <Link className="DataTable-link" href={`/${projectId}/leaderboards/${board.id}`}>
                  {board.name}
                </Link>
              </td>
              <td>
                <IdCell id={board.id} label={`Copy ID of ${board.name}`} muted />
              </td>
              <td className={total === undefined ? "num muted" : "num"}>{total === undefined ? "unknown" : fmtInt(total)}</td>
              <td className={tops?.items[0] ? "num" : "num muted"}>{tops === undefined ? "unknown" : tops.items[0] ? fmtInt(tops.items[0].score) : "none"}</td>
              <td>{snap === undefined ? <span className="muted">unknown</span> : <FreshnessIndicator lagSeconds={snap ? secondsSince(snap.snapshotAt, now) : null} />}</td>
              <td>{total === undefined ? <StatusBadge quiet status="idle" label="Unknown" /> : total > 0 ? <StatusBadge quiet status="ok" label="Active" /> : <StatusBadge quiet status="idle" label="Empty" />}</td>
            </tr>
          );
        }}
      />
    </Panel>
  );
}
