import Link from "next/link";
import type { ButtonHTMLAttributes, ReactNode } from "react";

type Variant = "default" | "primary" | "ghost" | "danger";

interface Props extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "className"> {
  variant?: Variant;
  size?: "md" | "sm";
  href?: string; // renders a link styled as a button
  children: ReactNode;
}

// 32 px control; one primary per page at most.
export function Button({ variant = "default", size = "md", href, children, type = "button", ...rest }: Props) {
  const cls = ["Button", variant !== "default" && `is-${variant}`, size === "sm" && "is-small"].filter(Boolean).join(" ");
  if (href) {
    return (
      <Link className={cls} href={href}>
        {children}
      </Link>
    );
  }
  return (
    <button className={cls} type={type} {...rest}>
      {children}
    </button>
  );
}
