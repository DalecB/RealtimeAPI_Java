import { useId, type InputHTMLAttributes } from "react";

// Input is mono because it takes IDs.
export function TextField({
  label,
  hint,
  error,
  className,
  ...input
}: { label: string; hint?: string; error?: string; className?: string } & Omit<InputHTMLAttributes<HTMLInputElement>, "className">) {
  const id = useId();
  const hintId = `${id}-hint`;
  const message = error ?? hint;
  return (
    <div className={["TextField", error && "is-error", className].filter(Boolean).join(" ")}>
      <label htmlFor={id}>{label}</label>
      <input id={id} aria-invalid={error ? true : undefined} aria-describedby={message ? hintId : undefined} {...input} />
      {message && (
        <span className="TextField-hint" id={hintId}>
          {message}
        </span>
      )}
    </div>
  );
}
