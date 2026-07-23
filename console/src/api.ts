// RealtimeAPI 콘솔 API 클라이언트 — 기존 백엔드 표면만 사용 (신규 API는 백로그)

export interface Session {
  userIds: string[]
  jwt: string
  projectId: string
  apiKey: string
  leaderboardId: string
}

export type FireOutcome = 'NEW' | 'REPLAY' | 'CONFLICT_409' | 'RATE_LIMITED_429' | 'BLOCKED_503' | 'ERROR'

export interface FireResult {
  outcome: FireOutcome
  status: number
  latencyMs: number
  idempotencyKey: string
  at: number
  retryAfter?: string | null
}

export interface BreakerStatus {
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  failureRate: number
}

export interface StreamsStatus {
  pendingEntries: number
  streamLength: number
  lastDeliveredId: string | null
}

export interface TopRankItem {
  rank: number
  userId: string
  score: number
}

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`${res.status} ${await res.text()}`)
  return res.json() as Promise<T>
}

async function createUser(externalId: string): Promise<string> {
  const user = await json<{ id: number }>(
    await fetch('/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ externalId }),
    }),
  )
  return String(user.id)
}

// 유저 풀을 n명까지 지연 확장 (부족한 만큼만 생성)
export async function ensureUsers(session: Session, n: number): Promise<Session> {
  const missing = n - session.userIds.length
  if (missing <= 0) return session
  const created = await Promise.all(
    Array.from({ length: missing }, (_, i) => createUser(`console-u${session.userIds.length + i}-${Date.now()}`)),
  )
  return { ...session, userIds: [...session.userIds, ...created] }
}

// 데모 세션 부트스트랩: smoke 스크립트와 동일한 체인 (user → login → project → leaderboard)
export async function bootstrapSession(): Promise<Session> {
  const externalId = `console-${Date.now()}`
  const userId = await createUser(externalId)
  const login = await json<{ accessToken: string }>(
    await fetch('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ externalId }),
    }),
  )
  const auth = { Authorization: `Bearer ${login.accessToken}`, 'Content-Type': 'application/json' }
  const project = await json<{ id: string; defaultApiKey: { rawKey: string } }>(
    await fetch('/projects', { method: 'POST', headers: auth, body: JSON.stringify({ name: `console-${Date.now()}` }) }),
  )
  const leaderboard = await json<{ id: string }>(
    await fetch('/leaderboards', {
      method: 'POST',
      headers: auth,
      body: JSON.stringify({ projectId: project.id, name: 'console-board' }),
    }),
  )
  return {
    userIds: [userId],
    jwt: login.accessToken,
    projectId: project.id,
    apiKey: project.defaultApiKey.rawKey,
    leaderboardId: leaderboard.id,
  }
}

export async function fireEvent(
  session: Session,
  opts: { idempotencyKey: string; deltaScore: number; userId: string },
): Promise<FireResult> {
  const started = performance.now()
  try {
    const res = await fetch('/events', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${session.apiKey}`,
        'Idempotency-Key': opts.idempotencyKey,
      },
      body: JSON.stringify({
        leaderboardId: session.leaderboardId,
        userId: opts.userId,
        deltaScore: opts.deltaScore,
      }),
    })
    const latencyMs = performance.now() - started
    const base = { latencyMs, idempotencyKey: opts.idempotencyKey, at: Date.now(), status: res.status }
    if (res.status === 409) return { ...base, outcome: 'CONFLICT_409' }
    if (res.status === 429) return { ...base, outcome: 'RATE_LIMITED_429', retryAfter: res.headers.get('Retry-After') }
    if (res.status === 503) return { ...base, outcome: 'BLOCKED_503', retryAfter: res.headers.get('Retry-After') }
    if (!res.ok) return { ...base, outcome: 'ERROR' }
    const body = (await res.json()) as { replayed: boolean }
    return { ...base, outcome: body.replayed ? 'REPLAY' : 'NEW' }
  } catch {
    return {
      outcome: 'ERROR',
      status: 0,
      latencyMs: performance.now() - started,
      idempotencyKey: opts.idempotencyKey,
      at: Date.now(),
    }
  }
}

export const getBreakerStatus = () =>
  fetch('/internal/circuit-breaker/status').then((r) => json<BreakerStatus>(r))

export const getStreamsStatus = () =>
  fetch('/internal/streams/status').then((r) => json<StreamsStatus>(r))

export const getTops = (leaderboardId: string, limit = 10) =>
  fetch(`/leaderboards/${leaderboardId}/tops?limit=${limit}`).then((r) =>
    json<{ items: TopRankItem[]; total: number }>(r),
  )

export interface SnapshotEntries {
  snapshotId: number
  snapshotAt: string
  items: TopRankItem[]
}

export interface SnapshotStatus {
  lastSuccessfulSnapshotAt: string | null
  snapshotLagSeconds: number
}

export const getSnapshotEntries = (leaderboardId: string) =>
  fetch(`/internal/snapshots/${leaderboardId}/entries`).then((r) => json<SnapshotEntries>(r))

export const getSnapshotStatus = () =>
  fetch('/internal/snapshot/status').then((r) => json<SnapshotStatus>(r))

export interface DepsHealth {
  redis: boolean
  db: boolean
}

// actuator health (show-details=always) — 상단바 Redis/Postgres 칩은 장식이 아니라 실측
export const getDepsHealth = (): Promise<DepsHealth> =>
  fetch('/actuator/health')
    .then((r) => r.json())
    .then((h: { components?: Record<string, { status?: string }> }) => ({
      redis: h.components?.redis?.status === 'UP',
      db: h.components?.db?.status === 'UP',
    }))
