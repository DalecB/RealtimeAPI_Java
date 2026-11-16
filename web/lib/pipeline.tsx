"use client";

import { createContext, useContext, useState, type ReactNode } from "react";
import {
  BREAKERS,
  getAuditTopicStatus,
  getBreaker,
  getSnapshotStatus,
  getStoredCount,
  getStreamsStatus,
  type AuditTopicStatus,
  type BreakerStatus,
  type SnapshotStatus,
  type StreamsStatus,
} from "./api";
import { fmtCompact, fmtInt } from "./format";
import { usePoll, type Poll } from "./hooks";

export type Status = "ok" | "warn" | "fail" | "idle";

export type HopKey = "write-path" | "stream" | "relay" | "kafka" | "consumer" | "postgres";

// One hop of the pipeline as both the strip node and the /delivery PipelineHop read it.
// `summary` is the strip value and the hop's StatusBadge label, word for word.
export interface Hop {
  key: HopKey;
  step: number;
  name: string; // strip node name
  title: string; // PipelineHop title
  resource: string;
  status: Status;
  summary: string;
  compact?: string; // shorter summary for a narrow strip
  stale?: { value: string; asOf: number }; // last known value from a source that stopped answering
}

export interface Pipeline {
  breaker: Poll<BreakerStatus>;
  streams: Poll<StreamsStatus>;
  topic: Poll<AuditTopicStatus>;
  stored: Poll<number>;
  snapshot: Poll<SnapshotStatus>;
  // The breaker endpoint is in-memory in the app, so when it fails the console cannot reach the API
  // at all. That is a console-side failure, not a pipeline incident.
  apiReachable: boolean;
  lastPollAt?: number;
  hops: Hop[];
  dlt: { status: Status; summary: string };
  incident: boolean;
  incidentSince: number | null;
  announce: boolean; // true only on the render where the incident first appears
  redisOutage: boolean; // breaker open and the stream unreachable: one cause, shared effects
  unackedSeries: number[]; // pendingEntries over the last 8 successful polls
}

const kafkaDown = (t?: AuditTopicStatus) => t !== undefined && t.totalMessages < 0;

function deriveHops(p: Omit<Pipeline, "hops" | "dlt" | "incident" | "incidentSince" | "announce" | "redisOutage" | "unackedSeries">): Hop[] {
  const { breaker, streams, topic, stored, apiReachable } = p;
  const unreachable = (poll: Poll<unknown>) => apiReachable && poll.error !== undefined;

  const write: Hop = { key: "write-path", step: 0, name: "Breaker", title: "Write path", resource: "circuit breaker", status: "idle", summary: "no data" };
  if (breaker.data) {
    const s = breaker.data.state;
    if (s === "CLOSED") Object.assign(write, { status: "ok", summary: "CLOSED" });
    else if (s === "OPEN") Object.assign(write, { status: "fail", summary: "OPEN · 503" });
    else Object.assign(write, { status: "warn", summary: s });
  }

  const stream: Hop = { key: "stream", step: 1, name: "Stream", title: "Redis Stream", resource: "audit outbox", status: "idle", summary: "no data" };
  if (unreachable(streams)) {
    Object.assign(stream, { status: "fail", summary: "unreachable" });
    if (streams.data && streams.okAt) stream.stale = { value: fmtInt(streams.data.pendingEntries), asOf: streams.okAt };
  } else if (streams.data) {
    const { consumerGroupLag, pendingEntries } = streams.data;
    Object.assign(stream, { status: "ok", summary: `${fmtInt(consumerGroupLag)} unread · ${fmtInt(pendingEntries)} unacked` });
  }

  const relay: Hop = { key: "relay", step: 2, name: "Relay", title: "Relay", resource: "one active instance", status: "idle", summary: "not reported" };

  const kafka: Hop = { key: "kafka", step: 3, name: "Kafka", title: "Kafka", resource: "lb-audit-events", status: "idle", summary: "no data" };
  const consumer: Hop = { key: "consumer", step: 4, name: "Consumer", title: "Consumer", resource: "group audit-trend", status: "idle", summary: "no data" };
  if (unreachable(topic) || kafkaDown(topic.data)) {
    Object.assign(kafka, { status: "fail", summary: "unreachable" });
  } else if (topic.data) {
    Object.assign(kafka, { status: "ok", summary: `lag ${fmtInt(topic.data.consumerLag)}` });
  }
  if (topic.data && !unreachable(topic)) {
    const t = topic.data;
    if (t.consumerState === "RUNNING") Object.assign(consumer, { status: "ok", summary: "RUNNING" });
    else if (t.consumerState === "DB_RETRYING") Object.assign(consumer, { status: "warn", summary: `DB_RETRYING · attempt ${t.retryAttempts}` });
    else Object.assign(consumer, { status: "fail", summary: t.consumerState });
  }

  const postgres: Hop = { key: "postgres", step: 5, name: "PostgreSQL", title: "PostgreSQL", resource: "audit_events", status: "idle", summary: "no data" };
  if (unreachable(stored)) {
    Object.assign(postgres, { status: "fail", summary: "unavailable" });
  } else if (stored.data !== undefined) {
    Object.assign(postgres, { status: "ok", summary: `${fmtInt(stored.data)} stored`, compact: `${fmtCompact(stored.data)} stored` });
  }

  const hops = [write, stream, relay, kafka, consumer, postgres];
  // Console-side failure: every node keeps its last reading, marked stale, and claims no status.
  if (!apiReachable && breaker.okAt) {
    for (const h of hops) {
      if (h.status === "idle") continue;
      h.stale = { value: h.summary, asOf: breaker.okAt };
      h.status = "idle";
      h.summary = "no answer";
      h.compact = undefined;
    }
  }
  return hops;
}

