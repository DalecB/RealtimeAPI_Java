import { ConsoleFrame } from "./ConsoleFrame";

// AppShell (SidebarNav, TopBar, SkipLink) and the PipelineStrip around every console page.
export default function ConsoleLayout({ children }: { children: React.ReactNode }) {
  return <ConsoleFrame>{children}</ConsoleFrame>;
}
