"use client";

import { useParams } from "next/navigation";
import { useState, type CSSProperties, type ReactNode } from "react";
import { Button } from "@/components/Button";
import { CollapsedHops } from "@/components/CollapsedHops";
import { HopGroups } from "@/components/HopGroups";
import { IncidentBanner, type Impact } from "@/components/IncidentBanner";
import { KeyValueList, type KeyValueRow } from "@/components/KeyValueList";
import { NeedsEndpoint } from "@/components/NeedsEndpoint";
import { OperatorOnly } from "@/components/OperatorOnly";
import { OutcomeBreakdown } from "@/components/OutcomeBreakdown";
import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { PipelineHop, type HopLink } from "@/components/PipelineHop";
import { Reconcile } from "@/components/Reconcile";
import { Sparkline } from "@/components/Sparkline";
import { StaleValue } from "@/components/StaleValue";
import { StatusBadge } from "@/components/StatusBadge";
import { BREAKERS, MISSING } from "@/lib/api";
import { fmtDuration, fmtInt, fmtLocalTime, fmtPercent, fmtUtcTime } from "@/lib/format";
import { isStale, useNow, useSession, type Poll } from "@/lib/hooks";
import { usePipeline, type Hop, type HopKey, type Pipeline } from "@/lib/pipeline";

const cfg = BREAKERS.write;

// A value from a poll that is failing now is shown as last known, never as current.
function val<T>(poll: Poll<T>, read: (d: T) => ReactNode, tone?: "warn"): ReactNode {
  if (poll.data === undefined) return "—";
  return isStale(poll) && poll.okAt ? <StaleValue value={read(poll.data)} asOf={poll.okAt} tone={tone} /> : read(poll.data);
}

