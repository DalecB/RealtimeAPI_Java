import type { Role } from "@/lib/session";

export function RoleChip({ role }: { role: Role }) {
  return <span className={role === "operator" ? "RoleChip is-operator" : "RoleChip"}>{role === "operator" ? "Operator" : "Tenant"}</span>;
}
