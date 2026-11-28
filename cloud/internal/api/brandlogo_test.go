package api_test

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"image/png"
	"net/http"
	"strings"
	"testing"
)

// Customer brand logo (#86, docs/brand-logo-contract.md).

type brandLogo struct {
	CustomerID  *int64 `json:"customerId"`
	Configured  bool   `json:"configured"`
	Version     string `json:"version"`
	ContentType string `json:"contentType"`
	Width       int    `json:"width"`
	Height      int    `json:"height"`
	Size        int    `json:"size"`
	Data        string `json:"data"`
	NotModified bool   `json:"notModified"`
}

func pngOf(t *testing.T, w, h int, c color.Color) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, c)
		}
	}
	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func b64(b []byte) map[string]string {
	return map[string]string{"data": base64.StdEncoding.EncodeToString(b)}
}

func TestBrandLogo_Lifecycle(t *testing.T) {
	tn := newTenancy(t)
	path := fmt.Sprintf("/api/customers/%d/brand-logo", tn.custA)
	red := pngOf(t, 128, 96, color.RGBA{255, 0, 0, 255})
	blue := pngOf(t, 64, 64, color.RGBA{0, 0, 255, 255})

	// Default before anything is configured, for the admin view and for the customer's own accounts.
	if got := decodeAs[brandLogo](t, tn.send(t, "GET", path, tn.platform, nil)); got.Configured || got.CustomerID == nil || *got.CustomerID != tn.custA {
		t.Fatalf("unconfigured admin view: %+v", got)
	}
	for _, tok := range []string{tn.adminA, tn.userA} {
		rec := tn.send(t, "GET", "/api/me/brand-logo", tok, nil)
		if rec.Code != http.StatusOK || decodeAs[brandLogo](t, rec).Configured {
			t.Fatalf("unconfigured me: %d %s", rec.Code, rec.Body.String())
		}
	}

	rec := tn.send(t, "PUT", path, tn.platform, b64(red))
	if rec.Code != http.StatusOK {
		t.Fatalf("put: %d %s", rec.Code, rec.Body.String())
	}
	saved := decodeAs[brandLogo](t, rec)
	if !saved.Configured || saved.Width != 128 || saved.Height != 96 || saved.Size != len(red) || saved.ContentType != "image/png" || saved.Version == "" {
		t.Fatalf("saved: %+v", saved)
	}
	if n := tn.auditCount(t, `action = 'customer.brand_logo.set' AND outcome = 'allowed' AND detail->>'customerId' = $1`, fmt.Sprint(tn.custA)); n != 1 {
		t.Errorf("set audit rows: %d, want 1", n)
	}

	// Both customer roles in A see the same picture; B still has the default.
	for _, tok := range []string{tn.adminA, tn.userA} {
		got := decodeAs[brandLogo](t, tn.send(t, "GET", "/api/me/brand-logo", tok, nil))
		raw, _ := base64.StdEncoding.DecodeString(got.Data)
		if !got.Configured || got.Version != saved.Version || !bytes.Equal(raw, red) {
			t.Fatalf("customer A me: %+v", got)
		}
	}
	for _, tok := range []string{tn.adminB, tn.userB} {
		if got := decodeAs[brandLogo](t, tn.send(t, "GET", "/api/me/brand-logo", tok, nil)); got.Configured || got.Data != "" {
			t.Fatalf("customer B must keep the default: %+v", got)
		}
	}
	// A platform admin belongs to no customer.
	if got := decodeAs[brandLogo](t, tn.send(t, "GET", "/api/me/brand-logo", tn.platform, nil)); got.Configured || got.CustomerID != nil {
		t.Fatalf("platform admin me: %+v", got)
	}

	// A client already holding the version gets no data back.
	got := decodeAs[brandLogo](t, tn.send(t, "GET", "/api/me/brand-logo?known="+saved.Version, tn.userA, nil))
	if !got.NotModified || got.Data != "" || got.Version != saved.Version {
		t.Fatalf("known version: %+v", got)
	}

	// Replacing changes the version; the old version now gets data again.
	replaced := decodeAs[brandLogo](t, tn.send(t, "PUT", path, tn.platform, b64(blue)))
	if replaced.Version == saved.Version {
		t.Fatal("replacing the picture must change the version")
	}
	got = decodeAs[brandLogo](t, tn.send(t, "GET", "/api/me/brand-logo?known="+saved.Version, tn.userA, nil))
	if got.NotModified || got.Version != replaced.Version || got.Data == "" {
		t.Fatalf("stale known version: %+v", got)
	}

	// Restore default, twice (idempotent), both audited.
	for i := 0; i < 2; i++ {
		rec := tn.send(t, "DELETE", path, tn.platform, nil)
		if rec.Code != http.StatusOK || decodeAs[brandLogo](t, rec).Configured {
			t.Fatalf("reset %d: %d %s", i, rec.Code, rec.Body.String())
		}
	}
	if n := tn.auditCount(t, `action = 'customer.brand_logo.reset' AND detail->>'customerId' = $1`, fmt.Sprint(tn.custA)); n != 2 {
		t.Errorf("reset audit rows: %d, want 2", n)
	}
	if got := decodeAs[brandLogo](t, tn.send(t, "GET", "/api/me/brand-logo?known="+replaced.Version, tn.userA, nil)); got.Configured || got.NotModified {
		t.Fatalf("after reset: %+v", got)
	}
}

