import Link from "next/link";
import { Fragment } from "react";
import type { Hop, Status } from "@/lib/pipeline";
import { fmtUtcTime } from "@/lib/format";
import { Glyph } from "./Glyph";
import { StaleValue } from "./StaleValue";

// Signature element, always visible in the console layout: the write path and the audit path as one rail.
// Each node shows the same summary as its hop's StatusBadge on /delivery and links to that hop's anchor.
// The rail is dashed on either side of a failing node.
export function PipelineStrip({ hops, dlt, deliveryHref }: { hops: Hop[]; dlt: { status: Status; summary: string }; deliveryHref: string }) {
  const blocked = (a?: Status, b?: Status) => a === "fail" || b === "fail";
  return (
    <nav className="PipelineStrip" aria-label="Pipeline status">
      <span className="PS-origin">POST /events</span>
      <i className={blocked(undefined, hops[0]?.status) ? "PS-link is-blocked" : "PS-link"} aria-hidden="true" />
      {hops.map((h, i) => (
        <Fragment key={h.key}>
          <Node hop={h} href={`${deliveryHref}#${h.key}`} />
          {i < hops.length - 1 && <i className={blocked(h.status, hops[i + 1].status) ? "PS-link is-blocked" : "PS-link"} aria-hidden="true" />}
        </Fragment>
      ))}
      <Link className={`PS-node is-aside ${dlt.status === "warn" || dlt.status === "fail" ? `is-${dlt.status}` : ""}`.trim()} href={`${deliveryHref}#dlt`} aria-label={`DLT: ${dlt.summary}`}>
        <Glyph status={dlt.status} />
        <span className="PS-name">DLT</span>
        <span className="PS-val">{dlt.summary}</span>
      </Link>
    </nav>
  );
}

function Node({ hop, href }: { hop: Hop; href: string }) {
  const cls = ["PS-node", (hop.status === "warn" || hop.status === "fail") && `is-${hop.status}`].filter(Boolean).join(" ");
  const label = hop.stale ? `${hop.name}: ${hop.summary}. Last known at ${fmtUtcTime(hop.stale.asOf)}: ${hop.stale.value}` : `${hop.name}: ${hop.summary}`;
  return (
    <Link className={cls} href={href} aria-label={label}>
      <Glyph status={hop.status} />
      <span className="PS-name">{hop.name}</span>
      <span className="PS-val" aria-hidden="true">
        {hop.compact ? (
          <>
            <span className="PS-full">{hop.summary}</span>
            <span className="PS-compact">{hop.compact}</span>
          </>
        ) : (
          hop.summary
        )}
        {hop.stale && <StaleValue value={hop.stale.value} asOf={hop.stale.asOf} />}
      </span>
    </Link>
  );
}
