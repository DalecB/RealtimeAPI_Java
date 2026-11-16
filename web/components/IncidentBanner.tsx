import type { ReactNode } from "react";
import { Glyph } from "./Glyph";

export interface Impact {
  writes: string;
  reads: string;
  audit: string;
}

// Top of the page content while an incident is open: what is failing, what callers receive, what is
// safe, whether someone has to act, and when the system tries again.
// role="alert" only on the render where the incident first opens (`announce`); every later poll
// updates inside aria-live="polite".
export function IncidentBanner({
  severity,
  title,
  body,
  impact,
  next,
  facts,
  actions,
  announce,
}: {
  severity: "fail" | "warn" | "ok";
  title: string;
  body?: ReactNode;
  impact?: Impact;
  next?: ReactNode;
  facts?: string[];
  actions?: ReactNode;
  announce?: boolean;
}) {
  return (
    <div className={severity === "fail" ? "IncidentBanner" : `IncidentBanner is-${severity}`} role={announce ? "alert" : undefined} aria-live={announce ? undefined : "polite"}>
      <div className="IncidentBanner-main">
        <Glyph status={severity} />
        <div>
          <div className="IncidentBanner-title">{title}</div>
          {body && <p className="IncidentBanner-body">{body}</p>}
          {impact && (
            <dl className="IncidentBanner-impact">
              <dt>Writes</dt>
              <dd>{impact.writes}</dd>
              <dt>Reads</dt>
              <dd>{impact.reads}</dd>
              <dt>Audit</dt>
              <dd>{impact.audit}</dd>
            </dl>
          )}
          {next}
          {facts && facts.length > 0 && (
            <div className="IncidentBanner-facts">
              {facts.map((f) => (
                <span key={f}>{f}</span>
              ))}
            </div>
          )}
        </div>
      </div>
      {actions && <div className="IncidentBanner-actions">{actions}</div>}
    </div>
  );
}
