import { useMemo, useState } from 'react'
import {
  bootstrapSession,
  ensureUsers,
  fireEvent,
  getBreakerStatus,
  getSnapshotEntries,
  getSnapshotStatus,
  getStreamsStatus,
  getTops,
  type FireOutcome,
  type FireResult,
  type Session,
} from './api'
import { usePoll, useRingBuffer } from './hooks'
import './App.css'

// 모드별로 의미 있는 입력만 노출한다 — 관계없는 필드가 보이면 설계자도 헷갈린다 (2026-07-23 UX 피드백)
const MODE_DEFS = [
  { key: 'single', label: 'SINGLE', uses: { n: false, users: true } },
  { key: 'concurrent-same-key', label: 'N× SAME KEY', uses: { n: true, users: false } },
  { key: 'concurrent-random-key', label: 'N× RANDOM KEYS', uses: { n: true, users: true } },
  { key: 'mismatch', label: 'SAME KEY · DIFF PAYLOAD', uses: { n: false, users: false } },
] as const

type FireMode = (typeof MODE_DEFS)[number]['key']

const OUTCOME_META: Record<FireOutcome, { label: string; cls: string }> = {
  NEW: { label: 'NEW', cls: 'new' },
  REPLAY: { label: 'REPLAY', cls: 'replay' },
  CONFLICT_409: { label: '409 CONFLICT', cls: 'conflict_409' },
  BLOCKED_503: { label: '503 BLOCKED', cls: 'blocked_503' },
  ERROR: { label: 'ERROR', cls: 'error' },
}

