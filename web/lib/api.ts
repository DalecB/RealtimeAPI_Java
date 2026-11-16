// API types and fetchers for the RealtimeAPI backend. Every call is same-origin; next.config rewrites
// forward these paths to API_ORIGIN. Response types follow the Spring DTOs (and console/src/api.ts).
import { clearSession, getToken, type Session } from "./session";

// ---------- Response types ----------

export type BreakerState = "CLOSED" | "OPEN" | "HALF_OPEN" | (string & {});

export interface BreakerStatus {
  state: BreakerState;
  failureRate: number; // percent 0..100; the backend reports 0 until the window has enough calls
}

export interface StreamsStatus {
  pendingEntries: number; // read by the relay, not yet XACKed
  streamLength: number; // XLEN, includes delivered entries
  consumerGroupLag: number; // not yet read by the relay
}

export type ConsumerState = "RUNNING" | "DB_RETRYING" | "STOPPED";

export interface AuditTopicStatus {
  // The Kafka offsets below are -1 when the backend could not read the topic.
  totalMessages: number;
  retained: number;
  consumerLag: number;
  dltTotalMessages: number;
  dltRetained: number;
  consumerState: ConsumerState;
  retryDurationSeconds: number;
  retryAttempts: number;
  consumerReady: boolean;
}

export interface SnapshotStatus {
  lastSuccessfulSnapshotAt: string | null;
  snapshotLagSeconds: number;
}

export interface TopRankItem {
  rank: number;
  userId: string;
  score: number;
}

export interface TopRanks {
  leaderboardId: string;
  items: TopRankItem[];
  total: number;
}

export interface UserRank {
  userId: string;
  score: number;
  rank: number | null; // null: not ranked (score 0)
}

export interface SnapshotEntries {
  leaderboardId: string;
  snapshotId: number;
  snapshotAt: string;
  topN: number;
  total: number;
  items: TopRankItem[];
}

export interface Project {
  id: string;
  adminId: number;
  name: string;
  createdAt: string;
}

export interface Leaderboard {
  id: string;
  projectId: string;
  name: string;
  createdAt: string;
}

export type FireOutcome = "NEW" | "REPLAY" | "CONFLICT_409" | "RATE_LIMITED_429" | "BLOCKED_503" | "ERROR";

export interface FireResult {
  outcome: FireOutcome;
  status: number;
  retryAfter: string | null;
}

// ---------- Backend configuration the UI states as fact ----------
// ponytail: copied from src/main/resources/application.properties because no endpoint exposes it.
// Ceiling: a config change in the backend silently disagrees with these labels until edited here.
export const SNAPSHOT = { intervalSeconds: 30, sloSeconds: 45, topN: 1000 } as const;

// ---------- Breakers ----------
// The write breaker wraps the Redis hot path of POST /events. A read breaker is planned in the backend;
// add it as one more entry here and in BreakerKind, and nothing in components changes.
export type BreakerKind = "write";

export interface BreakerConfig {
  kind: BreakerKind;
  path: string;
  windowCalls: number;
  openAtPercent: number;
  retryAfterSeconds: number;
  trialCalls: number;
}

// ponytail: window/threshold/wait/trial mirror events.circuit-breaker.* in application.properties.
export const BREAKERS: Record<BreakerKind, BreakerConfig> = {
  write: {
    kind: "write",
    path: "/internal/circuit-breaker/status",
    windowCalls: 10,
    openAtPercent: 50,
    retryAfterSeconds: 10,
    trialCalls: 3,
  },
};

// ---------- Endpoints the design needs that do not exist yet ----------
export const MISSING = {
  outcomes: "GET /internal/events/outcomes?last=50",
  relay: "GET /internal/relay/status",
  partitions: "GET /internal/kafka/audit-topic/partitions",
  dlt: "GET /internal/kafka/audit-topic/dlt?limit=20",
  dltActions: "POST /internal/kafka/audit-topic/dlt/{partition}/{offset}/replay | discard | review",
  errorRate: "GET /internal/events/error-rate?window=5m",
  incidents: "GET /internal/incidents?status=open",
  breakerOpenedAt: "openedAt on GET /internal/circuit-breaker/status",
} as const;

