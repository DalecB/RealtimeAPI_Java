import { MISSING, type FireOutcome } from "@/lib/api";
import { fmtInt } from "@/lib/format";
import { NeedsEndpoint } from "./NeedsEndpoint";
import { OutcomeTag } from "./OutcomeTag";

const ORDER: FireOutcome[] = ["BLOCKED_503", "ERROR", "NEW", "REPLAY", "CONFLICT_409", "RATE_LIMITED_429"];
const BAR: Record<FireOutcome, string> = {
  NEW: "is-new",
  REPLAY: "is-replay",
  CONFLICT_409: "is-conflict",
  RATE_LIMITED_429: "is-conflict",
  BLOCKED_503: "is-blocked",
  ERROR: "is-error",
};

// counts null: the outcome sample endpoint does not exist yet.
export function OutcomeBreakdown({ counts, sampleLabel }: { counts: Record<FireOutcome, number> | null; sampleLabel: string }) {
  if (!counts) {
    return (
      <div className="PH-block">
        <div className="Sparkline-caption">{sampleLabel}</div>
        <NeedsEndpoint what="Outcome counts" endpoint={MISSING.outcomes} />
      </div>
    );
  }
  const total = ORDER.reduce((n, o) => n + counts[o], 0) || 1;
  const shown = ORDER.filter((o) => counts[o] > 0 || o === "BLOCKED_503");
  return (
    <div className="PH-block">
      <div className="Sparkline-caption">{sampleLabel}</div>
      <div className="OutcomeBreakdown">
        <div className="OutcomeBreakdown-bar">
          {shown.filter((o) => counts[o] > 0).map((o) => (
            <i key={o} className={BAR[o]} style={{ width: `${(counts[o] / total) * 100}%` }} />
          ))}
        </div>
        <div className="OutcomeBreakdown-rows">
          {shown.map((o) => (
            <Row key={o} outcome={o} count={counts[o]} share={Math.round((counts[o] / total) * 100)} />
          ))}
        </div>
      </div>
    </div>
  );
}

function Row({ outcome, count, share }: { outcome: FireOutcome; count: number; share: number }) {
  return (
    <>
      <span className="is-tag">
        <OutcomeTag outcome={outcome} />
      </span>
      <span>{fmtInt(count)}</span>
      <span className="muted">{share}%</span>
    </>
  );
}
