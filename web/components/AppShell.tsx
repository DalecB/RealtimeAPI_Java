import type { ReactNode } from "react";
import { SkipLink } from "./SkipLink";

// SkipLink first, then SidebarNav, and the main column: TopBar, PipelineStrip, page content (#content).
export function AppShell({ sidebar, topBar, strip, banner, children }: { sidebar: ReactNode; topBar: ReactNode; strip: ReactNode; banner?: ReactNode; children: ReactNode }) {
  return (
    <>
      <SkipLink />
      <div className="AppShell">
        {sidebar}
        <div className="AppShell-main">
          {topBar}
          {strip}
          <main className="AppShell-page" id="content">
            {banner}
            {children}
          </main>
        </div>
      </div>
    </>
  );
}
