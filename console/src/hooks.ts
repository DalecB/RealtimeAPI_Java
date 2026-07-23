import { useEffect, useRef, useState } from 'react'

export const POLL_MS = 500

// 0.5초 폴링 — 실패는 null로 삼키고 다음 틱에 재시도 (백엔드 미기동/장애 주입 중에도 콘솔은 살아있어야 함)
export function usePoll<T>(fn: () => Promise<T>, intervalMs = POLL_MS): T | null {
  const [value, setValue] = useState<T | null>(null)
  const fnRef = useRef(fn)
  fnRef.current = fn
  useEffect(() => {
    let alive = true
    const tick = () => {
      fnRef.current()
        .then((v) => alive && setValue(v))
        .catch(() => alive && setValue(null))
    }
    tick()
    const id = setInterval(tick, intervalMs)
    return () => {
      alive = false
      clearInterval(id)
    }
  }, [intervalMs])
  return value
}

// 최근 N개 샘플 링버퍼 — 그래프의 "실시간"은 전부 클라이언트 축적으로 해결 (서버 히스토리 불필요)
export function useRingBuffer<T>(sample: T | null, capacity = 600): T[] {
  const buf = useRef<T[]>([])
  if (sample !== null && sample !== undefined) {
    buf.current = [...buf.current.slice(-(capacity - 1)), sample]
  }
  return buf.current
}
