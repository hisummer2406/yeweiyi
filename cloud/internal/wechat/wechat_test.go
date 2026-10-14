package wechat_test

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"yeweiyi-cloud/internal/wechat"
)

// fakeWeChat is a hand-written stand-in for api.weixin.qq.com. It answers with the JSON shapes
// WeChat documents; it proves our client's handling of them, not WeChat's behaviour.
type fakeWeChat struct {
	srv *httptest.Server

	openidFor  map[string]string // login code -> openid
	phoneFor   map[string]string // phone code -> number
	phoneAppID string            // watermark appid
	stable     atomic.Int32      // stable_token calls
	// rejectTokens makes getuserphonenumber answer 40001 for this many calls first.
	rejectTokens atomic.Int32
	status       int // forced HTTP status, 0 = normal
	lastURLs     []string
}

func newFake(t *testing.T) *fakeWeChat {
	f := &fakeWeChat{
		openidFor:  map[string]string{"good-login": "openid-1"},
		phoneFor:   map[string]string{"good-phone": "13800138000"},
		phoneAppID: "wx-test-app",
	}
	mux := http.NewServeMux()
	reply := func(w http.ResponseWriter, v any) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(v)
	}
	mux.HandleFunc("/sns/jscode2session", func(w http.ResponseWriter, r *http.Request) {
		f.lastURLs = append(f.lastURLs, r.URL.String())
		if f.status != 0 {
			w.WriteHeader(f.status)
			return
		}
		switch code := r.URL.Query().Get("js_code"); code {
		case "used-login":
			reply(w, map[string]any{"errcode": 40163, "errmsg": "code been used"})
		default:
			if id, ok := f.openidFor[code]; ok {
				reply(w, map[string]any{"openid": id, "session_key": "SECRET-SESSION-KEY"})
				return
			}
			reply(w, map[string]any{"errcode": 40029, "errmsg": "invalid code"})
		}
	})
	mux.HandleFunc("/cgi-bin/stable_token", func(w http.ResponseWriter, r *http.Request) {
		n := f.stable.Add(1)
		reply(w, map[string]any{"access_token": "tok-" + string(rune('0'+n)), "expires_in": 7200})
	})
	mux.HandleFunc("/wxa/business/getuserphonenumber", func(w http.ResponseWriter, r *http.Request) {
		if f.rejectTokens.Add(-1) >= 0 {
			reply(w, map[string]any{"errcode": 40001, "errmsg": "invalid credential"})
			return
		}
		var body struct{ Code string }
		json.NewDecoder(r.Body).Decode(&body)
		num, ok := f.phoneFor[body.Code]
		if !ok {
			reply(w, map[string]any{"errcode": 40029, "errmsg": "invalid code"})
			return
		}
		reply(w, map[string]any{"errcode": 0, "errmsg": "ok", "phone_info": map[string]any{
			"phoneNumber": "+86" + num, "purePhoneNumber": num, "countryCode": "86",
			"watermark": map[string]any{"appid": f.phoneAppID, "timestamp": 1},
		}})
	})
	f.srv = httptest.NewServer(mux)
	t.Cleanup(f.srv.Close)
	return f
}

