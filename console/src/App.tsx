import { useState } from 'react'
import {
  bootstrapSession,
  ensureUsers,
  fireEvent,
  getAuditEventCount,
  getRecentAuditEvents,
  getAuditTopicStatus,
  getBreakerStatus,
  getDepsHealth,
  getSnapshotEntries,
  getSnapshotStatus,
  getStreamsStatus,
  getTops,
  type FireOutcome,
  type FireResult,
  type Session,
} from './api'
import { usePoll, useRingBuffer } from './hooks'
import TrendPage from './TrendPage'
import './App.css'

// 모드별로 의미 있는 입력만 노출한다 — 관계없는 필드가 보이면 설계자도 헷갈린다
const MODE_DEFS = [
  { key: 'single', label: 'Single', uses: { n: false, users: true } },
  { key: 'concurrent-same-key', label: 'N× Same Key', uses: { n: true, users: false } },
  { key: 'concurrent-random-key', label: 'N× Random Keys', uses: { n: true, users: true } },
  { key: 'mismatch', label: 'Same Key · Diff Payload', uses: { n: false, users: false } },
] as const

type FireMode = (typeof MODE_DEFS)[number]['key']

const OUTCOME_META: Record<FireOutcome, { label: string; cls: string }> = {
  NEW: { label: 'New', cls: 'new' },
  REPLAY: { label: 'Replay', cls: 'replay' },
  CONFLICT_409: { label: '409 Conflict', cls: 'conflict_409' },
  RATE_LIMITED_429: { label: '429 Limited', cls: 'rate_429' },
  BLOCKED_503: { label: '503 Blocked', cls: 'blocked_503' },
  ERROR: { label: 'Error', cls: 'error' },
}

const ZERO_TOTALS: Record<FireOutcome, number> = {
  NEW: 0,
  REPLAY: 0,
  CONFLICT_409: 0,
  RATE_LIMITED_429: 0,
  BLOCKED_503: 0,
  ERROR: 0,
}

// 각 시리즈를 자기 범위(min~max)로 정규화해 그 시리즈 고유의 변화를 최대로 보여준다.
function toPoints(values: number[], W: number, H: number): string {
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || 1
  return values
    .map((v, i) => `${((i / (values.length - 1)) * W).toFixed(1)},${(H - 4 - ((v - min) / span) * (H - 8)).toFixed(1)}`)
    .join(' ')
}

function Sparkline({ values, stroke, fill }: { values: number[]; stroke: string; fill: string }) {
  const W = 300
  const H = 60
  if (values.length < 2) return <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="spark-svg" />
  const pts = toPoints(values, W, H)
  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="spark-svg">
      <polygon points={`${pts} ${W},${H} 0,${H}`} style={{ fill }} />
      <polyline
        points={pts}
        style={{ fill: 'none', stroke, strokeWidth: 1.25, strokeLinecap: 'round', strokeLinejoin: 'round' }}
      />
    </svg>
  )
}

