"use client";

import { useEffect, useRef, type ReactNode } from "react";
import { Button } from "./Button";

// Only for actions that change production data or delete something. Names exactly what will change and
// offers the safe path first. A native modal <dialog>: focus stays inside and Escape cancels.
export function ConfirmDialog({
  open,
  title,
  children,
  confirmLabel,
  danger,
  confirmDisabled,
  safeAction,
  onConfirm,
  onCancel,
}: {
  open: boolean;
  title: string;
  children: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  confirmDisabled?: boolean;
  safeAction?: { label: string; href: string };
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  }, [open]);
  return (
    <dialog ref={ref} className="ConfirmDialog" aria-labelledby="confirm-title" onClose={onCancel}>
      <div className="ConfirmDialog-head" id="confirm-title">
        {title}
      </div>
      <div className="ConfirmDialog-body">{children}</div>
      <div className="ConfirmDialog-actions">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button variant={danger ? "danger" : "default"} onClick={onConfirm} disabled={confirmDisabled}>
          {confirmLabel}
        </Button>
        {safeAction && (
          <Button variant="primary" href={safeAction.href}>
            {safeAction.label}
          </Button>
        )}
      </div>
    </dialog>
  );
}
