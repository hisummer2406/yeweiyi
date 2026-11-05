// Package wechat is the one place the server talks to WeChat's mini-program API: exchanging a
// wx.login code for an openid, and a getPhoneNumber code for the user's verified phone number.
//
// It is deliberately small and optional. Nothing here runs at startup, and a deployment without
// internet access (or with wechat.enabled=false) simply has no Client, which the API layer reports
// as "wechat_unavailable" without affecting password login.
//
// Secrets: the AppSecret and the access_token never appear in a returned error, a log line or a
// URL we log. WeChat's own API puts the AppSecret in a query string for jscode2session, so request
// URLs must never be printed; errors from net/http embed the URL, so they are rewrapped here.
package wechat

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// Errors the API layer maps to contract codes. Anything else is an upstream failure.
var (
	// ErrInvalidCode: the code is wrong, expired (5 min) or already used. Codes are single-use, so a
	// replay lands here. The client must obtain a fresh one; retrying the same code never helps.
	ErrInvalidCode = errors.New("wechat: invalid, expired or already used code")
	// ErrAppMismatch: the phone number was issued to a different mini program than ours.
	ErrAppMismatch = errors.New("wechat: phone number belongs to a different appid")
	// ErrUpstream: WeChat could not be reached or answered with something we cannot use.
	ErrUpstream = errors.New("wechat: upstream error")
)

// Phone is a verified phone number as WeChat reports it.
type Phone struct {
	Pure        string // number without country code
	CountryCode string // "86"
}

// Client is what the API needs from WeChat. The interface exists so the API tests can run without
// the internet; real behaviour against WeChat is only verified by a real mini program.
type Client interface {
	// AppID is the configured mini-program AppID, stored next to openid in user_wechat_identities.
	AppID() string
	// OpenID exchanges a wx.login code. The session_key WeChat also returns is discarded.
	OpenID(ctx context.Context, loginCode string) (string, error)
	// PhoneNumber exchanges a getPhoneNumber code and verifies the number was issued to our AppID.
	PhoneNumber(ctx context.Context, phoneCode string) (Phone, error)
	// MiniCode renders the unlimited mini-program code (getwxacodeunlimit) that opens page with the
	// given scene. envVersion is release, trial or develop. Returns the image bytes.
	MiniCode(ctx context.Context, scene, page, envVersion string) ([]byte, error)
}

// Config configures the HTTP client.
type Config struct {
	AppID     string
	AppSecret string
	// BaseURL defaults to https://api.weixin.qq.com; tests point it at a local server.
	BaseURL string
	Timeout time.Duration
}

type httpClient struct {
	cfg  Config
	http *http.Client

	mu        sync.Mutex
	token     string
	tokenFrom time.Time
	tokenTTL  time.Duration
}

// New builds a Client. It performs no network I/O.
func New(cfg Config) (Client, error) {
	if cfg.AppID == "" || cfg.AppSecret == "" {
		return nil, errors.New("wechat: appId and appSecret are required")
	}
	if cfg.BaseURL == "" {
		cfg.BaseURL = "https://api.weixin.qq.com"
	}
	cfg.BaseURL = strings.TrimRight(cfg.BaseURL, "/")
	if cfg.Timeout <= 0 {
		cfg.Timeout = 5 * time.Second
	}
	return &httpClient{cfg: cfg, http: &http.Client{Timeout: cfg.Timeout}}, nil
}

func (c *httpClient) AppID() string { return c.cfg.AppID }

// WeChat error codes we treat as "your code is bad", as opposed to "we could not talk to WeChat".
const (
	errCodeInvalid = 40029 // invalid code
	errCodeUsed    = 40163 // code been used
	errCodeToken   = 40001 // invalid access_token (also 42001 expired, 40014 illegal)
	errCodeExpired = 42001
	errCodeIllegal = 40014
)

type apiStatus struct {
	ErrCode int    `json:"errcode"`
	ErrMsg  string `json:"errmsg"`
}

func (c *httpClient) OpenID(ctx context.Context, loginCode string) (string, error) {
	if loginCode == "" {
		return "", ErrInvalidCode
	}
	q := url.Values{
		"appid": {c.cfg.AppID}, "secret": {c.cfg.AppSecret}, "js_code": {loginCode},
		"grant_type": {"authorization_code"},
	}
	var resp struct {
		apiStatus
		OpenID string `json:"openid"`
		// session_key and unionid are deliberately not decoded.
	}
	if err := c.do(ctx, http.MethodGet, "/sns/jscode2session?"+q.Encode(), nil, &resp); err != nil {
		return "", err
	}
	switch resp.ErrCode {
	case 0:
	case errCodeInvalid, errCodeUsed:
		return "", ErrInvalidCode
	default:
		return "", fmt.Errorf("%w: jscode2session errcode %d", ErrUpstream, resp.ErrCode)
	}
	if resp.OpenID == "" {
		return "", fmt.Errorf("%w: jscode2session returned no openid", ErrUpstream)
	}
	return resp.OpenID, nil
}

