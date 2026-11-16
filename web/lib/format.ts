const int = new Intl.NumberFormat("en-US");

export const fmtInt = (n: number) => int.format(n);

// Counts of 100,000 or more switch to 300.0k in a narrow PipelineStrip.
export const fmtCompact = (n: number) =>
  Math.abs(n) >= 1_000_000
    ? `${(n / 1_000_000).toFixed(1)}M`
    : Math.abs(n) >= 100_000
      ? `${(n / 1000).toFixed(1)}k`
      : fmtInt(n);

export const fmtPercent = (n: number) => `${Number.isInteger(n) ? n : n.toFixed(1)}%`;

const pad = (n: number) => String(n).padStart(2, "0");

export function fmtUtcTime(iso: string | number) {
  const d = new Date(iso);
  return `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())} UTC`;
}

export function fmtLocalTime(iso: string | number) {
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())} local`;
}

// 2026-11-16 14:32:07 UTC · 23:32:07 local
export function fmtClock(ms: number) {
  const d = new Date(ms);
  const date = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`;
  return `${date} ${fmtUtcTime(ms)} · ${fmtLocalTime(ms)}`;
}

// 27s · 1m 27s · 2h 4m
export function fmtDuration(seconds: number) {
  const s = Math.max(0, Math.round(seconds));
  if (s < 60) return `${s}s`;
  if (s < 3600) return `${Math.floor(s / 60)}m ${s % 60}s`;
  return `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
}

export const secondsSince = (iso: string | number, now: number) => (now - new Date(iso).getTime()) / 1000;
