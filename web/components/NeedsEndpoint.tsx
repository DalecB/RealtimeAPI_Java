// The honest unavailable state of a designed component whose API does not exist yet.
export function NeedsEndpoint({ what, endpoint }: { what: string; endpoint: string }) {
  return (
    <p className="NeedsEndpoint">
      {what}: not available — needs <code>{endpoint}</code>
    </p>
  );
}
