export function EnvTag({ env }: { env: "prod" | "sandbox" }) {
  return <span className={env === "prod" ? "EnvTag is-prod" : "EnvTag"}>{env}</span>;
}
