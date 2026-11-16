"use client";

import { useState } from "react";
import { Button } from "@/components/Button";
import { ConfirmDialog } from "@/components/ConfirmDialog";
import { KeyValueList } from "@/components/KeyValueList";
import { OutcomeTag } from "@/components/OutcomeTag";
import { TextField } from "@/components/TextField";
import { fireEvent, type FireResult, type Leaderboard } from "@/lib/api";
import { fmtInt } from "@/lib/format";

// "Send test event…" on a live leaderboard. POST /events needs the project's API key, which the console
// never stores, so the dialog asks for it each time.
export function TestEvent({ project, board, participants }: { project: string; board: Leaderboard; participants?: number }) {
  const [open, setOpen] = useState(false);
  const [apiKey, setApiKey] = useState("");
  const [userId, setUserId] = useState("");
  const [delta, setDelta] = useState("10");
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<FireResult>();
  const deltaScore = Number(delta);
  const valid = apiKey !== "" && /^[1-9]\d*$/.test(userId) && Number.isInteger(deltaScore) && deltaScore > 0;

  const send = async () => {
    setSending(true);
    setResult(await fireEvent({ apiKey, leaderboardId: board.id, userId, deltaScore }));
    setSending(false);
    setOpen(false);
  };

  return (
    <>
      {result && (
        <span className="Inline is-center" aria-live="polite">
          <span className="muted">last test event</span>
          <OutcomeTag outcome={result.outcome} />
          <span className="mono muted">HTTP {result.status || "no response"}</span>
        </span>
      )}
      <Button onClick={() => setOpen(true)}>Send test event…</Button>
      <ConfirmDialog
        open={open}
        title="Send a test event to a live leaderboard?"
        confirmLabel={sending ? "Sending…" : "Send to live leaderboard"}
        danger
        confirmDisabled={!valid || sending}
        safeAction={{ label: "Send in Sandbox instead", href: `/${project}/sandbox` }}
        onConfirm={send}
        onCancel={() => setOpen(false)}
      >
        <KeyValueList
          rows={[
            { label: "Leaderboard", value: board.name },
            { label: "userId", value: userId || "—" },
            { label: "deltaScore", value: Number.isFinite(deltaScore) && deltaScore > 0 ? `+${fmtInt(deltaScore)}` : "—" },
          ]}
        />
        <TextField label="API key" type="password" autoComplete="off" value={apiKey} onChange={(e) => setApiKey(e.target.value)} hint="POST /events takes the project's API key. The console does not keep it." />
        <TextField label="User ID" inputMode="numeric" value={userId} onChange={(e) => setUserId(e.target.value)} hint="A positive integer." />
        <TextField label="Delta score" inputMode="numeric" value={delta} onChange={(e) => setDelta(e.target.value)} />
        <p>
          This changes the live ranking that {participants === undefined ? "its" : fmtInt(participants)} participants see. Scores only go up, so it cannot be undone.
        </p>
      </ConfirmDialog>
    </>
  );
}
