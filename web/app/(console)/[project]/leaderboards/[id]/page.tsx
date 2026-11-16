"use client";

import { useParams } from "next/navigation";
import { useMemo, useState, type FormEvent } from "react";
import { Button } from "@/components/Button";
import { DataTable } from "@/components/DataTable";
import { KeyValueList } from "@/components/KeyValueList";
import { Panel } from "@/components/Panel";
import { RANK_COLUMNS, RankRow } from "@/components/RankRow";
import { TextField } from "@/components/TextField";
import { ApiError, getSnapshotEntries, getTops, getUserRank, SNAPSHOT, type TopRankItem } from "@/lib/api";
import { fmtInt, fmtUtcTime } from "@/lib/format";
import { usePoll } from "@/lib/hooks";

const PAGE = 10;

export default function LeaderboardLivePage() {
  const { id } = useParams<{ id: string }>();
  const [offset, setOffset] = useState(0);
  // One extra row tells whether the last row on the page ties with the next page.
  const tops = usePoll(() => getTops(id, offset, PAGE + 1), undefined, `${id}:${offset}`);
  // The snapshot changes every 30 s; read the whole top N every 10 s to compare against live scores.
  const snap = usePoll(() => getSnapshotEntries(id, SNAPSHOT.topN), 10_000, id);
  const snapByUser = useMemo(() => new Map((snap.data?.items ?? []).map((e) => [e.userId, e])), [snap.data]);
  // undefined: no snapshot to compare with; null: user not in the snapshot's top N.
  const snapshotOf = (userId: string): TopRankItem | null | undefined => (snap.data ? (snapByUser.get(userId) ?? null) : undefined);

  const [draft, setDraft] = useState("");
  const [lookupId, setLookupId] = useState("");
  const lookup = usePoll(() => (lookupId ? getUserRank(id, lookupId) : Promise.resolve(null)), undefined, `${id}:${lookupId}`);
  const lookupError = lookup.error instanceof ApiError ? lookup.error.message : lookup.error !== undefined ? "Lookup failed." : undefined;

  const items = tops.data?.items ?? [];
  const page = items.slice(0, PAGE);
  const total = tops.data?.total ?? 0;
  const tied = (i: number) => {
    const r = items[i].rank;
    return r < offset + i + 1 || items[i + 1]?.rank === r;
  };
  const deltas = page.map((it) => {
    const s = snapshotOf(it.userId);
    return s ? it.score - s.score : null;
  });
  const changed = page.filter((it, i) => deltas[i] !== 0 || snapshotOf(it.userId)?.rank !== it.rank).length;
  const largest = Math.max(0, ...deltas.map((d) => d ?? 0));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setLookupId(draft.trim());
  };

  return (
    <div className="Split">
      <div className="Stack is-wide">
        <Panel title="User lookup" flush meta={<span className="mono">GET /leaderboards/{"{id}"}/users/{"{userId}"}</span>}>
          <div className="Panel-body">
            <form className="Inline" onSubmit={submit}>
              <TextField className="is-field" label="User ID" inputMode="numeric" value={draft} onChange={(e) => setDraft(e.target.value)} error={lookupError} />
              <Button type="submit">Look up</Button>
            </form>
          </div>
          {lookup.data && (
            <DataTable
              fixed
              divided
              columns={RANK_COLUMNS}
              rows={[lookup.data]}
              rowKey={(u) => u.userId}
              empty=""
              renderRow={(u) => <RankRow item={u} snapshot={snapshotOf(u.userId)} tied={false} highlighted />}
            />
          )}
        </Panel>

        <Panel
          title={offset === 0 ? "Top 10" : `Ranks ${offset + 1}–${offset + PAGE}`}
          flush
          meta={
            <>
              <span>Competition ranking · live from Redis</span>
              <span className="mono">GET /leaderboards/{"{id}"}/tops?limit=10</span>
            </>
          }
          foot={
            <>
              <span>Equal scores share a rank and the next is skipped. Ties sort by user ID.</span>
              <span className="Inline is-center">
                <span className="mono">
                  {total > 0 ? `${fmtInt(offset + 1)}–${fmtInt(offset + page.length)} of ${fmtInt(total)}` : "0 of 0"}
                </span>
                <Button size="sm" disabled={offset === 0} onClick={() => setOffset((o) => Math.max(0, o - PAGE))}>
                  Previous
                </Button>
                <Button size="sm" disabled={offset + PAGE >= total} onClick={() => setOffset((o) => o + PAGE)}>
                  Next
                </Button>
              </span>
            </>
          }
        >
          <DataTable
            fixed
            columns={RANK_COLUMNS}
            rows={page}
            rowKey={(it) => it.userId}
            empty={tops.error !== undefined ? "Rankings could not be read from Redis." : tops.data ? "No scores yet." : "Loading…"}
            renderRow={(it) => <RankRow item={it} snapshot={snapshotOf(it.userId)} tied={tied(items.indexOf(it))} />}
          />
        </Panel>
      </div>

      <Panel className="is-narrow" title="Redis and the latest snapshot" stack>
        <KeyValueList
          rows={[
            { label: "Live source", value: "Redis sorted set" },
            { label: "Participants", field: "total", value: tops.data ? fmtInt(total) : "—" },
            { label: "Latest snapshot", field: "snapshotId", value: snap.data ? fmtInt(snap.data.snapshotId) : "none yet", tone: snap.data ? undefined : "muted" },
            { label: "Taken at", field: "snapshotAt", value: snap.data ? fmtUtcTime(snap.data.snapshotAt) : "—" },
            { label: "Entries in snapshot", value: snap.data ? fmtInt(snap.data.total) : "—" },
            { label: "Snapshot interval", value: `${SNAPSHOT.intervalSeconds} s` },
            { label: `Top ${PAGE} changed since`, value: snap.data && page.length ? `${changed} of ${page.length}` : "—" },
            { label: "Largest gain since", value: snap.data && page.length ? `+${fmtInt(largest)}` : "—" },
          ]}
        />
        <p className="Panel-caption">
          Scores only increase, so a live score is never below its snapshot. If Redis loses its data, the ranking is rebuilt from the latest snapshot; score gained after it was taken is not recovered.
        </p>
      </Panel>
    </div>
  );
}
