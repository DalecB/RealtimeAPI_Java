// First tab stop on the page: an overlay at the top left that jumps to #content and moves nothing.
export function SkipLink({ href = "#content" }: { href?: string }) {
  return (
    <a className="SkipLink" href={href}>
      Skip to content
    </a>
  );
}
