package api

import (
	"context"
	"log"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
)

// Password-guessing limits (cloud#77). Staff can now have passwords, so the login endpoint is no longer
// only reachable by a handful of administrators. Failures are counted per submitted identifier and per
// client address in Redis; once either is over its limit the endpoint answers 429 before looking at
// the credentials. The identifier is whatever was typed, found or not, so a lockout reveals nothing
// about which accounts exist.
//
// Known trade-off: anyone can lock a *known* identifier for a window by failing against it. The
// recovery is to wait it out, or for a platform admin to reset the password (which clears the count).
const (
	defaultLoginMaxFailures = 5
	defaultLoginLockout     = 15 * time.Minute
	// An address may fail this many times as often as one account, because one office or carrier NAT
	// legitimately hosts many users.
	loginIPFailureFactor = 6

	codeLoginRateLimited = "login_rate_limited"
)

func (d Deps) loginMaxFailures() int {
	if d.LoginMaxFailures > 0 {
		return d.LoginMaxFailures
	}
	return defaultLoginMaxFailures
}

func (d Deps) loginLockout() time.Duration {
	if d.LoginLockout > 0 {
		return d.LoginLockout
	}
	return defaultLoginLockout
}

// loginKeys are the counters a login attempt is charged to. account is namespaced by how it was typed
// ("u:" username, case folded; "p:" phone) so the two spellings of one account are throttled apart —
// harmless, since each is a different guess surface.
func loginKeys(c *gin.Context, account string) (acct, ip string) {
	return "loginfail:acct:" + account, "loginfail:ip:" + c.ClientIP()
}

func usernameLoginKey(username string) string {
	return "u:" + strings.ToLower(strings.TrimSpace(username))
}
func phoneLoginKey(phone string) string { return "p:" + phone }

// loginBlocked reports how long the caller must wait, or 0 when it may try. Redis trouble fails open:
// an outage must not lock every user out, and bcrypt cost still bounds guessing.
func loginBlocked(ctx context.Context, deps Deps, acctKey, ipKey string) time.Duration {
	if deps.Redis == nil {
		return 0
	}
	var wait time.Duration
	for key, limit := range map[string]int{
		acctKey: deps.loginMaxFailures(),
		ipKey:   deps.loginMaxFailures() * loginIPFailureFactor,
	} {
		n, err := deps.Redis.Get(ctx, key).Int()
		if err != nil {
			continue // redis.Nil (no failures yet) or an outage
		}
		if n >= limit {
			ttl, err := deps.Redis.TTL(ctx, key).Result()
			if err != nil || ttl <= 0 {
				ttl = deps.loginLockout()
			}
			if ttl > wait {
				wait = ttl
			}
		}
	}
	return wait
}

// loginFailed charges one failure to both counters. The window starts at the first failure and is not
// extended by later ones, so a lockout always ends loginLockout after the failure that began it.
func loginFailed(ctx context.Context, deps Deps, keys ...string) {
	if deps.Redis == nil {
		return
	}
	for _, key := range keys {
		n, err := deps.Redis.Incr(ctx, key).Result()
		if err != nil {
			log.Printf("auth: login failure counter: %v", err)
			return
		}
		if n == 1 {
			deps.Redis.Expire(ctx, key, deps.loginLockout())
		}
	}
}

// loginSucceeded forgets the account's failures (not the address's: a shared address that also
// carries a guesser must keep paying for it).
func loginSucceeded(ctx context.Context, deps Deps, acctKey string) {
	if deps.Redis != nil {
		deps.Redis.Del(ctx, acctKey)
	}
}

// clearLoginFailures is the recovery path a platform admin's password reset triggers.
func clearLoginFailures(ctx context.Context, deps Deps, keys ...string) {
	if deps.Redis == nil {
		return
	}
	full := make([]string, 0, len(keys))
	for _, k := range keys {
		full = append(full, "loginfail:acct:"+k)
	}
	deps.Redis.Del(ctx, full...)
}
