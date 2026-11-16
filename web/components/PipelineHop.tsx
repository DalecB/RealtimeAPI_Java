import type { ReactNode } from "react";
import type { Status } from "@/lib/pipeline";
import { Glyph } from "./Glyph";
import { KeyValueList, type KeyValueRow } from "./KeyValueList";
import { StatusBadge } from "./StatusBadge";

export type HopLink = "flowing" | "blocked" | "none";

const railCls = (base: string, l: HopLink) => (l === "flowing" ? base : `${base} is-${l}`);

// The expanded form of one strip node on /delivery. Its StatusBadge repeats the strip value word for word.
export function PipelineHop({
  id,
  step,
  title,
  status,
  statusLabel,
  resource,
  rows,
  note,
  cause,
  linkIn = "flowing",
  linkOut = "flowing",
  children,
}: {
  id: string;
  step: number;
  title: string;
  status: Status;
  statusLabel: string;
  resource: ReactNode;
  rows: KeyValueRow[];
  note?: string;
  cause?: string; // "caused by Redis" under the badge of an affected hop
  linkIn?: HopLink;
  linkOut?: HopLink;
  children?: ReactNode; // e.g. PartitionLagBar[], OutcomeBreakdown, NeedsEndpoint
}) {
  return (
    <section className="PipelineHop" id={id} aria-labelledby={`${id}-title`}>
      <div className="PH-rail" aria-hidden="true">
        <i className={railCls("PH-in", linkIn)} />
        <Glyph status={status} />
        <i className={railCls("PH-out", linkOut)} />
      </div>
      <div className="PH-head">
        <h3 className="PH-title" id={`${id}-title`}>
          <span className="PH-step">{String(step).padStart(2, "0")}</span>
          {title}
        </h3>
        <StatusBadge status={status} label={statusLabel} />
        {cause && <span className="PH-cause">{cause}</span>}
      </div>
      <div className="PH-res">{resource}</div>
      {rows.length > 0 && <KeyValueList rows={rows} />}
      {children}
      {note && <p className="PH-note">{note}</p>}
    </section>
  );
}
