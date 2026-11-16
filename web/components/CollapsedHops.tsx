import type { ReactNode } from "react";
import type { Status } from "@/lib/pipeline";
import { Glyph } from "./Glyph";

// During an incident the hops that are fine fold into one row under the failing ones, keeping their
// number, name and strip value. A native details element, so it opens with Enter or Space.
export function CollapsedHops({
  id,
  title,
  hops,
  defaultOpen,
  children,
}: {
  id?: string;
  title: string;
  hops: Array<{ step: number; title: string; summary: string; status: Status }>;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  return (
    <details className="CollapsedHops" id={id} open={defaultOpen}>
      <summary>
        <span className="CollapsedHops-title">{title}</span>
        {hops.map((h) => (
          <span className="CollapsedHops-item" key={h.step}>
            <Glyph status={h.status} />
            <b>
              {String(h.step).padStart(2, "0")} {h.title}
            </b>
            <span>{h.summary}</span>
          </span>
        ))}
        <span className="CollapsedHops-toggle">
          <span className="when-closed">Show details</span>
          <span className="when-open">Hide details</span>
        </span>
      </summary>
      {children}
    </details>
  );
}
