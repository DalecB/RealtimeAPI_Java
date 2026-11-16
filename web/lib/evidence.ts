// Release evidence: past k6 load tests, shown on Overview as "not live".
// Every number is read from artifacts/k6/final/*.json at the repo root (p99 from http_req_duration
// or the scenario-tagged duration, counts from http_reqs / http_req_failed / processed_* totals).
// ponytail: copied by hand per release. Ceiling: a new run does not show up until this file is edited;
// the upgrade is generating it from artifacts/k6/final at build time.

export interface EvidenceRun {
  label: string;
  value: number;
}

export interface Evidence {
  name: string;
  objective: string;
  source: string;
  measured: number;
  limit: number;
  unit: "ms" | "count";
  detail: string;
  scale: string; // left side of the SloBudget scale
  history?: { runs: EvidenceRun[]; caption: string; label: string };
}

export const RELEASE = { commit: "27b5b47", date: "2026-11-16", redis: "Redis 256 MB" } as const;

const writeRuns: EvidenceRun[] = [
  { label: "T1 300 users", value: 4.15 },
  { label: "T1 500 users", value: 27.09 },
  { label: "T1 1000 users", value: 20.92 },
  { label: "T1 Kafka E2E phase 2", value: 3.99 },
  { label: "T3", value: 1.47 },
  { label: "T8 30 s", value: 1.36 },
  { label: "T8 5 min", value: 1.67 },
  { label: "T1 Kafka E2E 27b5b47", value: 11.82 },
];

const readRuns: EvidenceRun[] = [
  { label: "T3", value: 1.65 },
  { label: "T8 30 s", value: 1.63 },
  { label: "T8 5 min", value: 1.99 },
];

export const EVIDENCE: Evidence[] = [
  {
    name: "Write latency",
    objective: "POST /events p99 < 50 ms",
    source: "T1 Kafka E2E · 27b5b47 · 991.11 RPS · 5 min",
    measured: 11.82,
    limit: 50,
    unit: "ms",
    detail: "0 failed / 301,007",
    scale: "of budget used",
    history: { runs: writeRuns, caption: "8 runs to 27b5b47", label: "Write p99 by run, in ms" },
  },
  {
    name: "Read latency",
    objective: "GET tops p99 < 20 ms",
    source: "T3 mixed read and write · 5 min",
    measured: 1.65,
    limit: 20,
    unit: "ms",
    detail: "19 failed / 601,014",
    scale: "of budget used",
    history: { runs: readRuns, caption: "3 runs", label: "Read p99 by run, in ms" },
  },
  {
    name: "Idempotency",
    objective: "duplicate-key score changes = 0",
    source: "T4 · 50 concurrent requests, one key",
    measured: 0,
    limit: 0,
    unit: "count",
    detail: "1 applied · 49 replayed",
    scale: "contaminated · 0 errors",
  },
];
