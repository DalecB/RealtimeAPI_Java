import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { getSession, subscribeSession, type Session } from "./session";

export const POLL_MS = 2000;

// One polled source. A failed poll keeps the last good value: `data` with `error` set is a stale
// reading, and components show it as StaleValue instead of dropping it.
export interface Poll<T> {
  data?: T;
  okAt?: number; // epoch ms of the last successful poll
  error?: unknown; // set while the latest poll failed
}

export const isStale = <T>(p: Poll<T>) => p.error !== undefined && p.data !== undefined;

// Ported from console/src/hooks.ts usePoll. `key` restarts polling when the inputs change.
export function usePoll<T>(fn: () => Promise<T>, intervalMs = POLL_MS, key = ""): Poll<T> {
  const [state, setState] = useState<Poll<T> & { key: string }>({ key });
  const fnRef = useRef(fn);
  useEffect(() => {
    fnRef.current = fn;
  });
  useEffect(() => {
    let alive = true;
    const tick = () => {
      fnRef
        .current()
        .then((data) => alive && setState({ key, data, okAt: Date.now() }))
        .catch((error: unknown) =>
          alive && setState((s) => (s.key === key ? { ...s, error } : { key, error })),
        );
    };
    tick();
    const id = setInterval(tick, intervalMs);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [intervalMs, key]);
  // A new key shows nothing until its first poll lands, never the previous key's data.
  return state.key === key ? state : {};
}

// Wall clock for "polled 2s ago" and countdowns; ticks once a second.
export function useNow(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return now;
}

// undefined while rendering on the server or hydrating: the session only exists in the browser.
export function useSession(): Session | null | undefined {
  return useSyncExternalStore(subscribeSession, getSession, () => undefined);
}