export default function App() {
  const [view, setView] = useState<'dashboard' | 'trends'>('dashboard')
  const [session, setSession] = useState<Session | null>(null)
  const [busy, setBusy] = useState(false)
  const [mode, setMode] = useState<FireMode>('single')
  const [count, setCount] = useState(50)
  const [delta, setDelta] = useState(25)
  const [userCount, setUserCount] = useState(10)
  // results는 로그 표시용 창(최근 ~500건), totals는 진짜 세션 누적 — 섞으면 카운터가 창 크기에 갇힌다
  const [results, setResults] = useState<FireResult[]>([])
  const [totals, setTotals] = useState<Record<FireOutcome, number>>(ZERO_TOTALS)
  const [tab, setTab] = useState<'ranking' | 'idem' | 'audit'>('ranking')

  const breaker = usePoll(getBreakerStatus)
  const streams = usePoll(getStreamsStatus)
  const stored = usePoll(getAuditEventCount, 1000)
  const auditTopic = usePoll(getAuditTopicStatus)
  const deps = usePoll(getDepsHealth, 2000)
  const snapStatus = usePoll(getSnapshotStatus, 1000)
  // 브레이커 타임라인 창 = 80 샘플 × 0.5s = 40s. 10초 간격으로 축 눈금이 딱 떨어진다.
  const breakerStates = useRingBuffer(breaker?.state ?? null, 80)
  const streamLengths = useRingBuffer(streams?.streamLength ?? null, 240)
  const producedSeries = useRingBuffer(auditTopic?.totalMessages ?? null, 240)
  // Kafka는 누적 총계라 그대로 그리면 납작하다. 폴 사이 증가분(옮긴 건수)을 그려 relay가 옮기는 순간을 보인다.
  const kafkaThroughput = producedSeries.map((v, i) => (i === 0 ? 0 : Math.max(0, v - producedSeries[i - 1])))
  const kafkaPeak = kafkaThroughput.length ? Math.max(...kafkaThroughput) : 0
  const kafkaNow = kafkaThroughput.at(-1) ?? 0
  const tops = usePoll(() => (session ? getTops(session.leaderboardId, 50) : Promise.resolve(null)), 1000)
  const snapshot = usePoll(
    () => (session ? getSnapshotEntries(session.leaderboardId).catch(() => null) : Promise.resolve(null)),
    2000,
  )
  const auditEvents = usePoll(
    () => (session ? getRecentAuditEvents(session.leaderboardId) : Promise.resolve(null)),
    1000,
  )

  const latencies = results.slice(-120).map((r) => r.latencyMs)
  const lastBlocked = [...results].reverse().find((r) => r.outcome === 'BLOCKED_503')

  const preview =
    mode === 'single'
      ? `→ fires 1 request · fresh key · 1 user drawn from pool of ${userCount} · delta +${delta} · expect: 1 New`
      : mode === 'concurrent-same-key'
        ? `→ fires ${count} concurrent · ONE shared key · ONE user (same payload required — a different user would change the hash and cause 409) · expect: 1 New + ${count - 1} Replay`
        : mode === 'concurrent-random-key'
          ? `→ fires ${count} concurrent · unique keys · users drawn from pool of ${userCount} · delta +${delta} each · expect: ${count} New`
          : `→ fires 2 sequential · same key · delta +${delta} then +${delta + 1} · expect: 1 New + 1 409 Conflict`

  const state = breaker?.state ?? 'UNKNOWN'
  const kafkaUp = auditTopic ? auditTopic.totalMessages >= 0 : null
  const stateCls = state.toLowerCase()
  const isOpen = state === 'OPEN'
  const isHalf = state === 'HALF_OPEN'
  const stateLabel = isOpen ? 'Open' : isHalf ? 'Half-Open' : state === 'CLOSED' ? 'Closed' : '—'

  async function fire() {
    if (!session || busy) return
    setBusy(true)
    try {
      const s = await ensureUsers(session, Math.max(1, userCount))
      if (s !== session) setSession(s)
      const pick = () => s.userIds[Math.floor(Math.random() * Math.min(s.userIds.length, userCount))]

      let fired: FireResult[]
      if (mode === 'single') {
        fired = [await fireEvent(s, { idempotencyKey: crypto.randomUUID(), deltaScore: delta, userId: pick() })]
      } else if (mode === 'concurrent-same-key') {
        // 동일 payload가 전제이므로 유저도 1명 고정 (유저가 다르면 해시가 달라져 409)
        const key = crypto.randomUUID()
        const userId = pick()
        fired = await Promise.all(
          Array.from({ length: count }, () => fireEvent(s, { idempotencyKey: key, deltaScore: delta, userId })),
        )
      } else if (mode === 'concurrent-random-key') {
        fired = await Promise.all(
          Array.from({ length: count }, () =>
            fireEvent(s, { idempotencyKey: crypto.randomUUID(), deltaScore: delta, userId: pick() }),
          ),
        )
      } else {
        const key = crypto.randomUUID()
        const userId = pick()
        const first = await fireEvent(s, { idempotencyKey: key, deltaScore: delta, userId })
        const second = await fireEvent(s, { idempotencyKey: key, deltaScore: delta + 1, userId })
        fired = [first, second]
      }
      setResults((prev) => [...prev.slice(-400), ...fired])
      setTotals((prev) => {
        const next = { ...prev }
        fired.forEach((r) => next[r.outcome]++)
        return next
      })
    } finally {
      setBusy(false)
    }
  }

  // 동점 내 표시 순서는 저장소별로 다르다 (ZREVRANGE lex 역순 vs SQL user_id 순 — PRD 5.2 미규정)
  // 나란히 비교하는 뷰이므로 화면에서만 같은 기준으로 정렬한다
  const byRankThenUser = (a: { rank: number; userId: string }, b: { rank: number; userId: string }) =>
    a.rank - b.rank || Number(a.userId) - Number(b.userId)
  const hotItems = [...(tops?.items ?? [])].sort(byRankThenUser)
  const coldItems = [...(snapshot?.items ?? [])].sort(byRankThenUser)
  const coldByUser = new Map(coldItems.map((it) => [it.userId, it.score]))
  const driftByUser = new Map(hotItems.map((it) => [it.userId, it.score - (coldByUser.get(it.userId) ?? 0)] as const))
  const driftingCount = [...driftByUser.values()].filter((d) => d !== 0).length

  const uses = MODE_DEFS.find((m) => m.key === mode)!.uses
  const fieldCols = `1.4fr repeat(${1 + (uses.n ? 1 : 0) + (uses.users ? 1 : 0)}, 0.7fr)`

  if (view === 'trends') {
    return <TrendPage onBack={() => setView('dashboard')} />
  }

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <div className="logo">R</div>
          <div className="name">
            RealtimeAPI <span>Ops Console</span>
          </div>
          <div className="env">local-demo</div>
        </div>
        <div className="spacer" />
        <div className="conn">
          <span className={`dot ${breaker ? 'ok' : 'down'}`} />
          <span>{breaker ? 'API connected' : 'API unreachable'}</span>
        </div>
        <div className="deps-chip">
          <span>Redis</span>
          <span className={deps?.redis ? 'up' : 'downed'}>{deps ? (deps.redis ? 'OK' : 'DOWN') : '—'}</span>
          <span className="sep">·</span>
          <span>Postgres</span>
          <span className={deps?.db ? 'up' : 'downed'}>{deps ? (deps.db ? 'OK' : 'DOWN') : '—'}</span>
          <span className="sep">·</span>
          <span>Kafka</span>
          <span className={kafkaUp === null ? '' : kafkaUp ? 'up' : 'downed'}>
            {kafkaUp === null ? '—' : kafkaUp ? 'OK' : 'DOWN'}
          </span>
        </div>
        <div className={`breaker-pill ${stateCls}`}>
          <span className="dot" />
          Breaker {stateLabel}
        </div>
        <button className="trend-nav" onClick={() => setView('trends')}>
          Score Trends →
        </button>
      </header>

      {isOpen && <div className="hazard-strip" />}
      {isOpen && (
        <div className="banner open">
          <strong>Circuit Open</strong>
          <span>
            Write path failing fast with 503
            {lastBlocked?.retryAfter ? ` · Retry-After ${lastBlocked.retryAfter}s` : ''} · audit replay reconciles on
            recovery
          </span>
        </div>
      )}
      {isHalf && (
        <div className="banner half_open">
          <strong>Half-Open · Probing</strong>
          <span>Trial requests admitted · closes on consecutive successes, reopens on first failure</span>
        </div>
      )}

      <main className="page">
        <section className="section">
          <div className="section-title">
            Live Metrics <span className={`dot ${breaker ? 'ok' : 'down'} pulse`} />
            <span className="meta">0.5s poll</span>
          </div>
          <div className="metrics-grid">
            <div className="metric-card">
              <div className="metric-head">
                <span className="title">Breaker State</span>
                <span className={`value ${stateCls}`}>{stateLabel}</span>
              </div>
              <div className="breaker-timeline">
                {breakerStates.map((s, i) => (
                  <span key={i} className={`tick tick-${s.toLowerCase()}`} />
                ))}
              </div>
              <div className="breaker-axis">
                <span>40s</span>
                <span>30s</span>
                <span>20s</span>
                <span>10s</span>
                <span className="now">
                  <i className="live-dot" />now
                </span>
              </div>
              <div className="legend">
                <span>
                  <i style={{ background: 'var(--color-green)' }} />Closed
                </span>
                <span>
                  <i style={{ background: 'var(--color-red)' }} />Open
                </span>
                <span>
                  <i style={{ background: 'var(--color-amber)' }} />Half-Open
                </span>
              </div>
            </div>
            <div className="metric-card">
              <div className="metric-head">
                <span className="title">Audit Stream (outbox)</span>
                <span className="value plain">len {streams?.streamLength ?? '—'} · pending {streams?.pendingEntries ?? '—'}</span>
              </div>
              <Sparkline values={streamLengths} stroke="var(--color-blue)" fill="rgba(10,132,255,.12)" />
              <div className="metric-note">
                len = 쌓인 감사 기록(relay가 빼감) · pending = 아직 못 옮긴 건수(<span className="mono">relay 밀림</span>)
              </div>
            </div>
            <div className="metric-card">
              <div className="metric-head">
                <span className="title">Kafka Delivery</span>
                <span className="value plain">peak {kafkaPeak} · now {kafkaNow}</span>
              </div>
              <Sparkline values={kafkaThroughput} stroke="var(--color-green)" fill="rgba(48,209,88,.12)" />
              <div className="metric-flow">
                <span>Kafka produced</span>
                <b>{auditTopic?.totalMessages ?? '—'}</b>
                <span className="arrow">→</span>
                <span>PG stored</span>
                <b className="stored">{stored?.count ?? '—'}</b>
              </div>
            </div>
            <div className="metric-card">
              <div className="metric-head">
                <span className="title">Request Latency</span>
                <span className={`value ${isOpen ? 'open' : isHalf ? 'half_open' : 'closed'}`}>
                  {latencies.length ? `last ${latencies[latencies.length - 1].toFixed(0)}ms` : '—'}
                </span>
              </div>
              <Sparkline
                values={latencies}
                stroke={isOpen ? 'var(--color-red)' : isHalf ? 'var(--color-amber)' : 'var(--color-green)'}
                fill={isOpen ? 'rgba(255,69,58,.12)' : isHalf ? 'rgba(255,214,10,.10)' : 'rgba(48,209,88,.12)'}
              />
              <div className="metric-note">
                {isOpen
                  ? 'Fail-fast: breaker rejects before Redis I/O — no thread pile-up'
                  : 'Hot path: Lua (idem check + ZINCRBY + XADD), single round trip'}
              </div>
            </div>
          </div>
        </section>

        <section className="section">
          <div className="section-title">Scenario Playground</div>
          <div className="playground-grid">
            <div className="stack">
              <div className="card">
                <div className="card-title-row">
                  <span className="title">Event Firer</span>
                  <span className="meta">POST /events</span>
                </div>
                {!session ? (
                  <button
                    className="bootstrap-btn"
                    disabled={busy}
                    onClick={async () => {
                      setBusy(true)
                      try {
                        setSession(await bootstrapSession())
                      } finally {
                        setBusy(false)
                      }
                    }}
                  >
                    Bootstrap demo session (user → project → leaderboard)
                  </button>
                ) : (
                  <>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-2)' }}>
                      <span className="field-label">Fire mode</span>
                      <div className="segmented">
                        {MODE_DEFS.map((m) => (
                          <button
                            key={m.key}
                            className={mode === m.key ? 'active' : ''}
                            onClick={() => setMode(m.key)}
                          >
                            {m.label}
                          </button>
                        ))}
                      </div>
                    </div>
                    <div className="firer-fields" style={{ gridTemplateColumns: fieldCols }}>
                      <label>
                        <span>Leaderboard</span>
                        <input value={`console-board (${session.leaderboardId.slice(0, 8)})`} disabled />
                      </label>
                      <label>
                        <span>Score delta (+)</span>
                        <input type="number" min={1} value={delta} onChange={(e) => setDelta(Number(e.target.value))} />
                      </label>
                      {uses.n && (
                        <label>
                          <span>Requests (N)</span>
                          <input
                            type="number"
                            min={2}
                            max={200}
                            value={count}
                            onChange={(e) => setCount(Number(e.target.value))}
                          />
                        </label>
                      )}
                      {uses.users && (
                        <label>
                          <span>User pool size</span>
                          <input
                            type="number"
                            min={1}
                            max={1000}
                            value={userCount}
                            onChange={(e) => setUserCount(Number(e.target.value))}
                          />
                        </label>
                      )}
                    </div>
                    <div className="fire-footer">
                      <div className="mode-hint">{preview}</div>
                      <button className="fire-btn" onClick={fire} disabled={busy}>
                        {busy ? 'Firing…' : 'Fire'}
                      </button>
                    </div>
                  </>
                )}
              </div>

              <div className={`card ${isOpen ? 'danger-edge' : ''}`}>
                <div className="card-title-row">
                  <span className="title">Fault Injection</span>
                  <span className={`chip ${isOpen ? 'alert' : ''}`}>{isOpen ? 'breaker: open' : 'override: none'}</span>
                </div>
                <div className="btn-row">
                  {/* force-open/clear internal API는 백로그 (12_Ops_Console_스펙) */}
                  <button className="btn-danger" disabled title="internal API 설계 대기">
                    Force Open Breaker
                  </button>
                  <button className="btn-ghost" disabled title="internal API 설계 대기">
                    Clear Override
                  </button>
                  <div className="inline-metric">
                    <span>Retry-After</span>
                    <span className={`big ${isOpen ? 'alert' : 'idle'}`}>
                      {isOpen && lastBlocked?.retryAfter ? `${lastBlocked.retryAfter}s` : '—'}
                    </span>
                    <span className="note">{isOpen ? 'sent on every 503' : 'demo: docker compose stop redis'}</span>
                  </div>
                </div>
              </div>

              <div className="card">
                <div className="card-title-row">
                  <span className="title">Snapshot</span>
                  <span className="meta">cold path</span>
                </div>
                <div className="btn-row">
                  <button className="btn-primary" disabled title="internal API 설계 대기">
                    Capture Now
                  </button>
                  <button className="btn-ghost" disabled title="internal API 설계 대기">
                    Recover
                  </button>
                  <div className="inline-metric">
                    <span className={`dot ${snapStatus?.lastSuccessfulSnapshotAt ? 'ok' : 'down'}`} />
                    <span>Last success</span>
                    <span className="mono">
                      {snapStatus?.lastSuccessfulSnapshotAt
                        ? new Date(snapStatus.lastSuccessfulSnapshotAt).toISOString().slice(11, 19) + 'Z'
                        : '—'}
                    </span>
                    <span className="note">{snapStatus ? `· lag ${snapStatus.snapshotLagSeconds}s` : ''}</span>
                  </div>
                </div>
              </div>
            </div>

            <div className="card">
              <div className="card-title-row">
                <span className="title">Response Distribution</span>
                <span className="meta">session total</span>
              </div>
              <div className="counters">
                <div className="counter new">
                  <div className="label">
                    <i />New
                  </div>
                  <div className="num">{totals.NEW}</div>
                  <div className="sub">200 created</div>
                </div>
                <div className="counter replay">
                  <div className="label">
                    <i />Replay
                  </div>
                  <div className="num">{totals.REPLAY}</div>
                  <div className="sub">200 cached</div>
                </div>
                <div className="counter conflict">
                  <div className="label">
                    <i />409 Conflict
                  </div>
                  <div className="num">{totals.CONFLICT_409}</div>
                  <div className="sub">key reuse</div>
                </div>
                <div className="counter ratelimited">
                  <div className="label">
                    <i />429 Limited
                  </div>
                  <div className="num">{totals.RATE_LIMITED_429}</div>
                  <div className="sub">100 req/s cap</div>
                </div>
                <div className={`counter blocked ${isOpen ? 'alert' : ''}`}>
                  <div className="label">
                    <i />503 Blocked
                  </div>
                  <div className="num">{totals.BLOCKED_503}</div>
                  <div className="sub">breaker fail-fast</div>
                </div>
              </div>
              <div className="log-section">
                <div className="log-title">Recent fire results</div>
                <div className="log">
                  {results
                    .slice(-10)
                    .reverse()
                    .map((r, i) => {
                      const meta = OUTCOME_META[r.outcome]
                      return (
                        <div key={`${r.at}-${i}`} className={`log-row ${meta.cls}`}>
                          <span className="t">{new Date(r.at).toISOString().slice(11, 23)}</span>
                          <span className="k">{r.idempotencyKey}</span>
                          <span className="status-chip">
                            <i />
                            {meta.label}
                          </span>
                          <span className="lat">{r.latencyMs.toFixed(0)}ms</span>
                        </div>
                      )
                    })}
                </div>
              </div>
            </div>
          </div>
        </section>

        <section className="card">
          <div className="inspector-head">
            <span className="title">Data Inspector</span>
            <div className="segmented">
              <button className={tab === 'ranking' ? 'active' : ''} onClick={() => setTab('ranking')}>
                Ranking · Hot vs Cold
              </button>
              <button className={tab === 'idem' ? 'active' : ''} onClick={() => setTab('idem')}>
                Idempotency Keys
              </button>
              <button className={tab === 'audit' ? 'active' : ''} onClick={() => setTab('audit')}>
                Audit Events
              </button>
            </div>
          </div>
          {tab === 'ranking' ? (
            <>
              <div className="hot-cold">
                <div className="table-card">
                  <div className="table-card-head">
                    <span className="dot" style={{ background: 'var(--color-red)' }} />
                    <strong>Redis ZSET</strong>
                    <span className="meta">hot path · live</span>
                  </div>
                  <div className="rank-head cols-3">
                    <span>#</span>
                    <span>User</span>
                    <span style={{ textAlign: 'right' }}>Score</span>
                  </div>
                  {hotItems.map((it) => (
                    <div key={it.userId} className={`rank-row cols-3 ${driftByUser.get(it.userId) ? 'drifting' : ''}`}>
                      <span className="rank">{it.rank}</span>
                      <span>{it.userId}</span>
                      <span className="right">{it.score.toLocaleString()}</span>
                    </div>
                  ))}
                  {!hotItems.length && <div className="hint" style={{ padding: 14 }}>no data — bootstrap & fire</div>}
                </div>
                <div className="table-card">
                  <div className="table-card-head">
                    <span className="dot" style={{ background: 'var(--color-blue)' }} />
                    <strong>PostgreSQL Snapshot</strong>
                    <span className="meta">
                      {snapshot ? `seq ${snapshot.snapshotId} · ${snapshot.snapshotAt?.slice(11, 19)}Z` : 'cold path'}
                    </span>
                  </div>
                  <div className="rank-head cols-4">
                    <span>#</span>
                    <span>User</span>
                    <span style={{ textAlign: 'right' }}>Score</span>
                    <span style={{ textAlign: 'right' }}>Drift</span>
                  </div>
                  {coldItems.map((it) => {
                    const drift = (driftByUser.get(it.userId) ?? 0) as number
                    return (
                      <div key={it.userId} className={`rank-row cols-4 ${drift ? 'drifting' : ''}`}>
                        <span className="rank">{it.rank}</span>
                        <span>{it.userId}</span>
                        <span className="right">{it.score.toLocaleString()}</span>
                        <span className="drift-val">{drift ? `+${drift}` : '·'}</span>
                      </div>
                    )
                  })}
                  {!coldItems.length && <div className="hint" style={{ padding: 14 }}>no snapshot yet — 30s cycle</div>}
                </div>
              </div>
              <div className={`drift-note ${driftingCount ? 'active' : ''}`}>
                {driftingCount
                  ? `${driftingCount} rows drifting — hot path is ahead of last snapshot · reconciles at next capture`
                  : 'No drift — snapshot is in sync with hot path'}
              </div>
            </>
          ) : tab === 'audit' ? (
            <div className="table-card audit-table">
              <div className="table-card-head">
                <span className="dot" style={{ background: 'var(--color-green)' }} />
                <strong>PostgreSQL audit_events</strong>
                <span className="meta">latest 20 · 1s poll</span>
              </div>
              <div className="audit-head audit-row">
                <span>Time</span>
                <span>Type</span>
                <span>User</span>
                <span>Delta</span>
                <span>API Key</span>
                <span>Event ID</span>
                <span>Idempotency Key</span>
              </div>
              {(auditEvents ?? []).map((event) => (
                <div key={event.eventId} className="audit-row">
                  <span className="audit-time">{new Date(event.eventTime).toISOString().slice(11, 23)}</span>
                  <span className={`audit-type ${event.eventType}`}>{event.eventType}</span>
                  <span>{event.userId}</span>
                  <span className="audit-delta">{event.delta > 0 ? `+${event.delta}` : event.delta}</span>
                  <span>{event.apiKeyId}</span>
                  <span className="audit-id" title={event.eventId}>{event.eventId}</span>
                  <span className="audit-id" title={event.idempotencyKey}>{event.idempotencyKey}</span>
                </div>
              ))}
              {!session && <div className="audit-empty">bootstrap a demo session to inspect its events</div>}
              {session && auditEvents?.length === 0 && <div className="audit-empty">no stored events — fire Single or Same Key · Diff Payload</div>}
            </div>
          ) : (
            <p className="hint">read-only internal API 설계 대기 (12_Ops_Console_스펙 백로그)</p>
          )}
        </section>
      </main>
    </div>
  )
}