function Sparkline({ values, stroke, fill }: { values: number[]; stroke: string; fill: string }) {
  const W = 300
  const H = 60
  if (values.length < 2) return <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="spark-svg" />
  const max = Math.max(...values, 1)
  const pts = values
    .map((v, i) => `${((i / (values.length - 1)) * W).toFixed(1)},${(H - 4 - (v / max) * (H - 8)).toFixed(1)}`)
    .join(' ')
  return (
    <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="spark-svg">
      <polygon points={`${pts} ${W},${H} 0,${H}`} style={{ fill }} />
      <polyline points={pts} style={{ fill: 'none', stroke, strokeWidth: 1.5 }} />
    </svg>
  )
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [busy, setBusy] = useState(false)
  const [mode, setMode] = useState<FireMode>('single')
  const [count, setCount] = useState(50)
  const [delta, setDelta] = useState(25)
  const [userCount, setUserCount] = useState(10)
  const [results, setResults] = useState<FireResult[]>([])
  const [tab, setTab] = useState<'ranking' | 'idem' | 'audit'>('ranking')

  const breaker = usePoll(getBreakerStatus)
  const streams = usePoll(getStreamsStatus)
  const snapStatus = usePoll(getSnapshotStatus, 1000)
  const breakerStates = useRingBuffer(breaker?.state ?? null, 240)
  const streamLengths = useRingBuffer(streams?.streamLength ?? null, 240)
  const tops = usePoll(() => (session ? getTops(session.leaderboardId, 50) : Promise.resolve(null)), 1000)
  const snapshot = usePoll(
    () => (session ? getSnapshotEntries(session.leaderboardId).catch(() => null) : Promise.resolve(null)),
    2000,
  )

  const counters = useMemo(() => {
    const c: Record<FireOutcome, number> = { NEW: 0, REPLAY: 0, CONFLICT_409: 0, BLOCKED_503: 0, ERROR: 0 }
    results.forEach((r) => c[r.outcome]++)
    return c
  }, [results])
  const latencies = results.slice(-120).map((r) => r.latencyMs)
  const lastBlocked = [...results].reverse().find((r) => r.outcome === 'BLOCKED_503')

  // 발사 전에 "무슨 일이 일어나고 무엇을 기대해야 하는지"를 문장으로 미리 보여준다
  const preview =
    mode === 'single'
      ? `→ fires 1 request · fresh key · 1 user drawn from pool of ${userCount} · delta +${delta} · expect: 1 NEW`
      : mode === 'concurrent-same-key'
        ? `→ fires ${count} concurrent · ONE shared key · ONE user (same payload required — a different user would change the hash and cause 409) · expect: 1 NEW + ${count - 1} REPLAY`
        : mode === 'concurrent-random-key'
          ? `→ fires ${count} concurrent · unique keys · users drawn from pool of ${userCount} · delta +${delta} each · expect: ${count} NEW`
          : `→ fires 2 sequential · same key · delta +${delta} then +${delta + 1} · expect: 1 NEW + 1 409 CONFLICT`

  const state = breaker?.state ?? 'UNKNOWN'
  const stateCls = state.toLowerCase()
  const isOpen = state === 'OPEN'
  const isHalf = state === 'HALF_OPEN'

  async function fire() {
    if (!session || busy) return
    setBusy(true)
    try {
      // 유저 풀을 USERS 입력값까지 지연 확장 후 랜덤 분배
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
        // mismatch: 같은 키로 delta를 바꿔 재요청 → 두 번째는 409여야 한다
        const key = crypto.randomUUID()
        const userId = pick()
        const first = await fireEvent(s, { idempotencyKey: key, deltaScore: delta, userId })
        const second = await fireEvent(s, { idempotencyKey: key, deltaScore: delta + 1, userId })
        fired = [first, second]
      }
      setResults((prev) => [...prev.slice(-400), ...fired])
    } finally {
      setBusy(false)
    }
  }

  // 동점 내 표시 순서는 저장소별로 다르다 (ZREVRANGE는 lex 역순, SQL은 user_id 순 — PRD 5.2는 미규정)
  // 나란히 비교하는 뷰이므로 화면에서만 같은 기준(rank → userId 숫자순)으로 정렬한다
  const byRankThenUser = (a: { rank: number; userId: string }, b: { rank: number; userId: string }) =>
    a.rank - b.rank || Number(a.userId) - Number(b.userId)
  const hotItems = [...(tops?.items ?? [])].sort(byRankThenUser)
  const coldItems = [...(snapshot?.items ?? [])].sort(byRankThenUser)
  const coldByUser = new Map(coldItems.map((it) => [it.userId, it.score]))
  const driftByUser = new Map(
    hotItems.map((it) => [it.userId, it.score - (coldByUser.get(it.userId) ?? 0)] as const),
  )
  const driftingCount = [...driftByUser.values()].filter((d) => d !== 0).length

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
          <span>{breaker ? 'api connected' : 'api unreachable'}</span>
        </div>
        <div className={`breaker-pill ${stateCls}`}>BREAKER · {state}</div>
      </header>

      {isOpen && <div className="hazard-strip" />}
      {isOpen && (
        <div className="banner open">
          <strong>CIRCUIT OPEN</strong>
          <span>
            write path failing fast with 503
            {lastBlocked?.retryAfter ? ` · Retry-After ${lastBlocked.retryAfter}s` : ''} · audit replay reconciles on
            recovery
          </span>
        </div>
      )}
      {isHalf && (
        <div className="banner half_open">
          <strong>HALF_OPEN · PROBING</strong>
          <span>trial requests admitted · closes on consecutive successes, reopens on first failure</span>
        </div>
      )}

      <main className="grid">
        <div className="col">
          <div className="section-title">SCENARIO PLAYGROUND</div>

          <section className="card">
            <div className="card-head">
              <span className="title">EVENT FIRER</span>
              <span className="meta">POST /events</span>
            </div>
            <div className="card-body">
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
                  BOOTSTRAP DEMO SESSION (user → project → leaderboard)
                </button>
              ) : (
                <>
                  <div className="fire-row">
                    <div className="mode-group">
                      {MODE_DEFS.map((m) => (
                        <button key={m.key} className={mode === m.key ? 'active' : ''} onClick={() => setMode(m.key)}>
                          {m.label}
                        </button>
                      ))}
                    </div>
                  </div>
                  {(() => {
                    const uses = MODE_DEFS.find((m) => m.key === mode)!.uses
                    const cols = 1 + (uses.n ? 1 : 0) + (uses.users ? 1 : 0)
                    return (
                      <div className="firer-fields" style={{ gridTemplateColumns: `1.3fr repeat(${cols}, 0.6fr)` }}>
                        <label>
                          <span>LEADERBOARD</span>
                          <input value={`console-board (${session.leaderboardId.slice(0, 8)})`} disabled />
                        </label>
                        <label>
                          <span>SCORE DELTA (+)</span>
                          <input
                            type="number"
                            min={1}
                            value={delta}
                            onChange={(e) => setDelta(Number(e.target.value))}
                          />
                        </label>
                        {uses.n && (
                          <label>
                            <span>REQUESTS (N)</span>
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
                            <span>USER POOL SIZE</span>
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
                    )
                  })()}
                  <div className="fire-row">
                    <div className="mode-hint" style={{ flex: 1 }}>
                      {preview}
                    </div>
                    <button className="fire-btn" onClick={fire} disabled={busy}>
                      {busy ? 'FIRING…' : 'FIRE'}
                    </button>
                  </div>
                </>
              )}
            </div>
          </section>

          <section className="card">
            <div className="card-head">
              <span className="title">RESPONSE DISTRIBUTION</span>
              <span className="meta">session total</span>
            </div>
            <div className="counters">
              <div className="counter new">
                <div className="label">NEW</div>
                <div className="num">{counters.NEW}</div>
                <div className="sub">200 created</div>
              </div>
              <div className="counter replay">
                <div className="label">REPLAY</div>
                <div className="num">{counters.REPLAY}</div>
                <div className="sub">200 cached</div>
              </div>
              <div className="counter conflict">
                <div className="label">409 CONFLICT</div>
                <div className="num">{counters.CONFLICT_409}</div>
                <div className="sub">key reuse</div>
              </div>
              <div className={`counter blocked ${isOpen ? 'alert' : ''}`}>
                <div className="label">503 BLOCKED</div>
                <div className="num">{counters.BLOCKED_503}</div>
                <div className="sub">breaker fail-fast</div>
              </div>
            </div>
            <div className="log-section">
              <div className="log-title">RECENT FIRE RESULTS</div>
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
                        <span className="status-chip">{meta.label}</span>
                        <span className="lat">{r.latencyMs.toFixed(0)}ms</span>
                      </div>
                    )
                  })}
              </div>
            </div>
          </section>

          <div className="two-col">
            <section className={`card ${isOpen ? 'danger-edge' : ''}`}>
              <div className="card-head">
                <span className="title">FAULT INJECTION</span>
                <span className={`pill-meta ${isOpen ? 'alert' : ''}`}>
                  {isOpen ? 'breaker: open' : 'override: none'}
                </span>
              </div>
              <div className="card-body">
                {/* force-open/clear internal API는 백로그 (12_Ops_Console_스펙) — 설계 후 활성화 */}
                <div className="btn-row">
                  <button className="btn-danger" disabled title="internal API 설계 대기">
                    FORCE OPEN BREAKER
                  </button>
                  <button className="btn-ghost" disabled title="internal API 설계 대기">
                    CLEAR OVERRIDE
                  </button>
                </div>
                <div className="card-metric">
                  <span>Retry-After</span>
                  <span className={`big ${isOpen ? 'alert' : 'idle'}`}>
                    {isOpen && lastBlocked?.retryAfter ? `${lastBlocked.retryAfter}s` : '—'}
                  </span>
                  <span className="note">
                    {isOpen ? 'sent on every 503' : 'no active backoff · demo: docker compose stop redis'}
                  </span>
                </div>
              </div>
            </section>

            <section className="card">
              <div className="card-head">
                <span className="title">SNAPSHOT (COLD PATH)</span>
              </div>
              <div className="card-body">
                <div className="btn-row">
                  <button className="btn-primary" disabled title="internal API 설계 대기">
                    CAPTURE NOW
                  </button>
                  <button className="btn-ghost" disabled title="internal API 설계 대기">
                    RECOVER
                  </button>
                </div>
                <div className="card-metric">
                  <span className={`dot ${snapStatus?.lastSuccessfulSnapshotAt ? 'ok' : 'down'}`} />
                  <span>last success</span>
                  <span style={{ color: 'var(--color-text)' }}>
                    {snapStatus?.lastSuccessfulSnapshotAt
                      ? new Date(snapStatus.lastSuccessfulSnapshotAt).toISOString().slice(11, 19) + 'Z'
                      : '—'}
                  </span>
                  <span className="note">
                    {snapStatus ? `· lag ${snapStatus.snapshotLagSeconds}s · redis → postgres` : ''}
                  </span>
                </div>
              </div>
            </section>
          </div>
        </div>

        <div className="col">
          <div className="section-title">
            LIVE METRICS <span className={`dot ${breaker ? 'ok' : 'down'} pulse`} /> <span className="meta">0.5s poll</span>
          </div>

          <section className="card metric-card">
            <div className="metric-head">
              <span className="title">BREAKER STATE · {breakerStates.length} ticks</span>
              <span className={`value ${stateCls}`}>{state}</span>
            </div>
            <div className="breaker-timeline">
              {breakerStates.map((s, i) => (
                <span key={i} className={`tick tick-${s.toLowerCase()}`} />
              ))}
            </div>
            <div className="legend">
              <span>
                <i style={{ background: 'var(--color-green)' }} />CLOSED
              </span>
              <span>
                <i style={{ background: 'var(--color-red)' }} />OPEN
              </span>
              <span>
                <i style={{ background: 'var(--color-amber)' }} />HALF_OPEN
              </span>
            </div>
          </section>

          <section className="card metric-card">
            <div className="metric-head">
              <span className="title">AUDIT STREAM LENGTH</span>
              <span className="value plain">len {streams?.streamLength ?? '—'}</span>
            </div>
            <Sparkline values={streamLengths} stroke="var(--color-blue)" fill="rgba(76,154,255,.10)" />
            <div className="metric-note">XADD rate ≈ write throughput · new-path only</div>
          </section>

          <section className="card metric-card">
            <div className="metric-head">
              <span className="title">REQUEST LATENCY</span>
              <span className={`value ${isOpen ? 'open' : isHalf ? 'half_open' : 'closed'}`}>
                {latencies.length ? `last ${latencies[latencies.length - 1].toFixed(0)}ms` : '—'}
              </span>
            </div>
            <Sparkline
              values={latencies}
              stroke={isOpen ? 'var(--color-red)' : isHalf ? 'var(--color-amber)' : 'var(--color-green)'}
              fill={isOpen ? 'rgba(240,82,79,.10)' : isHalf ? 'rgba(227,179,65,.10)' : 'rgba(63,185,80,.10)'}
            />
            <div className="metric-note">
              {isOpen
                ? 'fail-fast: breaker rejects before Redis I/O — no thread pile-up'
                : 'hot path: Lua (idem check + ZINCRBY + XADD), single round trip'}
            </div>
          </section>
        </div>

        <section className="card inspector">
          <div className="tabs-head">
            <span className="title">DATA INSPECTOR</span>
            <button className={tab === 'ranking' ? 'active' : ''} onClick={() => setTab('ranking')}>
              Ranking (Hot vs Cold)
            </button>
            <button className={tab === 'idem' ? 'active' : ''} onClick={() => setTab('idem')}>
              Idempotency Keys
            </button>
            <button className={tab === 'audit' ? 'active' : ''} onClick={() => setTab('audit')}>
              Audit Stream
            </button>
          </div>
          <div className="inspector-body">
            {tab === 'ranking' ? (
              <>
                <div className="hot-cold">
                  <div className="table-card">
                    <div className="table-card-head">
                      <span className="dot" style={{ background: 'var(--color-red)' }} />
                      <strong>REDIS ZSET</strong>
                      <span className="meta">hot path · live</span>
                    </div>
                    <div className="rank-head cols-3">
                      <span>#</span>
                      <span>USER</span>
                      <span className="right">SCORE</span>
                    </div>
                    {hotItems.map((it) => (
                      <div key={it.rank} className={`rank-row cols-3 ${driftByUser.get(it.userId) ? 'drifting' : ''}`}>
                        <span className="rank">{it.rank}</span>
                        <span>{it.userId}</span>
                        <span className="right">{it.score.toLocaleString()}</span>
                      </div>
                    ))}
                    {!hotItems.length && <div className="hint" style={{ padding: 12 }}>no data — bootstrap & fire</div>}
                  </div>
                  <div className="table-card">
                    <div className="table-card-head">
                      <span className="dot" style={{ background: 'var(--color-blue)' }} />
                      <strong>POSTGRESQL SNAPSHOT</strong>
                      <span className="meta">
                        {snapshot ? `seq ${snapshot.snapshotId} · ${snapshot.snapshotAt?.slice(11, 19)}Z` : 'cold path'}
                      </span>
                    </div>
                    <div className="rank-head cols-4">
                      <span>#</span>
                      <span>USER</span>
                      <span className="right">SCORE</span>
                      <span className="right">DRIFT</span>
                    </div>
                    {coldItems.map((it) => {
                      const drift = (driftByUser.get(it.userId) ?? 0) as number
                      return (
                        <div key={it.rank} className={`rank-row cols-4 ${drift ? 'drifting' : ''}`}>
                          <span className="rank">{it.rank}</span>
                          <span>{it.userId}</span>
                          <span className="right">{it.score.toLocaleString()}</span>
                          <span className="drift-val">{drift ? `+${drift}` : '·'}</span>
                        </div>
                      )
                    })}
                    {!coldItems.length && (
                      <div className="hint" style={{ padding: 12 }}>no snapshot yet — 30s cycle</div>
                    )}
                  </div>
                </div>
                <div className={`drift-note ${driftingCount ? 'active' : ''}`}>
                  {driftingCount
                    ? `${driftingCount} rows drifting — hot path is ahead of last snapshot · reconciles at next capture`
                    : 'no drift — snapshot is in sync with hot path'}
                </div>
              </>
            ) : (
              <p className="hint">read-only internal API 설계 대기 (12_Ops_Console_스펙 백로그)</p>
            )}
          </div>
        </section>
      </main>
    </div>
  )
}
