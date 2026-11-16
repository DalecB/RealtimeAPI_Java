"use client";

import { useParams, useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/Button";
import { PageHeader } from "@/components/PageHeader";
import { Panel } from "@/components/Panel";
import { TextField } from "@/components/TextField";
import { createLeaderboard } from "@/lib/api";
import { useNow } from "@/lib/hooks";
import { LeaderboardsPanel, useBoards } from "../LeaderboardsPanel";

export default function LeaderboardsPage() {
  const { project } = useParams<{ project: string }>();
  const router = useRouter();
  const now = useNow();
  const boards = useBoards(project);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const create = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const name = String(new FormData(e.currentTarget).get("name")).trim();
    setBusy(true);
    setError(undefined);
    try {
      const board = await createLeaderboard(project, name);
      router.push(`/${project}/leaderboards/${board.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create the leaderboard.");
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Leaderboards" />
      <LeaderboardsPanel projectId={project} rows={boards} now={now} />
      <Panel title="New leaderboard" id="new" meta={<span className="mono">POST /leaderboards</span>}>
        <form className="Inline" onSubmit={create}>
          <TextField className="is-field" label="Name" name="name" required maxLength={255} error={error} />
          <Button variant="primary" type="submit" disabled={busy}>
            Create leaderboard
          </Button>
        </form>
      </Panel>
    </>
  );
}
