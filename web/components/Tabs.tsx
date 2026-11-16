import Link from "next/link";

export function Tabs({ items, active, label }: { items: Array<{ label: string; href: string }>; active: string; label: string }) {
  return (
    <nav className="Tabs" aria-label={label}>
      {items.map((t) => (
        <Link key={t.href} href={t.href} className={t.label === active ? "is-active" : undefined} aria-current={t.label === active ? "page" : undefined}>
          {t.label}
        </Link>
      ))}
    </nav>
  );
}