function hopBody(key: HopKey, p: Pipeline, incident: boolean): { rows: KeyValueRow[]; note?: string; resource?: string; children?: ReactNode } {
  const { breaker, streams, topic, stored } = p;
  const kafkaDown = topic.data !== undefined && topic.data.totalMessages < 0;
  switch (key) {
    case "write-path": {
      const open = breaker.data?.state === "OPEN";
      const failed = breaker.data ? Math.round((breaker.data.failureRate / 100) * cfg.windowCalls) : 0;
      return {
        rows: [
          { label: "State", field: "state", value: val(breaker, (b) => b.state), strong: true, tone: open ? "fail" : undefined },
          { label: "Failure rate", field: "failureRate", value: val(breaker, (b) => (open ? `${fmtPercent(b.failureRate)} · ${failed} of ${cfg.windowCalls}` : fmtPercent(b.failureRate))) },
          { label: "Opens at", value: `${cfg.openAtPercent}%` },
          { label: "Window", value: `${cfg.windowCalls} calls` },
          { label: "While open", value: "503" },
          { label: "Retry-After", value: `${cfg.retryAfterSeconds} s` },
        ],
        children: <OutcomeBreakdown counts={null} sampleLabel="Last 50 POST /events" />,
      };
    }
    case "stream": {
      const down = p.hops[1].status === "fail";
      return {
        resource: down && streams.okAt ? `audit outbox · last read ${fmtUtcTime(streams.okAt)}` : "audit outbox",
        rows: [
          { label: "Unread", field: "consumerGroupLag", value: val(streams, (s) => fmtInt(s.consumerGroupLag)), strong: true },
          { label: "Unacked", field: "pendingEntries", value: val(streams, (s) => fmtInt(s.pendingEntries), "warn"), strong: true, note: down ? "re-sent on recovery" : undefined },
          { label: "Length", field: "streamLength", value: val(streams, (s) => fmtInt(s.streamLength)) },
        ],
        note: down
          ? "The clock marks values last read before Redis stopped answering. The relay sends unacked entries again on recovery and PostgreSQL keeps one row each."
          : "Unacked entries were read by the relay but not yet confirmed. Length includes delivered entries; it is not backlog.",
        children:
          incident && p.unackedSeries.length > 1 ? (
            <div className="PH-block">
              <Sparkline points={p.unackedSeries} tone={down ? "warn" : "neutral"} label={`Unacked entries over the last ${p.unackedSeries.length} successful polls: ${p.unackedSeries.map(fmtInt).join(", ")}`} />
              <span className="Sparkline-caption">Unacked, last {p.unackedSeries.length} successful polls</span>
            </div>
          ) : undefined,
      };
    }
    case "relay":
      return {
        rows: [],
        children: <NeedsEndpoint what="Leader, lock and standby" endpoint={MISSING.relay} />,
        note: "One instance holds the PostgreSQL advisory lock and relays; a standby takes over on its next tick if the leader's session drops.",
      };
    case "kafka":
      return {
        rows: [
          { label: "Messages", field: "totalMessages", value: kafkaDown ? "unreadable" : val(topic, (t) => fmtInt(t.totalMessages)), tone: kafkaDown ? "muted" : undefined },
          { label: "Lag", field: "consumerLag", value: kafkaDown ? "unreadable" : val(topic, (t) => fmtInt(t.consumerLag)), strong: true, tone: kafkaDown ? "muted" : undefined },
        ],
        children: (
          <div className="PH-block">
            <NeedsEndpoint what="Per-partition lag" endpoint={MISSING.partitions} />
          </div>
        ),
      };
    case "consumer": {
      const retrying = topic.data?.consumerState === "DB_RETRYING";
      const rows: KeyValueRow[] = [
        { label: "State", field: "consumerState", value: val(topic, (t) => t.consumerState), strong: true, tone: topic.data?.consumerState === "STOPPED" ? "fail" : retrying ? "warn" : undefined },
        { label: "Ready", field: "consumerReady", value: val(topic, (t) => String(t.consumerReady)) },
        { label: "Retries", field: "retryAttempts", value: val(topic, (t) => fmtInt(t.retryAttempts)) },
      ];
      if (retrying) rows.push({ label: "Retrying for", field: "retryDurationSeconds", value: val(topic, (t) => fmtDuration(t.retryDurationSeconds)) });
      return { rows, note: "Offsets are committed only after the batch is stored." };
    }
    case "postgres":
      return {
        rows: [
          { label: "Rows", field: "count", value: val(stored, fmtInt), strong: true },
          { label: "Duplicates", value: "ignored" },
        ],
        note: "One row per event, even when it is delivered twice.",
      };
  }
}

// Rail links: dashed on either side of a failing hop, in pipeline order.
function links(hops: Hop[], i: number): { linkIn: HopLink; linkOut: HopLink } {
  const fail = (j: number) => hops[j]?.status === "fail";
  return {
    linkIn: fail(i) || fail(i - 1) ? "blocked" : i === 0 ? "none" : "flowing",
    linkOut: i === hops.length - 1 ? "none" : fail(i) || fail(i + 1) ? "blocked" : "flowing",
  };
}

