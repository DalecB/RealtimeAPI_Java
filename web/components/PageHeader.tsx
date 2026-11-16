import type { ReactNode } from "react";

export function PageHeader({ title, meta, actions }: { title: string; meta?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="PageHeader">
      <div>
        <h1 className="PageHeader-title">{title}</h1>
        {meta && <div className="PageHeader-meta">{meta}</div>}
      </div>
      {actions && <div className="PageHeader-actions">{actions}</div>}
    </div>
  );
}
