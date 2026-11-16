import { fmtInt } from "@/lib/format";

// One Kafka partition: lag against a fixed scale, the number, and the instance that owns it.
// lag null: the partition is unassigned (rebalance in progress).
export function PartitionLagBar({ partition, lag, scaleMax, owner, status = "ok" }: { partition: number; lag: number | null; scaleMax: number; owner: string | null; status?: "ok" | "warn" | "fail" }) {
  return (
    <div className={status === "ok" ? "PartitionLagBar" : `PartitionLagBar is-${status}`}>
      <span className="PartitionLagBar-id">p{partition}</span>
      <span className="PartitionLagBar-track">
        <i className="PartitionLagBar-fill" style={{ width: `${lag === null ? 0 : Math.min(100, (lag / scaleMax) * 100)}%` }} />
      </span>
      <span className={lag === null ? "PartitionLagBar-lag muted" : "PartitionLagBar-lag"}>{lag === null ? "—" : fmtInt(lag)}</span>
      <span className="PartitionLagBar-owner">{owner ?? "none"}</span>
    </div>
  );
}
