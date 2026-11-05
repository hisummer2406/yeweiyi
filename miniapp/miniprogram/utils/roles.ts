// Pure (no `wx`), so it can be checked in plain Node: `npm run check`.

// customer_user is view-only (yeweiyi-cloud tank-grants-contract v1.0 §2): the cloud refuses every
// command and hides the command history from them, so the app does not offer the control entries at
// all. Hiding is a courtesy — the server is the boundary. An unknown role is NOT treated as read-only:
// the server decides, and an empty role must not lock out an administrator whose token has no claim.
export function isReadOnlyRole(role: string): boolean {
  return role === 'customer_user';
}
