# web — RealtimeAPI console (Next.js)

The operator console built from the approved design in `design-reference/project/`
(`Main.dc.html` is the component sheet, `console.css` the source of `styles/`).

```bash
npm install
API_ORIGIN=http://localhost:8080 npm run dev   # default origin; compose: http://app:8080
```

The browser calls this server only; `next.config.ts` rewrites backend paths to `API_ORIGIN`.
Rewrites are resolved at build time, so pass `API_ORIGIN` to `next build` as well.
Sign in with the seeded admin (local default `admin` / `local-admin-password`).
