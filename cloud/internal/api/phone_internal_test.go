package api

import "testing"

func TestNormalizePhone(t *testing.T) {
	good := map[string]string{
		"13800138000":       "13800138000",
		" 138 0013 8000 ":   "13800138000",
		"138-0013-8000":     "13800138000",
		"+8613800138000":    "13800138000",
		"+86 138 0013 8000": "13800138000",
		"008613800138000":   "13800138000",
		"8613800138000":     "13800138000",
	}
	for in, want := range good {
		got, ok := normalizePhone(in)
		if !ok || got != want {
			t.Errorf("normalizePhone(%q) = %q, %v; want %q", in, got, ok, want)
		}
	}
	for _, in := range []string{
		"", "1380013800", "138001380000", "12800138000", "23800138000", "+11380013800", "+1 13800138000",
		"abc", "1380013800a", "+86", "86",
	} {
		if got, ok := normalizePhone(in); ok {
			t.Errorf("normalizePhone(%q) = %q, want rejection", in, got)
		}
	}
}

func TestMaskPhone(t *testing.T) {
	if got := maskPhone("13800138000"); got != "138****8000" {
		t.Errorf("maskPhone = %q", got)
	}
	if got := maskPhone("123"); got != "" {
		t.Errorf("maskPhone of malformed = %q, want empty", got)
	}
}