// ---------- Transport ----------

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | null,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}, admin = false): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set("Content-Type", "application/json");
  const token = getToken();
  if (admin && token) headers.set("Authorization", `Bearer ${token}`);
  let res: Response;
  try {
    res = await fetch(path, { ...init, headers, cache: "no-store" });
  } catch (e) {
    throw new ApiError(0, null, e instanceof Error ? e.message : "network error");
  }
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { code?: string; message?: string } | null;
    if (admin && res.status === 401) clearSession();
    throw new ApiError(res.status, body?.code ?? null, body?.message ?? `${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

const adminGet = <T>(path: string) => request<T>(path, {}, true);

// ---------- Auth ----------

export async function login(externalId: string, password: string): Promise<Session> {
  const res = await request<{ accessToken: string; expiresAt: string; externalId: string }>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ externalId, password }),
  });
  return { token: res.accessToken, role: "operator", externalId: res.externalId, expiresAt: res.expiresAt };
}

// ---------- Projects and leaderboards ----------

export const getProjects = () =>
  request<{ items: Project[] }>("/projects?offset=0&limit=50").then((r) => r.items);

export const getProject = (id: string) => request<Project>(`/projects/${id}`);

export const getLeaderboards = (projectId: string) =>
  request<{ items: Leaderboard[] }>(`/leaderboards?projectId=${projectId}`).then((r) => r.items);

export const getLeaderboard = (id: string) => request<Leaderboard>(`/leaderboards/${id}`);

export const createLeaderboard = (projectId: string, name: string) =>
  request<Leaderboard>("/leaderboards", { method: "POST", body: JSON.stringify({ projectId, name }) }, true);

// ---------- Rankings (public reads, live from Redis) ----------

export const getTops = (leaderboardId: string, offset: number, limit: number) =>
  request<TopRanks>(`/leaderboards/${leaderboardId}/tops?offset=${offset}&limit=${limit}`);

export const getUserRank = (leaderboardId: string, userId: string) =>
  request<UserRank>(`/leaderboards/${leaderboardId}/users/${encodeURIComponent(userId)}`);

// ---------- Internal status (admin JWT) ----------

export const getBreaker = (kind: BreakerKind) => adminGet<BreakerStatus>(BREAKERS[kind].path);

export const getStreamsStatus = () => adminGet<StreamsStatus>("/internal/streams/status");

export const getAuditTopicStatus = () => adminGet<AuditTopicStatus>("/internal/kafka/audit-topic/status");

export const getStoredCount = () => adminGet<{ count: number }>("/internal/audit-events/count").then((r) => r.count);

export const getSnapshotStatus = () => adminGet<SnapshotStatus>("/internal/snapshot/status");

// 404 SNAPSHOT_NOT_FOUND means the leaderboard has no snapshot yet (an empty leaderboard is never snapshotted).
export const getSnapshotEntries = (leaderboardId: string, limit: number) =>
  adminGet<SnapshotEntries>(`/internal/snapshots/${leaderboardId}/entries?offset=0&limit=${limit}`).catch((e) => {
    if (e instanceof ApiError && e.status === 404) return null;
    throw e;
  });

// ---------- Writes ----------

// POST /events authenticates with the project's API key, not the admin JWT.
export async function fireEvent(opts: {
  apiKey: string;
  leaderboardId: string;
  userId: string;
  deltaScore: number;
}): Promise<FireResult> {
  try {
    const res = await fetch("/events", {
      method: "POST",
      cache: "no-store",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${opts.apiKey}`,
        "Idempotency-Key": crypto.randomUUID(),
      },
      body: JSON.stringify({ leaderboardId: opts.leaderboardId, userId: opts.userId, deltaScore: opts.deltaScore }),
    });
    const retryAfter = res.headers.get("Retry-After");
    const base = { status: res.status, retryAfter };
    if (res.status === 409) return { ...base, outcome: "CONFLICT_409" };
    if (res.status === 429) return { ...base, outcome: "RATE_LIMITED_429" };
    if (res.status === 503) return { ...base, outcome: "BLOCKED_503" };
    if (!res.ok) return { ...base, outcome: "ERROR" };
    const body = (await res.json()) as { replayed: boolean };
    return { ...base, outcome: body.replayed ? "REPLAY" : "NEW" };
  } catch {
    return { outcome: "ERROR", status: 0, retryAfter: null };
  }
}
