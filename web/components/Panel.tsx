import { useId, type ReactNode } from "react";

// The only surface container. `flush` puts a table to the edges; otherwise children go in Panel-body.
export function Panel({
  title,
  meta,
  foot,
  flush,
  stack,
  id,
  className,
  children,
}: {
  title: string;
  meta?: ReactNode;
  foot?: ReactNode;
  flush?: boolean;
  stack?: boolean;
  id?: string;
  className?: string;
  children: ReactNode;
}) {
  const titleId = useId();
  return (
    <section className={className ? `Panel ${className}` : "Panel"} id={id} aria-labelledby={titleId}>
      <div className="Panel-head">
        <h2 className="Panel-title" id={titleId}>
          {title}
        </h2>
        {meta && <div className="Panel-meta">{meta}</div>}
      </div>
      {flush ? children : <div className={stack ? "Panel-body is-stack" : "Panel-body"}>{children}</div>}
      {foot && <div className="Panel-foot">{foot}</div>}
    </section>
  );
}
