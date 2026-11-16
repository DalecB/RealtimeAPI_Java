// A small series beside a number. Never drawn without a real series behind it.
export function Sparkline({ points, limit, tone = "neutral", label }: { points: number[]; limit?: number; tone?: "neutral" | "warn" | "fail"; label: string }) {
  const max = Math.max(limit ?? 0, ...points) || 1;
  const step = points.length > 1 ? 92 / (points.length - 1) : 0;
  const xy = points.map((v, i) => [2 + i * step, 22 - (v / max) * 20] as const);
  const last = xy.at(-1);
  const cls = ["Sparkline", tone !== "neutral" && `is-${tone}`, points.length < 2 && "is-empty"].filter(Boolean).join(" ");
  return (
    <svg className={cls} viewBox="0 0 96 24" role="img" aria-label={label}>
      {limit !== undefined && <line x1="0" y1="2" x2="96" y2="2" />}
      <polyline points={(points.length < 2 ? [[2, 22], [94, 22]] : xy).map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(" ")} />
      {last && points.length > 1 && <circle cx={last[0].toFixed(1)} cy={last[1].toFixed(1)} r="2" />}
    </svg>
  );
}
