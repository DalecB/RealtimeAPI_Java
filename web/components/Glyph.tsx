import type { Status } from "@/lib/pipeline";

// Status shape: circle ok, triangle warn, square fail, ring idle. Always paired with a text label.
export function Glyph({ status }: { status: Status }) {
  return <i className={`glyph is-${status}`} aria-hidden="true" />;
}
