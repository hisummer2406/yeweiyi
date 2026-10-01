## Purpose

Lets any of the three roles (`platform_admin`, `customer_admin`, `customer_user`) actually
authenticate against `yeweiyi-cloud` and stay authenticated across page loads, replacing the
Ant Design Pro template's fake login/current-user flow with the real bearer-token contract.

## ADDED Requirements

### Requirement: Real login request
The login page SHALL submit the entered username and password to
`POST /api/login` and SHALL treat a response containing a `token` field as a
successful login.

#### Scenario: Valid credentials
- **WHEN** the user submits a username/password for which the backend
  returns `200 {token}`
- **THEN** the app SHALL store that token and proceed to the post-login
  redirect

#### Scenario: Invalid credentials
- **WHEN** the backend responds with a non-2xx status (e.g. `401
  {error: "invalid username or password"}`)
- **THEN** the login page SHALL show a login-failure message and SHALL NOT
  store any token or navigate away from the login page

### Requirement: Bearer token attached to authenticated requests
Every request to `/api/*` other than `POST /api/login` SHALL include an
`Authorization: Bearer <token>` header when a token is currently stored, and
SHALL omit it when none is stored.

#### Scenario: Request made with a stored token
- **WHEN** any `/api/*` request other than login is issued while a token is
  stored
- **THEN** the request SHALL carry `Authorization: Bearer <token>` with that
  stored token

#### Scenario: Request made with no stored token
- **WHEN** any `/api/*` request is issued while no token is stored (e.g. the
  user has never logged in, or has logged out)
- **THEN** the request SHALL be sent without an `Authorization` header rather
  than the app inventing or reusing a stale value

### Requirement: Current user derived from the stored JWT
The backend exposes no current-user endpoint, so the app SHALL derive the
current user entirely by decoding the stored JWT's payload
(`{uid, role, sub, exp, iat}`) client-side, never by calling a server
endpoint for it. The derived value SHALL expose the token's `role` claim as
the `access` field so existing role checks keep working unchanged.

#### Scenario: Valid, unexpired token stored
- **WHEN** app initialization runs and a stored token's `exp` claim is in the
  future
- **THEN** the app SHALL treat the user as logged in, with `access` set to
  that token's `role` claim

#### Scenario: No token stored
- **WHEN** app initialization runs and no token is stored
- **THEN** the app SHALL treat there as being no current user, and SHALL
  redirect to `/user/login` for any route other than the login page itself

#### Scenario: Stored token is malformed or expired
- **WHEN** app initialization runs and the stored token cannot be decoded, or
  its `exp` claim is in the past
- **THEN** the app SHALL treat there as being no current user (same as no
  token stored), and SHALL clear the invalid token from storage

### Requirement: Session ends on any 401 response
Any `/api/*` response with status `401` (other than the login request itself
failing) SHALL be treated as an ended session: the stored token SHALL be
cleared and the app SHALL redirect to `/user/login`.

#### Scenario: An authenticated request comes back 401
- **WHEN** a request made with a stored token receives a `401` response
  (token expired past its 24h lifetime, or otherwise rejected by the backend)
- **THEN** the app SHALL clear the stored token and redirect to
  `/user/login`, preserving the current path so the user returns to it after
  logging in again

### Requirement: Logout clears the stored token
Signing out SHALL clear the stored token in addition to any other
client-side session state, so a stale token cannot outlive an explicit
sign-out.

#### Scenario: User signs out
- **WHEN** the user triggers logout
- **THEN** the stored token SHALL be cleared and the app SHALL navigate to
  `/user/login`
