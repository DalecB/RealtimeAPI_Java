import { SNAPSHOT } from "@/lib/api";
import { fmtDuration } from "@/lib/format";

// Age of the latest PostgreSQL snapshot against the 45 s objective; the tick on the meter is the 30 s interval.
export function FreshnessIndicator({
  lagSeconds,
  sloSeconds = SNAPSHOT.sloSeconds,
  intervalSeconds = SNAPSHOT.intervalSeconds,
}: {
  lagSeconds: number | null;
  sloSeconds?: number;
  intervalSeconds?: number;
}) {
  if (lagSeconds === null) {
    return (
      <span className="FreshnessIndicator is-idle">
        <span className="FreshnessIndicator-meter">
          <i style={{ width: 0 }} />
        </span>
        <b>no snapshot yet</b>
      </span>
    );
  }
  const tone = lagSeconds > sloSeconds ? "is-fail" : lagSeconds >= intervalSeconds ? "is-warn" : "";
  return (
    <span className={`FreshnessIndicator ${tone}`.trim()}>
      <span className="FreshnessIndicator-meter">
        <i style={{ width: `${Math.min(100, (lagSeconds / sloSeconds) * 100)}%` }} />
      </span>
      snapshot <b>{fmtDuration(lagSeconds)}</b> ago{lagSeconds > sloSeconds && " · stale"}
    </span>
  );
}
