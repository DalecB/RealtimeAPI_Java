import type { ReactNode } from "react";
import { fmtUtcTime } from "@/lib/format";

// A number last read from a source that stopped answering: muted with a clock, never red.
export function StaleValue({ value, asOf, tone = "neutral" }: { value: ReactNode; asOf: string | number; tone?: "neutral" | "warn" }) {
  return (
    <span className={tone === "warn" ? "StaleValue is-warn" : "StaleValue"} title={`last known at ${fmtUtcTime(asOf)}`}>
      <svg className="StaleValue-glyph" viewBox="0 0 12 12" aria-hidden="true">
        <circle cx="6" cy="6" r="4.5" />
        <path d="M6 3.4V6l1.9 1.2" />
      </svg>
      <span className="sr">last known at {fmtUtcTime(asOf)}: </span>
      {value}
    </span>
  );
}
