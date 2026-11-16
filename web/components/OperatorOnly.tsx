"use client";

import type { ReactNode } from "react";
import { useSession } from "@/lib/hooks";

// A rule, not a look: the region is not rendered unless the session carries the operator role.
// ponytail: checked on the client because the JWT lives in client memory; once the session moves to
// an httpOnly cookie this becomes the server-side check the sheet asks for.
export function OperatorOnly({ children }: { children: ReactNode }) {
  const session = useSession();
  return session?.role === "operator" ? <>{children}</> : null;
}
