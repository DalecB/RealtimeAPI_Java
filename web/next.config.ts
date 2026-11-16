import type { NextConfig } from "next";

// The browser only talks to this Next server; these paths are forwarded to the Spring app so calls stay
// same-origin (no CORS). In compose, set API_ORIGIN=http://app:8080.
// ponytail: rewrites are resolved by `next build`, so API_ORIGIN must be set at build time (a Docker
// build arg), not only when the server starts.
const API_ORIGIN = process.env.API_ORIGIN ?? "http://localhost:8080";

const BACKEND_PATHS = ["auth", "internal", "projects", "leaderboards", "events", "users", "admin", "actuator", "swagger-ui", "v3"];

const nextConfig: NextConfig = {
  async rewrites() {
    return {
      // beforeFiles: /leaderboards/... must reach the backend before the [project] route can match it.
      beforeFiles: BACKEND_PATHS.flatMap((p) => [
        { source: `/${p}`, destination: `${API_ORIGIN}/${p}` },
        { source: `/${p}/:path*`, destination: `${API_ORIGIN}/${p}/:path*` },
      ]),
      afterFiles: [],
      fallback: [],
    };
  },
};

export default nextConfig;
