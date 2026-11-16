import type { FireOutcome } from "@/lib/api";

const TAGS: Record<FireOutcome, { cls: string; label: string }> = {
  NEW: { cls: "is-new", label: "NEW" },
  REPLAY: { cls: "is-replay", label: "REPLAY" },
  CONFLICT_409: { cls: "is-conflict", label: "409 CONFLICT" },
  RATE_LIMITED_429: { cls: "is-limited", label: "429 LIMITED" },
  BLOCKED_503: { cls: "is-blocked", label: "503 BLOCKED" },
  ERROR: { cls: "is-error", label: "ERROR" },
};

export function OutcomeTag({ outcome }: { outcome: FireOutcome }) {
  const t = TAGS[outcome];
  return <span className={`OutcomeTag ${t.cls}`}>{t.label}</span>;
}
