"use client";

import { useState } from "react";

// 24 by 24, icon only, named by aria-label; confirms in place for two seconds.
// With nothing to copy the caller renders text instead (the sheet's "no ID" state).
export function CopyButton({ value, label, disabled }: { value: string; label: string; disabled?: boolean }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    await navigator.clipboard.writeText(value);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <button className={copied ? "CopyButton is-copied" : "CopyButton"} type="button" aria-label={copied ? `${label}: copied` : label} disabled={disabled} onClick={copy}>
      {copied ? (
        "Copied"
      ) : (
        <svg viewBox="0 0 12 12" aria-hidden="true">
          <rect x="4" y="4" width="6.5" height="6.5" rx="1" />
          <path d="M8 4V2.5a1 1 0 0 0-1-1H2.5a1 1 0 0 0-1 1V7a1 1 0 0 0 1 1H4" />
        </svg>
      )}
    </button>
  );
}

export function IdCell({ id, label, muted }: { id: string; label: string; muted?: boolean }) {
  return (
    <span className="IdCell">
      <span className={muted ? "mono muted" : "mono"}>{id}</span>
      <CopyButton value={id} label={label} />
    </span>
  );
}
