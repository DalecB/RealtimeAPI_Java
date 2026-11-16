import { fmtInt } from "@/lib/format";
import { StaleValue } from "./StaleValue";
import { StatusBadge } from "./StatusBadge";

// The one answer at the top of /delivery: everything published is stored, quarantined, or still in flight.
// Green only when every term is live; during a Redis outage the result is neutral and unacked is stale.
export function Reconcile({
  published,
  stored,
  quarantined,
  inFlight,
  live,
  unacked,
}: {
  published: number; // AuditTopicStatus.totalMessages
  stored: number; // audit-events/count
  quarantined: number; // AuditTopicStatus.dltTotalMessages
  inFlight: number; // AuditTopicStatus.consumerLag: published, not yet consumed
  live: boolean;
  unacked?: { value: number; asOf: number };
}) {
  const unaccounted = Math.abs(published - stored - quarantined - inFlight);
  const terms = [`${fmtInt(published)} published`, `${fmtInt(stored)} stored`, `${fmtInt(quarantined)} in the dead-letter topic`];
  if (inFlight > 0) terms.push(`${fmtInt(inFlight)} in flight`);
  const label = `Reconciliation: ${terms[0]} equals ${terms.slice(1).join(" plus ")}. ${fmtInt(unaccounted)} unaccounted.`;
  return (
    <div className="Reconcile" role="group" aria-label={label}>
      <span>
        {fmtInt(published)}
        <small>published</small>
      </span>
      <span className="Reconcile-op">=</span>
      <span>
        {fmtInt(stored)}
        <small>stored</small>
      </span>
      <span className="Reconcile-op">+</span>
      <span>
        {fmtInt(quarantined)}
        <small>in DLT</small>
      </span>
      {inFlight > 0 && (
        <>
          <span className="Reconcile-op">+</span>
          <span>
            {fmtInt(inFlight)}
            <small>in flight</small>
          </span>
        </>
      )}
      <span className="Reconcile-result">
        {!live ? (
          <StatusBadge label={`${fmtInt(unaccounted)} unaccounted between Kafka and PostgreSQL`} />
        ) : unaccounted === 0 ? (
          <StatusBadge status="ok" label="0 unaccounted" />
        ) : (
          <StatusBadge status="fail" label={`${fmtInt(unaccounted)} unaccounted`} />
        )}
      </span>
      {unacked && (
        <span className="Reconcile-extra">
          <StaleValue value={fmtInt(unacked.value)} asOf={unacked.asOf} tone="warn" /> unacked in Redis. They are already in Kafka and are re-sent on recovery; duplicates are ignored.
        </span>
      )}
    </div>
  );
}
