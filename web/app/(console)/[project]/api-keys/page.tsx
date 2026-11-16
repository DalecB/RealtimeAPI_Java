import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";

export default function ApiKeysPage() {
  return (
    <>
      <PageHeader title="API keys" />
      <Panel title="Keys">
        <p className="NeedsEndpoint">This page is not built yet. Keys are issued with POST /admin/api-keys.</p>
      </Panel>
    </>
  );
}
