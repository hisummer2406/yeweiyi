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