func (c *httpClient) PhoneNumber(ctx context.Context, phoneCode string) (Phone, error) {
	if phoneCode == "" {
		return Phone{}, ErrInvalidCode
	}
	// One retry with a fresh token: an access_token can be invalidated early, e.g. when another
	// process of the same AppID asked for a new one.
	for attempt := 0; ; attempt++ {
		token, err := c.accessToken(ctx, attempt > 0)
		if err != nil {
			return Phone{}, err
		}
		body, _ := json.Marshal(map[string]string{"code": phoneCode})
		var resp struct {
			apiStatus
			PhoneInfo struct {
				PurePhoneNumber string `json:"purePhoneNumber"`
				CountryCode     string `json:"countryCode"`
				Watermark       struct {
					AppID string `json:"appid"`
				} `json:"watermark"`
			} `json:"phone_info"`
		}
		path := "/wxa/business/getuserphonenumber?access_token=" + url.QueryEscape(token)
		if err := c.do(ctx, http.MethodPost, path, body, &resp); err != nil {
			return Phone{}, err
		}
		switch resp.ErrCode {
		case 0:
			info := resp.PhoneInfo
			if info.Watermark.AppID != c.cfg.AppID {
				return Phone{}, ErrAppMismatch
			}
			if info.PurePhoneNumber == "" {
				return Phone{}, fmt.Errorf("%w: getuserphonenumber returned no number", ErrUpstream)
			}
			return Phone{Pure: info.PurePhoneNumber, CountryCode: strings.TrimPrefix(info.CountryCode, "+")}, nil
		case errCodeInvalid, errCodeUsed:
			return Phone{}, ErrInvalidCode
		case errCodeToken, errCodeExpired, errCodeIllegal:
			if attempt == 0 {
				continue
			}
			return Phone{}, fmt.Errorf("%w: access_token rejected twice (errcode %d)", ErrUpstream, resp.ErrCode)
		default:
			return Phone{}, fmt.Errorf("%w: getuserphonenumber errcode %d", ErrUpstream, resp.ErrCode)
		}
	}
}

// accessToken returns a cached token, fetching a new one with the stable_token endpoint when there
// is none, it is near expiry, or force is set. The token lives only in memory.
func (c *httpClient) accessToken(ctx context.Context, force bool) (string, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !force && c.token != "" && time.Since(c.tokenFrom) < c.tokenTTL-time.Minute {
		return c.token, nil
	}
	body, _ := json.Marshal(map[string]any{
		"grant_type": "client_credential", "appid": c.cfg.AppID, "secret": c.cfg.AppSecret,
		"force_refresh": force,
	})
	var resp struct {
		apiStatus
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := c.do(ctx, http.MethodPost, "/cgi-bin/stable_token", body, &resp); err != nil {
		return "", err
	}
	if resp.ErrCode != 0 || resp.AccessToken == "" {
		return "", fmt.Errorf("%w: stable_token errcode %d", ErrUpstream, resp.ErrCode)
	}
	c.token, c.tokenFrom = resp.AccessToken, time.Now()
	c.tokenTTL = time.Duration(resp.ExpiresIn) * time.Second
	if c.tokenTTL <= 0 {
		c.tokenTTL = 2 * time.Hour
	}
	return c.token, nil
}

// do performs a request and decodes a JSON body. Transport errors are rewrapped without the URL (it
// contains the AppSecret or access_token); only the fact that the call failed survives.
func (c *httpClient) do(ctx context.Context, method, path string, body []byte, out any) error {
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.cfg.BaseURL+path, rd)
	if err != nil {
		return fmt.Errorf("%w: build request", ErrUpstream)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := c.http.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err // drop the URL
		}
		return fmt.Errorf("%w: %v", ErrUpstream, err)
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK {
		return fmt.Errorf("%w: http %d", ErrUpstream, res.StatusCode)
	}
	if err := json.NewDecoder(io.LimitReader(res.Body, 1<<20)).Decode(out); err != nil {
		return fmt.Errorf("%w: undecodable response", ErrUpstream)
	}
	return nil
}

// MiniCode asks WeChat for a mini-program code image. WeChat answers with the raw image on success
// and with a JSON errcode body on failure, so this does not go through do().
func (c *httpClient) MiniCode(ctx context.Context, scene, page, envVersion string) ([]byte, error) {
	for attempt := 0; ; attempt++ {
		token, err := c.accessToken(ctx, attempt > 0)
		if err != nil {
			return nil, err
		}
		body, _ := json.Marshal(map[string]any{
			"scene": scene, "page": page, "env_version": envVersion,
			// check_path stays on: a page missing from the published version must fail loudly here
			// rather than print a code that opens nothing.
			"check_path": true, "width": 430,
		})
		req, err := http.NewRequestWithContext(ctx, http.MethodPost,
			c.cfg.BaseURL+"/wxa/getwxacodeunlimit?access_token="+url.QueryEscape(token), bytes.NewReader(body))
		if err != nil {
			return nil, fmt.Errorf("%w: build request", ErrUpstream)
		}
		req.Header.Set("Content-Type", "application/json")
		res, err := c.http.Do(req)
		if err != nil {
			var ue *url.Error
			if errors.As(err, &ue) {
				err = ue.Err
			}
			return nil, fmt.Errorf("%w: %v", ErrUpstream, err)
		}
		data, rerr := io.ReadAll(io.LimitReader(res.Body, 4<<20))
		res.Body.Close()
		if rerr != nil || res.StatusCode != http.StatusOK {
			return nil, fmt.Errorf("%w: getwxacodeunlimit http %d", ErrUpstream, res.StatusCode)
		}
		if !strings.HasPrefix(res.Header.Get("Content-Type"), "application/json") && len(data) > 0 &&
			!bytes.HasPrefix(bytes.TrimSpace(data), []byte("{")) {
			return data, nil
		}
		var st apiStatus
		if json.Unmarshal(data, &st) != nil {
			return nil, fmt.Errorf("%w: undecodable response", ErrUpstream)
		}
		switch st.ErrCode {
		case errCodeToken, errCodeExpired, errCodeIllegal:
			if attempt == 0 {
				continue
			}
		}
		return nil, fmt.Errorf("%w: getwxacodeunlimit errcode %d", ErrUpstream, st.ErrCode)
	}
}
