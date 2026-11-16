import type { Status } from "@/lib/pipeline";
import { Glyph } from "./Glyph";

// No status: a neutral badge without a glyph (evidence "Passed", a neutral Reconcile result).
export function StatusBadge({ status, label, quiet }: { status?: Status; label: string; quiet?: boolean }) {
  const cls = ["StatusBadge", quiet && "is-quiet", status && status !== "idle" && `is-${status}`].filter(Boolean).join(" ");
  return (
    <span className={cls}>
      {status && <Glyph status={status} />}
      {label}
    </span>
  );
}
