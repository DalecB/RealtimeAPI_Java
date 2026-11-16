"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Button } from "@/components/Button";
import { IdCell } from "@/components/CopyButton";
import { NeedsEndpoint } from "@/components/NeedsEndpoint";
import { OperatorOnly } from "@/components/OperatorOnly";
import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { SloBudget, sloStatus, type SloStatus } from "@/components/SloBudget";
import { Sparkline } from "@/components/Sparkline";
import { StaleValue } from "@/components/StaleValue";
import { StatusBadge } from "@/components/StatusBadge";
import { BREAKERS, MISSING, SNAPSHOT } from "@/lib/api";
import { EVIDENCE, RELEASE } from "@/lib/evidence";
import { fmtDuration, fmtInt, fmtPercent, fmtUtcTime } from "@/lib/format";
import { useNow } from "@/lib/hooks";
import { usePipeline } from "@/lib/pipeline";
import { LeaderboardsPanel, useBoards } from "../LeaderboardsPanel";

const fmtLag = (s: number) => (s < 60 ? `${Math.round(s)} s` : fmtDuration(s));

export default function OverviewPage() {
  const { project } = useParams<{ project: string }>();
  const p = usePipeline();
  const now = useNow();
  const boards = useBoards(project);
  const cfg = BREAKERS.write;

  // Snapshot lag, live from /internal/snapshot/status.
  const snap = p.snapshot.data;
  const snapUsed = snap?.lastSuccessfulSnapshotAt ? snap.snapshotLagSeconds / SNAPSHOT.sloSeconds : null;
  const snapStatus: SloStatus = p.snapshot.error !== undefined ? "unmeasured" : sloStatus(snapUsed);

  // Write path, live from the write breaker.
  const br = p.breaker.data;
  const brUsed = br ? br.failureRate / cfg.openAtPercent : null;
  const brStatus: SloStatus = !br || p.breaker.error !== undefined ? "unmeasured" : br.state === "OPEN" ? "fail"
    // HALF_OPEN resets the window, so 0% here means "probing after an outage", not healthy.
    : br.state === "HALF_OPEN" ? "warn" : sloStatus(brUsed);
  const failedCalls = br ? Math.round((br.failureRate / 100) * cfg.windowCalls) : 0;

  const statuses = [snapStatus, brStatus, "unmeasured" as SloStatus];
  const count = (s: SloStatus) => statuses.filter((x) => x === s).length;
  const within = count("ok");
  const atRisk = count("warn");
  const breached = count("fail");

  return (
    <>
      <PageHeader
        title="Overview"
        meta={
          <>
            <IdCell id={project} label="Copy project ID" />
            <span>{boards.data ? `${fmtInt(boards.data.length)} leaderboards` : "leaderboards loading…"}</span>
            {within > 0 && <StatusBadge quiet status="ok" label={`${within} live objective${within > 1 ? "s" : ""} within SLO`} />}
            {atRisk > 0 && <StatusBadge quiet status="warn" label={`${atRisk} at risk`} />}
            {breached > 0 && <StatusBadge quiet status="fail" label={`${breached} breached`} />}
            {count("unmeasured") > 0 && <StatusBadge quiet status="idle" label={`${count("unmeasured")} not measured`} />}
          </>
        }
        actions={
          <>
            <Button href={`/${project}/sandbox`}>Send test event in Sandbox</Button>
            <Button variant="primary" href={`/${project}/leaderboards#new`}>
              New leaderboard
            </Button>
          </>
        }
      />

      <Panel title="Live objectives" flush meta={<span>Measured now, against each limit</span>}>
        <SloBudget
          kind="live"
          name="Snapshot lag"
          objective={`snapshot_lag_seconds < ${SNAPSHOT.sloSeconds} s`}
          source={
            p.snapshot.error !== undefined
              ? "status endpoint not answering"
              : snap?.lastSuccessfulSnapshotAt
                ? `last success ${fmtUtcTime(snap.lastSuccessfulSnapshotAt)}`
                : "no successful snapshot yet"
          }
          value={snap?.lastSuccessfulSnapshotAt ? (p.snapshot.error !== undefined && p.snapshot.okAt ? <StaleValue value={fmtLag(snap.snapshotLagSeconds)} asOf={p.snapshot.okAt} /> : fmtLag(snap.snapshotLagSeconds)) : null}
          detail={`interval ${SNAPSHOT.intervalSeconds} s`}
          used={snapUsed}
          scale={snapUsed === null ? "not measured" : <><b>{Math.round(snapUsed * 100)}%</b> of budget used</>}
          limitLabel={`limit ${SNAPSHOT.sloSeconds} s`}
          history="live value"
          status={snapStatus}
        />
        <SloBudget
          kind="live"
          name="Write path"
          objective={`breaker failure rate < ${cfg.openAtPercent}%`}
          source={`last ${cfg.windowCalls} calls to Redis`}
          value={br ? (p.breaker.error !== undefined && p.breaker.okAt ? <StaleValue value={fmtPercent(br.failureRate)} asOf={p.breaker.okAt} /> : fmtPercent(br.failureRate)) : null}
          detail={br ? `breaker ${br.state === "OPEN" ? "OPEN · 503" : br.state}` : "breaker not read yet"}
          used={brUsed}
          scale={br ? <><b>{failedCalls} of {cfg.windowCalls}</b> calls failed</> : "not measured"}
          limitLabel={`opens at ${cfg.openAtPercent}%`}
          history="live value"
          status={brStatus}
        />
        <SloBudget
          kind="live"
          name="Write error rate"
          objective="POST /events errors < 0.1%"
          source={<>needs <span className="mono">{MISSING.errorRate}</span></>}
          value={null}
          detail="last 5 min"
          used={null}
          scale="not measured"
          limitLabel="limit 0.1%"
          history="no series"
          status="unmeasured"
        />
      </Panel>

      <Panel title="Open incidents">
        <NeedsEndpoint what="Incident list" endpoint={MISSING.incidents} />
        {p.incident && (
          <p className="NeedsEndpoint">
            A hop is failing right now. <Link href={`/${project}/delivery`}>See Delivery</Link>.
          </p>
        )}
      </Panel>

      <LeaderboardsPanel projectId={project} rows={boards} now={now} />

      <OperatorOnly>
        <Panel title="Release evidence" flush meta={<span>k6 load tests on {RELEASE.redis} · latest run {RELEASE.commit} on {RELEASE.date} · not live</span>}>
          {EVIDENCE.map((e) => {
            const used = e.limit > 0 ? e.measured / e.limit : 0;
            const passed = e.unit === "count" ? e.measured <= e.limit : e.measured < e.limit;
            return (
              <SloBudget
                key={e.name}
                kind="evidence"
                name={e.name}
                objective={e.objective}
                source={e.source}
                value={e.unit === "ms" ? `${e.measured} ms` : fmtInt(e.measured)}
                detail={e.detail}
                used={used}
                scale={e.unit === "ms" ? <><b>{(used * 100).toFixed(1)}%</b> {e.scale}</> : <><b>{e.measured}</b> {e.scale}</>}
                limitLabel={e.unit === "ms" ? `limit ${e.limit} ms` : `limit ${e.limit}`}
                history={
                  e.history ? (
                    <>
                      <Sparkline
                        points={e.history.runs.map((r) => r.value)}
                        limit={e.limit}
                        label={`${e.history.label}: ${e.history.runs.map((r) => `${r.label} ${r.value}`).join(", ")}. Limit ${e.limit}.`}
                      />
                      <span>{e.history.caption}</span>
                    </>
                  ) : (
                    <span>single run</span>
                  )
                }
                status={passed ? "ok" : "fail"}
              />
            );
          })}
        </Panel>
      </OperatorOnly>
    </>
  );
}