function describe(p: Pipeline, now: number): { title: string; impact: Impact; next?: ReactNode; facts: string[] } {
  const since = p.incidentSince;
  const facts = since ? [`seen since ${fmtUtcTime(since)} · ${fmtLocalTime(since)}`, `${fmtDuration((now - since) / 1000)} ago`] : [];
  const audit = p.hops[4].status === "ok" && p.hops[5].status === "ok" ? "Events already in Kafka are still being stored." : "Audit storage is affected too; see the hops below.";
  const cause = p.hops.find((h) => h.status === "fail");
  const b = p.breaker.data;

  if (b?.state === "OPEN") {
    const failed = Math.round((b.failureRate / 100) * cfg.windowCalls);
    facts.push(`failure rate ${fmtPercent(b.failureRate)} · ${failed} of last ${cfg.windowCalls} calls`);
    return {
      title: p.redisOutage ? "Circuit open · Redis is not responding" : "Circuit open · writes fail fast",
      impact: {
        writes: `POST /events returns 503 with Retry-After: ${cfg.retryAfterSeconds}. No score is partially applied.`,
        reads: p.redisOutage
          ? "GET tops and user rank read Redis directly, outside the breaker. They return errors, not the fast 503."
          : "GET tops and user rank do not go through this breaker.",
        audit,
      },
      next: (
        <OperatorOnly>
          <p className="IncidentBanner-next">
            {p.redisOutage ? (
              <>
                <b>Action needed:</b> restore Redis.{" "}
              </>
            ) : (
              <>
                <b>Check</b> whether the breaker was opened on purpose (snapshot recovery opens it by hand).{" "}
              </>
            )}
            The breaker allows {cfg.trialCalls} trial calls {cfg.retryAfterSeconds} s after it opens and closes once they succeed.
          </p>
        </OperatorOnly>
      ),
      facts,
    };
  }
  switch (cause?.key) {
    case "stream":
      return {
        title: "Redis Stream is not responding",
        impact: {
          writes: `POST /events still goes through; if Redis is down, the breaker opens once ${cfg.openAtPercent}% of the last ${cfg.windowCalls} calls fail.`,
          reads: "GET tops and user rank read Redis directly and may return errors.",
          audit: "The relay cannot read new audit entries until Redis answers.",
        },
        facts,
      };
    case "kafka":
      return {
        title: "Kafka is not responding",
        impact: {
          writes: "POST /events is accepted. Audit entries wait in the Redis Stream.",
          reads: "Rankings are unaffected.",
          audit: "Nothing new reaches PostgreSQL until Kafka answers; the relay retries on every tick.",
        },
        facts,
      };
    case "consumer":
      return {
        title: "Audit consumer stopped",
        impact: {
          writes: "POST /events is accepted.",
          reads: "Rankings are unaffected.",
          audit: "Offsets are not committed, so records stay in Kafka and are stored once the consumer runs again.",
        },
        next: (
          <OperatorOnly>
            <p className="IncidentBanner-next">
              <b>Action needed:</b> fix the database error and restart the app. A permanent database error stops the consumer on purpose.
            </p>
          </OperatorOnly>
        ),
        facts,
      };
    default:
      return {
        title: "PostgreSQL is not responding",
        impact: {
          writes: "POST /events is accepted; rankings live in Redis.",
          reads: "Rankings are unaffected.",
          audit: "Audit rows are held in Kafka; offsets are not committed until the batch is stored.",
        },
        next: (
          <p className="IncidentBanner-next">
            <b>No action needed</b> unless PostgreSQL stays down; the consumer retries by itself.
          </p>
        ),
        facts,
      };
  }
}

