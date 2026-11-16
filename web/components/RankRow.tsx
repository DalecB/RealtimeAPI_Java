import type { TopRankItem } from "@/lib/api";
import { fmtInt } from "@/lib/format";

export const RANK_COLUMNS = [
  { key: "rank", header: "Rank" },
  { key: "user", header: "User" },
  { key: "live", header: "Score · Redis", align: "right" as const },
  { key: "snap", header: "Score · snapshot", align: "right" as const },
  { key: "since", header: "Since snapshot", align: "right" as const },
  { key: "snaprank", header: "Snapshot rank", align: "right" as const },
];

// One ranked user. Live score is Redis; snapshot is PostgreSQL. snapshot undefined: no snapshot to
// compare with; null: the user is not in the snapshot's top N.
export function RankRow({
  item,
  snapshot,
  tied,
  highlighted,
}: {
  item: { rank: number | null; userId: string; score: number };
  snapshot?: TopRankItem | null;
  tied: boolean;
  highlighted?: boolean;
}) {
  const unranked = item.rank === null;
  const cls = ["RankRow", highlighted && "is-selected", unranked && "is-unranked"].filter(Boolean).join(" ");
  const delta = snapshot ? item.score - snapshot.score : null;
  const none = snapshot === undefined ? "no snapshot" : "not in snapshot";
  return (
    <tr className={cls}>
      <td className="RankRow-rank">
        {unranked ? "unranked" : item.rank}
        {tied && <span className="RankRow-tie">TIE</span>}
      </td>
      <td className="RankRow-user mono">{item.userId}</td>
      <td className="num">{fmtInt(item.score)}</td>
      <td className={snapshot ? "num" : "num muted"}>{snapshot ? fmtInt(snapshot.score) : none}</td>
      <td className={delta === null ? "num muted" : delta > 0 ? "num is-up" : "num is-zero"}>
        {delta === null ? "—" : delta > 0 ? `+${fmtInt(delta)}` : fmtInt(delta)}
      </td>
      <td className={snapshot ? "num" : "num muted"}>{snapshot ? snapshot.rank : "—"}</td>
    </tr>
  );
}
