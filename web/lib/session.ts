// Operator = an admin JWT, the same token /internal/** requires. The backend has no tenant login yet;
// the role is here so OperatorOnly and RoleChip key off the session instead of a constant.
export type Role = "operator" | "tenant";

export interface Session {
  token: string;
  role: Role;
  externalId: string;
  expiresAt: string;
}

// ponytail: the admin JWT lives in module memory, same as the old console, so a reload or a typed URL
// signs you out. Upgrade path: a route handler that sets an httpOnly cookie on login and lets the
// rewrite (or a server fetch) attach Authorization, so the token never reaches client JS.
let current: Session | null = null;
const listeners = new Set<() => void>();

function emit() {
  for (const l of listeners) l();
}

export function setSession(next: Session) {
  current = next;
  emit();
}

export function clearSession() {
  if (current === null) return;
  current = null;
  emit();
}

export function getToken(): string | null {
  return current?.token ?? null;
}

export const getSession = () => current;

export function subscribeSession(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