function deriveDlt(topic: Poll<AuditTopicStatus>, apiReachable: boolean): Pipeline["dlt"] {
  const t = topic.data;
  if (!apiReachable || !t || topic.error !== undefined || t.dltRetained < 0) return { status: "idle", summary: "no data" };
  // ponytail: there is no review state in the backend, so every retained record reads as retained, not
  // "to review". Upgrade: GET /internal/kafka/audit-topic/dlt with a review flag per record.
  return t.dltRetained > 0 ? { status: "warn", summary: `${fmtInt(t.dltRetained)} retained` } : { status: "ok", summary: "0 retained" };
}

const PipelineContext = createContext<Pipeline | null>(null);

export function PipelineProvider({ children }: { children: ReactNode }) {
  const breaker = usePoll(() => getBreaker(BREAKERS.write.kind));
  const streams = usePoll(getStreamsStatus);
  const topic = usePoll(getAuditTopicStatus);
  const stored = usePoll(getStoredCount);
  const snapshot = usePoll(getSnapshotStatus);

  const apiReachable = breaker.error === undefined;
  const base = { breaker, streams, topic, stored, snapshot, apiReachable, lastPollAt: breaker.okAt };
  const hops = deriveHops(base);
  const incident = apiReachable && breaker.data !== undefined && hops.some((h) => h.status === "fail");

  // Remember when this console first saw the incident; the render on that poll announces it.
  const [incidentSince, setIncidentSince] = useState<number | null>(null);
  if (incident && incidentSince === null) setIncidentSince(breaker.okAt ?? null);
  if (!incident && incidentSince !== null) setIncidentSince(null);

  const [series, setSeries] = useState<{ at: number; v: number }[]>([]);
  if (streams.okAt && streams.data && streams.okAt !== series.at(-1)?.at) {
    const point = { at: streams.okAt, v: streams.data.pendingEntries };
    setSeries((s) => [...s.slice(-7), point]);
  }

  const value: Pipeline = {
    ...base,
    hops,
    dlt: deriveDlt(topic, apiReachable),
    incident,
    incidentSince,
    announce: incident && breaker.okAt === incidentSince,
    redisOutage: breaker.data?.state === "OPEN" && hops[1].status === "fail",
    unackedSeries: series.map((p) => p.v),
  };
  return <PipelineContext.Provider value={value}>{children}</PipelineContext.Provider>;
}

export function usePipeline(): Pipeline {
  const p = useContext(PipelineContext);
  if (!p) throw new Error("usePipeline outside PipelineProvider");
  return p;
}
