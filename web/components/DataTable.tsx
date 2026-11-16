import { Fragment, type ReactNode } from "react";

export interface Column {
  key: string;
  header: string;
  align?: "left" | "right";
  srOnly?: boolean; // header read by screen readers only (an actions column)
}

// The default container for anything countable: 32 px head, 36 px rows, numbers right-aligned in mono.
export function DataTable<T>({
  columns,
  rows,
  rowKey,
  renderRow,
  empty,
  fixed,
  divided,
}: {
  columns: Column[];
  rows: T[];
  rowKey: (row: T) => string;
  renderRow: (row: T) => ReactNode; // a <tr>
  empty: ReactNode;
  fixed?: boolean;
  divided?: boolean; // a rule above, when the table follows a Panel-body
}) {
  return (
    <div className={divided ? "Panel-scroll is-divided" : "Panel-scroll"}>
      <table className={fixed ? "DataTable is-fixed" : "DataTable"}>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} className={c.align === "right" ? "num" : undefined} scope="col">
                {c.srOnly ? <span className="sr">{c.header}</span> : c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td className="DataTable-empty" colSpan={columns.length}>
                {empty}
              </td>
            </tr>
          ) : (
            rows.map((r) => <Fragment key={rowKey(r)}>{renderRow(r)}</Fragment>)
          )}
        </tbody>
      </table>
    </div>
  );
}
