import type { ReactNode } from "react";
import { StatusBadge } from "./StatusBadge";

export type SloStatus = "ok" | "warn" | "fail" | "unmeasured";

// Live budgets: ok under 80% of the limit, warn from 80%, fail once the objective's "<" no longer holds.
export function sloStatus(used: number | null): SloStatus {
  if (used === null) return "unmeasured";
  return used >= 1 ? "fail" : used >= 0.8 ? "warn" : "ok";
}

const LIVE_LABEL: Record<SloStatus, string> = { ok: "Within SLO", warn: "At risk", fail: "Breached", unmeasured: "Not measured" };

// One objective as budget consumed against its limit. live: polled now, colored, with a StatusBadge.
// evidence: a past load test, neutral fill and a plain Passed label. The two never share a panel.
export function SloBudget({
  kind,
  name,
  objective,
  source,
  value,
  detail,
  used,
  scale,
  limitLabel,
  history,
  status,
}: {
  kind: "live" | "evidence";
  name: string;
  objective: string;
  source: ReactNode;
  value: ReactNode | null; // null: no data
  detail: string;
  used: number | null; // share of the budget, 1 = at the limit
  scale: ReactNode;
  limitLabel: string;
  history: ReactNode; // a Sparkline, or a short text such as "live value"
  status: SloStatus;
}) {
  const live = kind === "live";
  const cls = ["SloBudget", !live && "is-evidence", live && (status === "warn" || status === "fail") && `is-${status}`].filter(Boolean).join(" ");
  return (
    <div className={cls}>
      <div>
        <div className="SloBudget-name">{name}</div>
        <div className="SloBudget-objective">{objective}</div>
        <div className="SloBudget-source">{source}</div>
      </div>
      <div>
        <div className={value === null ? "SloBudget-value muted" : live && status === "fail" ? "SloBudget-value t-fail" : "SloBudget-value"}>{value ?? "no data"}</div>
        <div className="SloBudget-sub">{detail}</div>
      </div>
      <div className="SloBudget-bar">
        <div className="SloBudget-track">
          <i className="SloBudget-fill" style={{ width: `${Math.min(100, (used ?? 0) * 100)}%` }} />
        </div>
        <div className="SloBudget-scale">
          <span>{scale}</span>
          <span>{limitLabel}</span>
        </div>
      </div>
      <div className="SloBudget-history">{history}</div>
      <div className="SloBudget-status">
        {live ? (
          <StatusBadge status={status === "unmeasured" ? "idle" : status} label={LIVE_LABEL[status]} />
        ) : (
          <StatusBadge label={status === "fail" ? "Rejected" : "Passed"} />
        )}
      </div>
    </div>
  );
}
