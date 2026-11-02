package api

import (
	"regexp"
	"strings"
)

// phonePattern is the stored form: a mainland China mobile number, 11 digits. The same rule is
// enforced by users_phone_format_check, so a number that gets past normalizePhone cannot be refused
// by the database for format.
var phonePattern = regexp.MustCompile(`^1[3-9][0-9]{9}$`)

// normalizePhone turns the ways people write a mainland mobile number into the stored 11 digits.
// It strips spaces and hyphens and a leading +86 / 86 / 0086, and reports false for anything else.
// Only mainland numbers are supported (contract Q1); a non-86 country code must be refused by the
// caller before it reaches here, because stripping "+1" would turn a foreign number into a wrong one.
func normalizePhone(raw string) (string, bool) {
	s := strings.NewReplacer(" ", "", "-", "", " ", "").Replace(strings.TrimSpace(raw))
	for _, prefix := range []string{"+86", "0086", "86"} {
		// "86" is only a country code when what follows is a full 11-digit number; otherwise an
		// 11-digit number that merely starts with 86 cannot exist (mobile numbers start with 1).
		if rest, ok := strings.CutPrefix(s, prefix); ok && phonePattern.MatchString(rest) {
			s = rest
			break
		}
	}
	if !phonePattern.MatchString(s) {
		return "", false
	}
	return s, true
}

// maskPhone keeps the first 3 and last 4 digits: 13800138000 -> 138****8000.
func maskPhone(phone string) string {
	if len(phone) != 11 {
		return ""
	}
	return phone[:3] + "****" + phone[7:]
}

// usernamePattern is the form of a customer-side username (cloud#77): 3-32 characters, letters and
// digits with . _ - inside, starting with a letter or digit. It mirrors users_phone_role_check.
// Case is not significant (the unique index and login both fold it), but the spelling given is kept.
var usernamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$`)

// validAccountUsername reports whether name may be the username of a customer-side account. A name
// shaped like a mainland mobile number is refused so the two identifiers can never be confused: the
// client routes digits-that-look-like-a-phone to the `phone` field.
func validAccountUsername(name string) bool {
	return usernamePattern.MatchString(name) && !phonePattern.MatchString(name)
}
