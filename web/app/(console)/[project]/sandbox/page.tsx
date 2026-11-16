import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";

export default function SandboxPage() {
  return (
    <>
      <PageHeader title="Sandbox" />
      <Panel title="Test events">
        <p className="NeedsEndpoint">The sandbox is not built yet. A test event can be sent from a leaderboard&apos;s Live tab.</p>
      </Panel>
    </>
  );
}
