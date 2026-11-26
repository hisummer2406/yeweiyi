package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"image/png"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/store/db"
)

// Customer brand logo (#86, contract docs/brand-logo-contract.md). A platform admin uploads one PNG per
// customer; customer accounts read their own customer's logo through /api/me/brand-logo, decided from
// the caller's database row, so a customer can never name another customer's logo. No row means the
// default logo, which lives in the clients, not here.
//
// The image travels as base64 inside JSON rather than as an image URL: the mini-program's <image> cannot
// send the Authorization header, and a public URL would let anyone read any customer's logo. The image is
// small, so the overhead does not matter, and the existing request domain is all the client needs.

const (
	brandLogoMaxBytes = 256 * 1024
	brandLogoMinSide  = 64
	brandLogoMaxSide  = 1024
	brandLogoType     = "image/png"

	codeBrandLogoInvalid = "brand_logo_invalid"
)

type brandLogoResponse struct {
	CustomerID  *int64     `json:"customerId"` // null for a platform admin (no customer)
	Configured  bool       `json:"configured"`
	Version     string     `json:"version,omitempty"`
	ContentType string     `json:"contentType,omitempty"`
	Width       int32      `json:"width,omitempty"`
	Height      int32      `json:"height,omitempty"`
	Size        int        `json:"size,omitempty"`
	UpdatedAt   *time.Time `json:"updatedAt,omitempty"`
	// Data is the base64 image. It is left out when the caller already holds this version (?known=).
	Data        string `json:"data,omitempty"`
	NotModified bool   `json:"notModified,omitempty"`
}

func toBrandLogoResponse(row db.CustomerBrandLogo, known string) brandLogoResponse {
	r := brandLogoResponse{
		CustomerID: &row.CustomerID, Configured: true, Version: row.Version, ContentType: row.ContentType,
		Width: row.Width, Height: row.Height, Size: len(row.Image),
	}
	if row.UpdatedAt.Valid {
		t := row.UpdatedAt.Time
		r.UpdatedAt = &t
	}
	if known != "" && known == row.Version {
		r.NotModified = true
	} else {
		r.Data = base64.StdEncoding.EncodeToString(row.Image)
	}
	return r
}

// brandLogoVersion is derived from the content, so re-uploading the same picture keeps the client's cache.
func brandLogoVersion(img []byte) string {
	sum := sha256.Sum256(img)
	return hex.EncodeToString(sum[:8])
}

type decodedBrandLogo struct {
	image         []byte
	width, height int
}

// decodeBrandLogo checks what the bytes really are: the whole PNG must decode (a renamed JPEG or a
// truncated file fails), and size and dimensions must be within bounds. It returns a short reason for
// the client on failure.
func decodeBrandLogo(b64 string) (decodedBrandLogo, string) {
	if b64 == "" {
		return decodedBrandLogo{}, "image is required"
	}
	if base64.StdEncoding.DecodedLen(len(b64)) > brandLogoMaxBytes+3 {
		return decodedBrandLogo{}, "image is larger than 256 KiB"
	}
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return decodedBrandLogo{}, "image is not valid base64"
	}
	if len(raw) > brandLogoMaxBytes {
		return decodedBrandLogo{}, "image is larger than 256 KiB"
	}
	if http.DetectContentType(raw) != brandLogoType {
		return decodedBrandLogo{}, "image must be a PNG"
	}
	cfg, err := png.DecodeConfig(bytes.NewReader(raw))
	if err != nil {
		return decodedBrandLogo{}, "image must be a PNG"
	}
	if cfg.Width < brandLogoMinSide || cfg.Height < brandLogoMinSide ||
		cfg.Width > brandLogoMaxSide || cfg.Height > brandLogoMaxSide {
		return decodedBrandLogo{}, "image must be 64-1024 pixels on each side"
	}
	// Dimensions are checked before the full decode so a header claiming a huge image cannot make us
	// allocate for it.
	if _, err := png.Decode(bytes.NewReader(raw)); err != nil {
		return decodedBrandLogo{}, "image must be a PNG"
	}
	return decodedBrandLogo{image: raw, width: cfg.Width, height: cfg.Height}, ""
}

// brandLogoActor admits platform admins only; everyone else gets the role refusal before the id or body
// is looked at.
func brandLogoActor(c *gin.Context, deps Deps, action string) (db.User, int64, bool) {
	actor, ok := requireAdminActor(c, deps)
	if !ok {
		return actor, 0, false
	}
	if actor.Role != control.RolePlatformAdmin {
		refuse(c, deps, actor, action, "platform admin only", nil)
		return actor, 0, false
	}
	id, ok := parseIDParam(c)
	return actor, id, ok
}

func customerNotFound(c *gin.Context) {
	apiError(c, http.StatusNotFound, codeCustomerNotFound, "customer not found")
}