export default function DeliveryPage() {
  const { project } = useParams<{ project: string }>();
  const p = usePipeline();
  const now = useNow();
  const operator = useSession()?.role === "operator";
  const [fields, setFields] = useState(false);

  const visible = p.hops.filter((h) => operator || h.key !== "relay");
  const renderHop = (h: Hop) => {
    const body = hopBody(h.key, p, p.incident);
    const hop = (
      <PipelineHop
        key={h.key}
        id={h.key}
        step={h.step}
        title={h.title}
        status={h.status}
        statusLabel={h.summary}
        resource={body.resource ?? h.resource}
        rows={body.rows}
        note={body.note}
        cause={p.redisOutage && h.key === "stream" ? "caused by Redis" : undefined}
        {...links(p.hops, h.step)}
      >
        {body.children}
      </PipelineHop>
    );
    return h.key === "relay" ? <OperatorOnly key={h.key}>{hop}</OperatorOnly> : hop;
  };
  const grid = (hops: Hop[]) => (
    <div className="PipelineHops" style={{ "--hops": hops.length } as CSSProperties}>
      {hops.map(renderHop)}
    </div>
  );

  const t = p.topic.data;
  const reconcileReady = t !== undefined && t.totalMessages >= 0 && p.stored.data !== undefined;
  const writesBlocked = p.breaker.data?.state === "OPEN";
  const headerBadge = !p.apiReachable ? (
    <StatusBadge quiet status="idle" label="Not answering" />
  ) : writesBlocked ? (
    <StatusBadge quiet status="fail" label="Writes blocked" />
  ) : p.incident ? (
    <StatusBadge quiet status="fail" label="Audit delivery affected" />
  ) : p.breaker.data ? (
    <StatusBadge quiet status="ok" label="Delivering" />
  ) : null;

  const attention = visible.filter((h) => h.status === "fail" || h.status === "warn");
  const rest = visible.filter((h) => h.status === "ok" || h.status === "idle");
  const okCount = rest.filter((h) => h.status === "ok").length;
  const idleCount = rest.length - okCount;
  const incident = p.incident ? describe(p, now) : null;

  return (
    <div className={fields ? "Stack lc-fields" : "Stack"}>
      <PageHeader
        title="Delivery"
        meta={
          <>
            <span>Write path and audit pipeline for every leaderboard in this project</span>
            {headerBadge}
          </>
        }
        actions={
          <OperatorOnly>
            <Button aria-pressed={fields} onClick={() => setFields((f) => !f)}>
              Show API fields
            </Button>
          </OperatorOnly>
        }
      />

      {incident && (
        <IncidentBanner
          severity="fail"
          title={incident.title}
          impact={incident.impact}
          next={incident.next}
          facts={incident.facts}
          announce={p.announce}
          actions={<Button href={`/${project}/incidents`}>View incident</Button>}
        />
      )}

      <OperatorOnly>
        {reconcileReady && t && (
          <Reconcile
            published={t.totalMessages}
            stored={p.stored.data!}
            quarantined={t.dltTotalMessages}
            inFlight={t.consumerLag}
            live={!isStale(p.topic) && !isStale(p.stored) && p.hops[1].status !== "fail"}
            unacked={p.hops[1].status === "fail" && p.hops[1].stale ? { value: p.streams.data!.pendingEntries, asOf: p.hops[1].stale.asOf } : undefined}
          />
        )}
      </OperatorOnly>

      {incident ? (
        <Panel title="Needs attention" flush meta={<span>{attention.length} of {visible.length} hops · in pipeline order</span>}>
          {p.redisOutage && attention.length > 1 && (
            <HopGroups cause={{ label: "Redis is not responding", span: 1 }} effects={{ label: "caused by Redis; they clear when it returns", span: attention.length - 1 }} />
          )}
          {grid(attention)}
          {rest.length > 0 && (
            <CollapsedHops
              id="healthy-hops"
              title={`Healthy · ${okCount} hop${okCount === 1 ? "" : "s"}${idleCount ? ` · ${idleCount} not reported` : ""}`}
              hops={rest.map((h) => ({ step: h.step, title: h.title, summary: h.summary, status: h.status }))}
            >
              {grid(rest)}
            </CollapsedHops>
          )}
        </Panel>
      ) : (
        <Panel title="Pipeline" flush meta={<span>Each accepted event is published at least once and stored exactly once</span>}>
          {grid(visible)}
        </Panel>
      )}

      <OperatorOnly>
        <Panel
          title="Dead-letter topic"
          id="dlt"
          meta={<StatusBadge status={p.dlt.status} label={p.dlt.summary} />}
          foot={<span>A record lands here after 3 failed processing attempts.</span>}
          stack
        >
          <KeyValueList
            narrow
            rows={[
              { label: "Retained", field: "dltRetained", value: t && t.dltRetained >= 0 ? val(p.topic, (x) => fmtInt(x.dltRetained)) : "—" },
              { label: "Published to the DLT", field: "dltTotalMessages", value: t && t.dltTotalMessages >= 0 ? val(p.topic, (x) => fmtInt(x.dltTotalMessages)) : "—" },
            ]}
          />
          <div>
            <NeedsEndpoint what="Records" endpoint={MISSING.dlt} />
            <NeedsEndpoint what="Replay, discard and mark reviewed" endpoint={MISSING.dltActions} />
          </div>
        </Panel>
      </OperatorOnly>
    </div>
  );
}