func (f *fakeWeChat) client(t *testing.T) wechat.Client {
	t.Helper()
	c, err := wechat.New(wechat.Config{AppID: "wx-test-app", AppSecret: "TOP-SECRET-VALUE", BaseURL: f.srv.URL, Timeout: 2 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestNewRequiresCredentials(t *testing.T) {
	if _, err := wechat.New(wechat.Config{AppID: "x"}); err == nil {
		t.Error("a client without an AppSecret must not be constructible")
	}
}

func TestOpenID(t *testing.T) {
	f := newFake(t)
	c := f.client(t)
	ctx := context.Background()

	if id, err := c.OpenID(ctx, "good-login"); err != nil || id != "openid-1" {
		t.Fatalf("OpenID = %q, %v", id, err)
	}
	for _, code := range []string{"bad-login", "used-login", ""} {
		if _, err := c.OpenID(ctx, code); !errors.Is(err, wechat.ErrInvalidCode) {
			t.Errorf("OpenID(%q) error = %v, want ErrInvalidCode", code, err)
		}
	}
	f.status = http.StatusBadGateway
	_, err := c.OpenID(ctx, "good-login")
	if !errors.Is(err, wechat.ErrUpstream) || errors.Is(err, wechat.ErrInvalidCode) {
		t.Errorf("a 502 from WeChat: error = %v, want ErrUpstream", err)
	}
}

func TestPhoneNumber(t *testing.T) {
	f := newFake(t)
	c := f.client(t)
	ctx := context.Background()

	p, err := c.PhoneNumber(ctx, "good-phone")
	if err != nil || p.Pure != "13800138000" || p.CountryCode != "86" {
		t.Fatalf("PhoneNumber = %+v, %v", p, err)
	}
	if _, err := c.PhoneNumber(ctx, "good-phone"); err != nil {
		t.Fatal(err)
	}
	if n := f.stable.Load(); n != 1 {
		t.Errorf("access_token fetched %d times for two calls, want it cached (1)", n)
	}
	if _, err := c.PhoneNumber(ctx, "nope"); !errors.Is(err, wechat.ErrInvalidCode) {
		t.Errorf("invalid phone code: %v", err)
	}

	f.phoneAppID = "wx-someone-else"
	if _, err := c.PhoneNumber(ctx, "good-phone"); !errors.Is(err, wechat.ErrAppMismatch) {
		t.Errorf("a number issued to another appid: %v, want ErrAppMismatch", err)
	}
	f.phoneAppID = "wx-test-app"

	// A rejected token is replaced once and the call retried.
	f.rejectTokens.Store(1)
	before := f.stable.Load()
	if _, err := c.PhoneNumber(ctx, "good-phone"); err != nil {
		t.Errorf("after a stale access_token: %v", err)
	}
	if f.stable.Load() != before+1 {
		t.Errorf("expected exactly one token refresh, got %d", f.stable.Load()-before)
	}
	// ... but not forever.
	f.rejectTokens.Store(100)
	if _, err := c.PhoneNumber(ctx, "good-phone"); !errors.Is(err, wechat.ErrUpstream) {
		t.Errorf("a token rejected twice: %v, want ErrUpstream", err)
	}
}

func TestErrorsDoNotLeakSecrets(t *testing.T) {
	// A dead server: net/http's error text embeds the request URL, which carries the AppSecret.
	f := newFake(t)
	c := f.client(t)
	f.srv.Close()

	_, err := c.OpenID(context.Background(), "good-login")
	if err == nil {
		t.Fatal("expected a transport error")
	}
	for _, secret := range []string{"TOP-SECRET-VALUE", "good-login", "secret="} {
		if strings.Contains(err.Error(), secret) {
			t.Errorf("error leaks %q: %v", secret, err)
		}
	}
	if !errors.Is(err, wechat.ErrUpstream) {
		t.Errorf("error = %v, want ErrUpstream", err)
	}
}

func TestMiniCode(t *testing.T) {
	f := newFake(t)
	var gotBody string
	f.srv.Config.Handler.(*http.ServeMux).HandleFunc("/wxa/getwxacodeunlimit", func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		if r.URL.Query().Get("access_token") == "" {
			t.Error("no access_token")
		}
		if strings.Contains(gotBody, `"scene":"BAD"`) {
			w.Header().Set("Content-Type", "application/json")
			w.Write([]byte(`{"errcode":41030,"errmsg":"invalid page"}`))
			return
		}
		w.Header().Set("Content-Type", "image/png")
		w.Write([]byte("\x89PNG-data"))
	})
	c := f.client(t)
	img, err := c.MiniCode(context.Background(), "ZCT000001", "pages/device-detail/device-detail", "trial")
	if err != nil || string(img) != "\x89PNG-data" {
		t.Fatalf("MiniCode = %q, %v", img, err)
	}
	for _, want := range []string{`"scene":"ZCT000001"`, `"page":"pages/device-detail/device-detail"`, `"env_version":"trial"`} {
		if !strings.Contains(gotBody, want) {
			t.Errorf("request body %s lacks %s", gotBody, want)
		}
	}
	if _, err := c.MiniCode(context.Background(), "BAD", "p", "release"); !errors.Is(err, wechat.ErrUpstream) {
		t.Errorf("errcode body: %v, want ErrUpstream", err)
	}
}
