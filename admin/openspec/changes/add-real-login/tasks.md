## 1. Types and token helpers

- [x] 1.1 In `src/services/ant-design-pro/typings.d.ts`, replace `API.LoginParams` with
      `{ username: string; password: string }` and `API.LoginResult` with
      `{ token: string } | { error: string }` (or split into a success/failure union),
      matching `yeweiyi-cloud`'s `POST /api/login`; update `API.CurrentUser` to
      `{ access: string; name: string }` at minimum (`access` = JWT `role`, `name` = JWT
      `sub`), keeping any other template fields optional so `src/access.ts` and existing
      layout code (`initialState?.currentUser?.avatar` etc. in `app.tsx`) still type-check.
      Verify with `npm run tsc`.
- [x] 1.2 Add a small token helper module (e.g. `src/utils/auth.ts`) with `getToken()`,
      `setToken(token: string)`, `clearToken()` (all backed by `localStorage`), and
      `decodeToken(token: string): { uid: number; role: string; sub: string; exp: number;
      iat: number } | null` that base64url-decodes the JWT payload segment and returns
      `null` on any parse failure. Verify with a unit test covering: a well-formed token
      decodes correctly, a malformed token returns `null` (no throw).
- [x] 1.3 Add `isTokenExpired(claims: { exp: number }): boolean` (compares `exp` in seconds
      against `Date.now()`) next to `decodeToken`. Verify with a unit test for both a
      past and a future `exp`.

## 2. Real login request

- [x] 2.1 In `src/services/ant-design-pro/api.ts`, change `login()` to `POST /api/login`
      with `{ username, password }` and the updated `API.LoginResult` return type; remove
      the template's `type`/`autoLogin` fields from the request body (the real backend
      ignores them). Verify with `npm run tsc`.
- [x] 2.2 In `src/pages/user/login/index.tsx`'s `handleSubmit`, replace the
      `msg.status === 'ok'` success check with `'token' in msg`; on success call
      `setToken(msg.token)` before the existing `fetchUserInfo()`/redirect logic; on
      failure (no `token` field), keep showing the existing login-failure message. Pass
      `skipErrorHandler: true` on the `login()` call so a 401 (bad credentials) doesn't
      trip the shared 401-redirect handler being added in task 4.1. Verify by running
      `npm run dev` and confirming the network tab shows `POST /api/login` (not
      `/api/login/account`).

## 3. Current user from JWT, not a fetch

- [x] 3.1 In `src/app.tsx`'s `getInitialState`, replace the `queryCurrentUser()` call in
      `fetchUserInfo` with: read the stored token via `getToken()`; if absent, return
      `undefined`; otherwise `decodeToken()` it, and if decoding fails or
      `isTokenExpired()` is true, `clearToken()` and return `undefined`; otherwise return
      an `API.CurrentUser` with `access: claims.role` and `name: claims.sub`. Verify by
      grepping `src/app.tsx` for `queryCurrentUser` and confirming no remaining reference.
- [x] 3.2 Verify `src/access.ts` needs no code change (only confirm, don't edit) by running
      `npm run test` (its existing `src/access.test.ts` should still pass unchanged) plus
      `npm run tsc`.

## 4. Session expiry (401) and token attachment

- [x] 4.1 In `src/requestErrorConfig.ts`'s `errorHandler`, add a branch before the existing
      `error.response` message-showing branch: when `error.response?.status === 401`, call
      `clearToken()` and `history.replace(`/user/login?redirect=${encodeURIComponent(current
      path+search+hash)}`)`, then return (skip the generic "Response status:401" message).
      Verify with a unit test in `src/requestErrorConfig.test.ts` asserting a 401 error
      triggers a redirect to `/user/login` with the current path preserved and clears the
      stored token.
- [x] 4.2 Uncomment and complete the placeholder `requestInterceptors` entry in
      `src/requestErrorConfig.ts`: read `getToken()` fresh on every request and set
      `Authorization: Bearer <token>` when present, added to `config.headers` without
      clobbering any headers already set by the call site. Verify by running `npm run dev`,
      logging in, and confirming (via the network tab) that `GET /api/devices` carries the
      `Authorization` header.

## 5. Logout

- [x] 5.1 Find the existing logout call site (`outLogin()` usage, likely in
      `src/components/RightContent`/`AvatarDropdown` or similar) and add `clearToken()`
      alongside it, then navigate to `/user/login`. Verify by logging in, logging out, and
      confirming `localStorage` no longer has the token key (devtools Application tab) and
      the app lands on `/user/login`.

## 6. Verification

- [x] 6.1 Run `npm run tsc`, `npm run lint`, and `npm run test` and confirm all pass.
- [ ] 6.2 Manually exercise against a running `yeweiyi-cloud` backend (per root
      `CLAUDE.md` quick-start) with a real account for each of the three roles
      (`platform_admin`, `customer_admin`, `customer_user`): confirm login succeeds,
      `/ops/*` and `/console/*` are gated correctly per role, a stored-but-expired/invalid
      token redirects to `/user/login` instead of hanging, and logout clears the session.
