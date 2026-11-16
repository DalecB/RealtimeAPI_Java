"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/Button";
import { Panel } from "@/components/Panel";
import { TextField } from "@/components/TextField";
import { ApiError, login } from "@/lib/api";
import { setSession } from "@/lib/session";

// Admin login. The JWT stays in memory (lib/session.ts), so the console navigates client-side from here.
export default function LoginPage() {
  const router = useRouter();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = new FormData(e.currentTarget);
    setBusy(true);
    setError(undefined);
    try {
      setSession(await login(String(form.get("username")), String(form.get("password"))));
      const next = new URLSearchParams(window.location.search).get("next");
      router.replace(next?.startsWith("/") && !next.startsWith("//") ? next : "/");
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? "Username or password is wrong." : `Sign-in failed: ${err instanceof Error ? err.message : "unknown error"}`);
      setBusy(false);
    }
  };

  return (
    <main className="LoginPage" id="content">
      <Panel title="Sign in to RealtimeAPI" meta={<span className="mono">POST /auth/login</span>}>
        <form onSubmit={submit}>
          <TextField label="Username" name="username" autoComplete="username" required defaultValue="" />
          <TextField label="Password" name="password" type="password" autoComplete="current-password" required error={error} />
          <Button variant="primary" type="submit" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </Button>
        </form>
      </Panel>
    </main>
  );
}
