## Context

See `proposal.md` - Why. Two template call sites are wired to endpoints that
don't exist on the real backend (`POST /api/login/account`,
`GET /api/currentUser`), and the request layer never attaches a token to
outgoing requests at all (`src/requestErrorConfig.ts` has the attach step
commented out as a placeholder). The real backend's contract, confirmed
against `~/Developer/yeweiyi-cloud`'s source:

- `POST /api/login` — `{username, password}` → `200 {token}` or `4xx {error}`.
- No current-user endpoint exists anywhere in `internal/api/router.go`.
- `authMiddleware` (`internal/api/auth.go`) only accepts
  `Authorization: Bearer <token>`; no cookie/session fallback.
- The JWT payload is `{uid: number, role: string, sub: string, exp: number,
  iat: number}` (`apiClaims`), default lifetime 24h
  (`defaultTokenTTL`), non-refreshable — a role change or expiry only takes
  effect on next login.
- `src/access.ts` already reads `initialState.currentUser.access` to compute
  `canPlatformAdmin`/`canConsole` — this design must keep populating that
  field, not change how it's consumed.

## Goals / Non-Goals

**Goals:**
- Make login work end-to-end against the real backend for all three roles.
- Keep every other page's access-control code (`src/access.ts`) untouched —
  only the data feeding `initialState.currentUser` changes.
- Make the 24h token expiry a clean, predictable redirect-to-login rather
  than a confusing string of failed requests.

**Non-Goals:**
- Token refresh — the backend issues none; re-login is the only path back
  from an expired token.
- Remembering/restoring the in-flight request that triggered a 401 — after
  re-login the user lands back on the same *page* (via the existing
  `redirect` query param pattern already used by `getInitialState`), not
  mid-request.
- Any UI for account creation/management (`POST /api/admin/users` is a
  separate, already-out-of-scope capability).

## Decisions

- **Decode the JWT client-side by hand, no new dependency**: the payload is
  a base64url-encoded JSON object in the token's middle segment
  (`token.split('.')[1]`); decoding it is `atob` (with base64url→base64
  padding fixup) + `JSON.parse`, about ten lines. A library like
  `jwt-decode` would do the same thing for a dependency that exists only to
  save those ten lines.
  - Alternative considered: add `jwt-decode` — rejected, unnecessary weight
    for a single call site with no verification requirement (the app never
    needs to *verify* the signature, only read the claims it already trusts
    because it just received them from `POST /api/login` over HTTPS).

- **`localStorage`, not a cookie, for the token**: the backend never sets a
  cookie (confirmed: `loginHandler` only ever returns JSON, no `Set-Cookie`),
  so a cookie-based approach would require a backend change out of scope
  here. `localStorage` also survives a page reload, which `sessionStorage`
  complicates for a multi-tab admin console with no strong reason to scope
  the session per-tab.

- **`initialState.currentUser` is synthesized from the token, not fetched**:
  `getInitialState` currently does `const currentUser = await
  fetchUserInfo()` where `fetchUserInfo` calls the (fake) endpoint. Replace
  that with a synchronous decode of whatever token is in `localStorage` at
  boot. This keeps `getInitialState`'s existing shape (still returns
  `{currentUser, fetchUserInfo, settings, ...}`) so `app.tsx`'s `layout`
  function and `access.ts` need no changes — only what feeds `currentUser`
  changes.
  - Alternative considered: keep an async `fetchUserInfo` that "fetches" by
    reading `localStorage` — kept async only insofar as the existing
    signature already returns a `Promise`; the body itself has no I/O once
    the token is decoded locally, so this is really the same decision as
    above, just satisfying the existing function signature without changing
    call sites in `login/index.tsx`.

- **401 handling lives in the shared `errorHandler`, not per-call-site**:
  `src/requestErrorConfig.ts`'s `errorHandler` already special-cases
  `error.response` (axios errors). Add a branch there: any `error.response
  .status === 401` clears the token and calls `history.replace` to
  `/user/login?redirect=<current path>`, matching the existing redirect
  pattern in `getInitialState`. This covers every `/api/*` call site (current
  and future) without each one needing its own 401 check — including the two
  read-only endpoints `add-console-devices-tank-grid` just added.
  - One exception: the login request itself returning 401 (bad credentials)
    must NOT trigger this redirect loop (it's already on `/user/login`, and
    “invalid credentials” is not “session expired”). `login/index.tsx`
    already calls `login()` with default error handling and shows its own
    failure message in a `catch` — pass `skipErrorHandler: true` on the
    login call specifically so the shared 401 handler never sees it, mirroring
    how `getLatestTelemetry` already opts out for its own expected-404 case.

- **Token attach interceptor reads `localStorage` per-request, not from a
  cached variable**: uncomment and complete the existing placeholder in
  `requestInterceptors`, reading `localStorage.getItem('token')` fresh on
  every request. A cached in-memory copy would go stale the moment logout or
  a 401-triggered clear runs without a matching cache invalidation; reading
  storage directly has no such class of bug.

## Risks / Trade-offs

- [`localStorage` is readable by any script on the page] → same exposure
  profile as every other Ant Design Pro deployment using this pattern;
  no XSS-specific mitigation is in scope for this change. Acceptable because
  introducing e.g. httpOnly-cookie-based auth is a backend change, out of
  scope here.
- [Decoding a JWT without verifying its signature client-side] → this is
  standard practice for a token the client itself just received from a
  trusted HTTPS endpoint moments ago; the backend is the only party that
  needs to verify the signature (and does, on every request via
  `authMiddleware`). A tampered `localStorage` value would just fail the next
  real request's signature check server-side (401 → redirect to login), not
  grant any elevated access.
- [24h expiry lands mid-session with no warning] → out of scope to add a
  refresh/warning mechanism (Non-Goals); the 401 handler makes the failure
  mode a clean redirect rather than a silent stuck page, which is the
  concrete problem this change is fixing.

## Open Questions

- Whether to eventually add a "session expiring soon" warning before the 24h
  cutoff is a product decision, not something this change should preempt.
