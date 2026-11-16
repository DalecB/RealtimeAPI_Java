import { NeedsEndpoint } from "@/components/NeedsEndpoint";
import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { MISSING } from "@/lib/api";

export default function IncidentsPage() {
  return (
    <>
      <PageHeader title="Incidents" />
      <Panel title="Open incidents">
        <NeedsEndpoint what="Incident list" endpoint={MISSING.incidents} />
        <p className="NeedsEndpoint">This page is not built yet. A failing hop shows on Delivery as it happens.</p>
      </Panel>
    </>
  );
}
