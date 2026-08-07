import { useEffect, useMemo, useState } from 'react'
import { getAuditLeaderboards, getScoreTrend, type TrendBucket } from './api'

// 기간 → 적응형 버킷 + 조회 길이(ms). 서버가 버킷 코드를 interval로 해석한다.
const RANGES = {
  '1h': { bucket: '1m', ms: 60 * 60 * 1000, label: '1H' },
  '6h': { bucket: '5m', ms: 6 * 60 * 60 * 1000, label: '6H' },
  '24h': { bucket: '15m', ms: 24 * 60 * 60 * 1000, label: '24H' },
  '7d': { bucket: '1h', ms: 7 * 24 * 60 * 60 * 1000, label: '7D' },
} as const

type RangeKey = keyof typeof RANGES

function fmtTime(iso: string, range: RangeKey): string {
  const d = new Date(iso)
  if (range === '7d') return `${d.getMonth() + 1}/${d.getDate()}`
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// 축·눈금 있는 누적 점수 라인. 스파크라인과 달리 uniform 스케일이라 텍스트가 안 뭉개진다.
function LineChart({ buckets, range }: { buckets: TrendBucket[]; range: RangeKey }) {
  const W = 900
  const H = 320
  const padL = 56
  const padR = 20
  const padT = 16
  const padB = 34

  // 누적: delta 합의 러닝 합(구간 시작 0에서 상승)
  const points = useMemo(() => {
    let running = 0
    return buckets.map((b) => {
      running += b.deltaSum
      return { t: b.bucketStart, cumulative: running }
    })
  }, [buckets])

  if (points.length === 0) {
    return <div className="trend-empty">이 구간에 데이터가 없습니다 — 대시보드에서 이벤트를 쏘세요.</div>
  }

  const maxY = Math.max(...points.map((p) => p.cumulative), 1)
  const plotW = W - padL - padR
  const plotH = H - padT - padB
  const x = (i: number) => padL + (points.length === 1 ? plotW / 2 : (i / (points.length - 1)) * plotW)
  const y = (v: number) => padT + plotH - (v / maxY) * plotH

  const line = points.map((p, i) => `${x(i).toFixed(1)},${y(p.cumulative).toFixed(1)}`).join(' ')
  const area = `${line} ${x(points.length - 1).toFixed(1)},${(padT + plotH).toFixed(1)} ${x(0).toFixed(1)},${(padT + plotH).toFixed(1)}`

  const yTicks = [0, 0.5, 1].map((f) => Math.round(maxY * f))
  const xIdx = points.length <= 1 ? [0] : [0, Math.floor((points.length - 1) / 2), points.length - 1]

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="trend-chart" preserveAspectRatio="xMidYMid meet">
      {yTicks.map((v) => (
        <g key={v}>
          <line x1={padL} x2={W - padR} y1={y(v)} y2={y(v)} className="grid" />
          <text x={padL - 10} y={y(v) + 4} className="ylabel">
            {v.toLocaleString()}
          </text>
        </g>
      ))}
      {xIdx.map((i) => (
        <text key={i} x={x(i)} y={H - 12} className="xlabel">
          {fmtTime(points[i].t, range)}
        </text>
      ))}
      <polygon points={area} className="trend-area" />
      <polyline points={line} className="trend-line" />
      {/* 끝점 앵커. 누적은 단조 증가라 이 값은 y축 최댓값·summary와 같으므로 텍스트는 생략. */}
      <circle cx={x(points.length - 1)} cy={y(points[points.length - 1].cumulative)} r="3.5" className="trend-dot" />
    </svg>
  )
}

export default function TrendPage({ onBack }: { onBack: () => void }) {
  const [leaderboards, setLeaderboards] = useState<string[]>([])
  const [selected, setSelected] = useState<string>('')
  const [range, setRange] = useState<RangeKey>('6h')
  const [buckets, setBuckets] = useState<TrendBucket[]>([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    getAuditLeaderboards()
      .then((ids) => {
        setLeaderboards(ids)
        if (ids.length) setSelected((cur) => cur || ids[0])
      })
      .catch(() => setLeaderboards([]))
  }, [])

  useEffect(() => {
    if (!selected) return
    const to = new Date()
    const from = new Date(to.getTime() - RANGES[range].ms)
    setLoading(true)
    getScoreTrend(selected, from.toISOString(), to.toISOString(), RANGES[range].bucket)
      .then(setBuckets)
      .catch(() => setBuckets([]))
      .finally(() => setLoading(false))
  }, [selected, range])

  const total = buckets.reduce((s, b) => s + b.deltaSum, 0)
  const events = buckets.reduce((s, b) => s + b.eventCount, 0)

  return (
    <div className="app">
      <header className="topbar trend-topbar">
        <button className="back-link" onClick={onBack}>
          ← Dashboard
        </button>
        <div className="trend-title">Score Trends</div>
        <div className="spacer" />
      </header>

      <main className="content trend-content">
        <div className="trend-controls">
          <select
            className="lb-select"
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            disabled={!leaderboards.length}
          >
            {leaderboards.length === 0 && <option>데이터 있는 리더보드 없음</option>}
            {leaderboards.map((id) => (
              <option key={id} value={id}>
                {id.slice(0, 8)}…
              </option>
            ))}
          </select>
          <div className="range-seg">
            {(Object.keys(RANGES) as RangeKey[]).map((k) => (
              <button key={k} className={range === k ? 'on' : ''} onClick={() => setRange(k)}>
                {RANGES[k].label}
              </button>
            ))}
          </div>
        </div>

        <div className="trend-card">
          <div className="trend-card-head">
            <span className="title">누적 점수</span>
            <span className="trend-summary">
              total +{total.toLocaleString()} · {events.toLocaleString()} events · {buckets.length} buckets
            </span>
          </div>
          {loading ? <div className="trend-empty">불러오는 중…</div> : <LineChart buckets={buckets} range={range} />}
          <div className="trend-note">
            버킷: {RANGES[range].bucket} (기간에 맞춰 자동) · 누적 = 구간 내 delta 러닝 합
          </div>
        </div>
      </main>
    </div>
  )
}
