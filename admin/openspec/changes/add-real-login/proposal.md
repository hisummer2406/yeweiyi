## Why

Neither `/ops/*` nor `/console/*` can actually be reached today: the login page
(`src/pages/user/login/index.tsx`) still posts to the Ant Design Pro template's
fake `POST /api/login/account`, and `src/app.tsx`'s `getInitialState` still
calls the template's fake `GET /api/currentUser`. Neither route exists on the
real `yeweiyi-cloud` backend — both 404 (confirmed against a locally running
backend: `POST /api/login/account` → 404, `GET /api/currentUser` → 404, while
the real `POST /api/login` → 200 `{token}`). Every login attempt with any real
account fails, and `getInitialState` silently bounces every non-login route
back to `/user/login`. This was discovered while manually verifying
`add-console-devices-tank-grid`: no account could reach `/console/devices` in
a browser at all, for a reason unrelated to that change's own code.

## What Changes

- Replace the login page's request with the real `POST /api/login`
  (`{username, password}` → `{token}` on success, `{error}` on 4xx) instead of
  the template's `login()`/`API.LoginParams`/`API.LoginResult`.
- Store the returned JWT (`localStorage`) and add a request interceptor that
  attaches it as `Authorization: Bearer <token>` on every `/api/*` request —
  replacing the commented-out placeholder in `src/requestErrorConfig.ts`.
- Replace `getInitialState`'s `queryCurrentUser()` call: the real backend has
  no current-user endpoint (`internal/api/router.go` has no such route; user
  identity travels entirely inside the JWT). Decode the stored token's payload
  (`{uid, role, sub, exp, iat}` per `yeweiyi-cloud`'s `apiClaims`) client-side
  and shape it into `API.CurrentUser` with an `access` field carrying `role`,
  so `src/access.ts` (`canPlatformAdmin`/`canConsole`) keeps working unchanged.
  Treat a missing, malformed, or expired (`exp` past) token as "no current
  user" — not a thrown error.
- On any `/api/*` response with `401`, clear the stored token and redirect to
  `/user/login` (session expired or invalid — the JWT has no refresh
  mechanism; the token's own `exp`, 24h by default, is the only expiry).
- Wire logout to clear the stored token (in addition to whatever the template
  logout flow already does) so a stale token can't outlive an explicit
  sign-out.
- **BREAKING**: `API.LoginParams`/`API.LoginResult`/`API.CurrentUser` in
  `src/services/ant-design-pro/typings.d.ts` change shape to match the real
  backend; anything still relying on the old template shape (there is no such
  code today outside login/app.tsx) would need to follow.

**Not in scope**: user/account management UI, account creation (all accounts
are created by a `platform_admin` via `POST /api/admin/users`, out of scope
here), changing `src/access.ts`'s role-check logic itself, token refresh (the
backend issues none), or any `/ops/*`/`/console/*` page content.

## Capabilities

### New Capabilities
- `auth/session`: real login request/response handling, JWT storage and
  attachment to outgoing requests, deriving the current user from the JWT
  (since the backend exposes none), session-expiry (401) handling, and logout.

### Modified Capabilities
(none — `src/access.ts`'s role-check behavior is unchanged; only the data
feeding it changes from fake to real)

## Impact

- **Modified files**: `src/pages/user/login/index.tsx`, `src/app.tsx`
  (`getInitialState`), `src/requestErrorConfig.ts` (token-attach interceptor,
  401 handling), `src/services/ant-design-pro/typings.d.ts`
  (`LoginParams`/`LoginResult`/`CurrentUser`), `src/services/ant-design-pro/api.ts`
  (`login`/`currentUser`/`outLogin` request functions).
- **New files**: none anticipated beyond a small JWT-decode helper (exact
  location left to design.md).
- **Backend dependency**: read-only against existing `yeweiyi-cloud` routes
  (`POST /api/login`); no backend changes required. Depends on the JWT shape
  documented in `internal/api/auth.go`'s `apiClaims` staying `{uid, role,
  sub, exp, iat}` — a backend claims change would need to be reflected here.
- **Cross-cutting**: unblocks manual browser verification of both `/ops/*`
  and `/console/*` for every future change — today neither can be reached by
  any account.