func TestBrandLogo_OnlyPlatformAdminWrites(t *testing.T) {
	tn := newTenancy(t)
	img := b64(pngOf(t, 64, 64, color.White))
	for _, cust := range []int64{tn.custA, tn.custB} {
		path := fmt.Sprintf("/api/customers/%d/brand-logo", cust)
		for who, tok := range map[string]string{"admin A": tn.adminA, "user A": tn.userA} {
			for _, m := range []string{"GET", "PUT", "DELETE"} {
				var body any
				if m == "PUT" {
					body = img
				}
				rec := tn.send(t, m, path, tok, body)
				if rec.Code != http.StatusForbidden || !strings.Contains(rec.Body.String(), `"not authorized"`) {
					t.Errorf("%s %s customer %d: %d %s", who, m, cust, rec.Code, rec.Body.String())
				}
			}
		}
	}
	var n int
	if err := tn.env.pool.QueryRow(context.Background(),
		`SELECT count(*) FROM customer_brand_logos WHERE customer_id IN ($1, $2)`, tn.custA, tn.custB).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("refused writes stored %d rows", n)
	}
	if n := tn.auditCount(t, `action LIKE 'customer.brand_logo.%' AND outcome = 'denied' AND customer_id = $1`, tn.custA); n != 12 {
		t.Errorf("denied audit rows: %d, want 12", n)
	}
	if rec := tn.send(t, "PUT", "/api/customers/999999999/brand-logo", tn.platform, img); rec.Code != http.StatusNotFound {
		t.Errorf("unknown customer: %d", rec.Code)
	}
}

func TestBrandLogo_Validation(t *testing.T) {
	tn := newTenancy(t)
	path := fmt.Sprintf("/api/customers/%d/brand-logo", tn.custA)

	var jpg bytes.Buffer
	if err := jpeg.Encode(&jpg, image.NewRGBA(image.Rect(0, 0, 64, 64)), nil); err != nil {
		t.Fatal(err)
	}
	good := pngOf(t, 64, 64, color.Black)
	// Noise does not compress, so this PNG is well past 256 KiB while within the pixel bounds.
	big := image.NewRGBA(image.Rect(0, 0, 512, 512))
	if _, err := rand.Read(big.Pix); err != nil {
		t.Fatal(err)
	}
	var bigBuf bytes.Buffer
	if err := png.Encode(&bigBuf, big); err != nil {
		t.Fatal(err)
	}
	if bigBuf.Len() <= 256*1024 {
		t.Fatalf("test image is only %d bytes", bigBuf.Len())
	}

	cases := map[string]struct {
		body any
		want int
	}{
		"empty":          {map[string]string{"data": ""}, http.StatusBadRequest},
		"not base64":     {map[string]string{"data": "!!!"}, http.StatusBadRequest},
		"jpeg":           {b64(jpg.Bytes()), http.StatusBadRequest},
		"truncated png":  {b64(good[:len(good)-12]), http.StatusBadRequest},
		"too small":      {b64(pngOf(t, 63, 64, color.Black)), http.StatusBadRequest},
		"too large side": {b64(pngOf(t, 1025, 64, color.Black)), http.StatusBadRequest},
		"too many bytes": {b64(bigBuf.Bytes()), http.StatusRequestEntityTooLarge},
	}
	for name, tc := range cases {
		rec := tn.send(t, "PUT", path, tn.platform, tc.body)
		if rec.Code != tc.want || !strings.Contains(rec.Body.String(), "brand_logo_invalid") {
			t.Errorf("%s: %d %s, want %d brand_logo_invalid", name, rec.Code, rec.Body.String(), tc.want)
		}
	}
	if got := decodeAs[brandLogo](t, tn.send(t, "GET", path, tn.platform, nil)); got.Configured {
		t.Fatal("an invalid upload must not be stored")
	}
}

// A logo must not keep an otherwise empty customer from being deleted.
func TestBrandLogo_DeletedWithCustomer(t *testing.T) {
	tn := newTenancy(t)
	rec := tn.send(t, "POST", "/api/customers", tn.platform, map[string]string{"name": uniqueName("logo")})
	created := decodeAs[struct {
		ID int64 `json:"id"`
	}](t, rec)
	path := fmt.Sprintf("/api/customers/%d/brand-logo", created.ID)
	if rec := tn.send(t, "PUT", path, tn.platform, b64(pngOf(t, 64, 64, color.White))); rec.Code != http.StatusOK {
		t.Fatalf("put: %d %s", rec.Code, rec.Body.String())
	}
	if rec := tn.send(t, "DELETE", fmt.Sprintf("/api/customers/%d", created.ID), tn.platform, nil); rec.Code != http.StatusNoContent {
		t.Fatalf("delete customer with a logo: %d %s", rec.Code, rec.Body.String())
	}
	if rec := tn.send(t, "GET", path, tn.platform, nil); rec.Code != http.StatusNotFound {
		t.Fatalf("logo of a deleted customer: %d", rec.Code)
	}
}
