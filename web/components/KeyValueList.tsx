import type { ReactNode } from "react";

export interface KeyValueRow {
  label: string;
  field?: string; // API field name, shown when "Show API fields" is on
  value: ReactNode;
  tone?: "ok" | "warn" | "fail" | "muted";
  strong?: boolean;
  note?: string; // second line under the value
}

export function KeyValueList({ rows, narrow }: { rows: KeyValueRow[]; narrow?: boolean }) {
  return (
    <dl className={narrow ? "KeyValueList is-narrow" : "KeyValueList"}>
      {rows.map((r) => (
        <Pair key={r.label} row={r} />
      ))}
    </dl>
  );
}

function Pair({ row }: { row: KeyValueRow }) {
  const cls = [row.strong && "is-strong", row.tone && (row.tone === "muted" ? "muted" : `t-${row.tone}`)].filter(Boolean).join(" ");
  return (
    <>
      <dt>
        {row.label}
        {row.field && <code>{row.field}</code>}
      </dt>
      <dd className={cls || undefined}>
        {row.value}
        {row.note && <small>{row.note}</small>}
      </dd>
    </>
  );
}