// getBrandLogoHandler: GET /api/customers/:id/brand-logo (platform admin).
func getBrandLogoHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		_, id, ok := brandLogoActor(c, deps, "customer.brand_logo.get")
		if !ok {
			return
		}
		ctx := c.Request.Context()
		if _, err := deps.Queries.GetCustomer(ctx, id); err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				customerNotFound(c)
				return
			}
			internalError(c, err)
			return
		}
		row, err := deps.Queries.GetCustomerBrandLogo(ctx, id)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusOK, brandLogoResponse{CustomerID: &id})
				return
			}
			internalError(c, err)
			return
		}
		c.JSON(http.StatusOK, toBrandLogoResponse(row, c.Query("known")))
	}
}

type putBrandLogoRequest struct {
	Data string `json:"data"`
}

// putBrandLogoHandler: PUT /api/customers/:id/brand-logo {"data": base64 PNG} (platform admin). Saving
// replaces the customer's logo and audits it in the same transaction.
func putBrandLogoHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, id, ok := brandLogoActor(c, deps, "customer.brand_logo.set")
		if !ok {
			return
		}
		// base64 of 256 KiB plus the JSON around it; anything bigger is refused before it is read.
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, brandLogoMaxBytes*4/3+4096)
		var req putBrandLogoRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			var tooBig *http.MaxBytesError
			if errors.As(err, &tooBig) {
				apiError(c, http.StatusRequestEntityTooLarge, codeBrandLogoInvalid, "image is larger than 256 KiB")
				return
			}
			badRequest(c, err)
			return
		}
		logo, reason := decodeBrandLogo(req.Data)
		if reason != "" {
			apiError(c, http.StatusBadRequest, codeBrandLogoInvalid, reason)
			return
		}

		ctx := c.Request.Context()
		var (
			saved    db.CustomerBrandLogo
			notFound bool
		)
		err := withTx(ctx, deps, func(q *db.Queries) error {
			if _, err := q.GetCustomerForUpdate(ctx, id); err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					notFound = true
					return nil
				}
				return err
			}
			var err error
			saved, err = q.UpsertCustomerBrandLogo(ctx, db.UpsertCustomerBrandLogoParams{
				CustomerID:  id,
				ContentType: brandLogoType,
				Image:       logo.image,
				Width:       int32(logo.width),
				Height:      int32(logo.height),
				Version:     brandLogoVersion(logo.image),
				UpdatedBy:   pgtype.Int8{Int64: actor.ID, Valid: true},
			})
			if err != nil {
				return err
			}
			// Filed under no customer, like the other customer-level actions: an audit row referencing
			// the customer would make it undeletable.
			return auditAdmin(ctx, q, actor, "customer.brand_logo.set", "", pgtype.Int8{}, "allowed", map[string]any{
				"customerId": id, "version": saved.Version, "size": len(saved.Image),
				"width": saved.Width, "height": saved.Height,
			})
		})
		if err != nil {
			internalError(c, err)
			return
		}
		if notFound {
			customerNotFound(c)
			return
		}
		c.JSON(http.StatusOK, toBrandLogoResponse(saved, ""))
	}
}

// deleteBrandLogoHandler: DELETE /api/customers/:id/brand-logo (platform admin) restores the default
// logo. Idempotent: a customer already on the default answers the same 200.
func deleteBrandLogoHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, id, ok := brandLogoActor(c, deps, "customer.brand_logo.reset")
		if !ok {
			return
		}
		ctx := c.Request.Context()
		var notFound bool
		err := withTx(ctx, deps, func(q *db.Queries) error {
			if _, err := q.GetCustomerForUpdate(ctx, id); err != nil {
				if errors.Is(err, pgx.ErrNoRows) {
					notFound = true
					return nil
				}
				return err
			}
			n, err := q.DeleteCustomerBrandLogo(ctx, id)
			if err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "customer.brand_logo.reset", "", pgtype.Int8{}, "allowed",
				map[string]any{"customerId": id, "removed": n > 0})
		})
		if err != nil {
			internalError(c, err)
			return
		}
		if notFound {
			customerNotFound(c)
			return
		}
		c.JSON(http.StatusOK, brandLogoResponse{CustomerID: &id})
	}
}

// myBrandLogoHandler: GET /api/me/brand-logo[?known=<version>] for any signed-in account. The customer
// comes from the caller's row (the auth middleware has already refused disabled accounts and customers
// still being configured). A platform admin belongs to no customer and always gets the default.
func myBrandLogoHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		id, _ := c.Get("userID")
		ctx := c.Request.Context()
		user, err := deps.Queries.GetUserByID(ctx, id.(int64))
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				forbidAccount(c)
				return
			}
			internalError(c, err)
			return
		}
		if !user.CustomerID.Valid {
			c.JSON(http.StatusOK, brandLogoResponse{})
			return
		}
		cust := user.CustomerID.Int64
		row, err := deps.Queries.GetCustomerBrandLogo(ctx, cust)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				c.JSON(http.StatusOK, brandLogoResponse{CustomerID: &cust})
				return
			}
			internalError(c, err)
			return
		}
		c.JSON(http.StatusOK, toBrandLogoResponse(row, c.Query("known")))
	}
}
