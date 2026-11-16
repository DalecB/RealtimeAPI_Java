import type { Role } from "@/lib/session";
import { fmtClock, fmtDuration } from "@/lib/format";
import { RoleChip } from "./RoleChip";
import { StaleValue } from "./StaleValue";

// Crumbs, role, poll freshness and the clock in UTC and local time. A failing poll is shown here as stale.
export function TopBar({ crumbs, role, lastPollAt, pollFailing, now }: { crumbs: string[]; role: Role; lastPollAt?: number; pollFailing: boolean; now: number }) {
  const ago = lastPollAt ? fmtDuration((now - lastPollAt) / 1000) : null;
  return (
    <div className="TopBar">
      <div className="TopBar-crumbs">
        {crumbs.map((c, i) =>
          i === crumbs.length - 1 ? (
            <b key={i}>{c}</b>
          ) : (
            <span key={i} style={{ display: "contents" }}>
              <span>{c}</span>
              <span aria-hidden="true">/</span>
            </span>
          ),
        )}
      </div>
      <div className="TopBar-meta">
        <RoleChip role={role} />
        {pollFailing ? (
          <span className="mono">
            <StaleValue tone="warn" value={ago ? `poll failing · last ok ${ago} ago` : "poll failing · no answer yet"} asOf={lastPollAt ?? now} />
          </span>
        ) : (
          <span className="mono">{ago ? `polled ${ago} ago` : "polling…"}</span>
        )}
        <span className="mono">{fmtClock(now)}</span>
      </div>
    </div>
  );
}
