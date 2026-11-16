import type { CSSProperties } from "react";

// Separates the cause from what it drags down, so several red hops do not read as several problems.
export function HopGroups({ cause, effects }: { cause: { label: string; span: number }; effects: { label: string; span: number } }) {
  const style = { "--hops": cause.span + effects.span, "--span": effects.span } as CSSProperties;
  return (
    <div className="HopGroups" style={style}>
      <div className="HopGroup" style={{ gridColumn: `span ${cause.span}` }}>
        Cause<span>{cause.label}</span>
      </div>
      <div className="HopGroup is-effects">
        Downstream effects<span>{effects.label}</span>
      </div>
    </div>
  );
}
