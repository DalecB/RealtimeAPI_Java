import Link from "next/link";

export interface NavItem {
  label: string;
  href: string;
  count?: number;
}

// Text-only navigation; the active item is a fill and carries aria-current.
// Documentation lives only in the sidebar foot.
export function SidebarNav({
  project,
  nav,
  build,
  activeHref,
  signedInAs,
}: {
  project: { id: string; name: string | null };
  nav: NavItem[];
  build: NavItem[];
  activeHref: string | null;
  signedInAs: string;
}) {
  const item = (n: NavItem) => (
    <Link key={n.href} href={n.href} className={n.href === activeHref ? "SidebarNav-item is-active" : "SidebarNav-item"} aria-current={n.href === activeHref ? "page" : undefined}>
      {n.label}
      {n.count !== undefined && n.count > 0 && <span className="SidebarNav-count">{n.count}</span>}
    </Link>
  );
  return (
    <aside className="SidebarNav">
      <div className="SidebarNav-brand">
        <span className="SidebarNav-mark" aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
        RealtimeAPI
      </div>
      {/* ponytail: projects carry no environment in the backend, so no EnvTag here yet. */}
      <Link className="SidebarNav-project" href="/" aria-label={`Project ${project.name ?? project.id}. Switch project`}>
        <span style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{project.name ?? project.id}</span>
      </Link>
      <nav className="SidebarNav-group" aria-label="Project">
        {nav.map(item)}
      </nav>
      <nav className="SidebarNav-group" aria-label="Build">
        <div className="SidebarNav-label">Build</div>
        {build.map(item)}
      </nav>
      <div className="SidebarNav-foot">
        {/* Swagger UI is served by the backend through the rewrite. A new tab keeps this tab's in-memory session. */}
        <a href="/swagger-ui/index.html" target="_blank" rel="noreferrer">
          Documentation
        </a>
        <span>Signed in as {signedInAs}</span>
      </div>
    </aside>
  );
}
