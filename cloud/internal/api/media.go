package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// 介质库（#27，契约 docs/media-library-contract.md）：全部接口仅平台管理员，按库内当前角色判定。
// 这里只维护字典，不改动任何油罐或设备配置。

const (
	mediaScopePublic   = "public"
	mediaScopeCustomer = "customer"
	mediaNameMax       = 20
	mediaNameEnMax     = 40
)

type mediaResponse struct {
	ID           int64     `json:"id"`
	Name         string    `json:"name"`
	NameEn       *string   `json:"nameEn"`
	Scope        string    `json:"scope"`
	CustomerID   *int64    `json:"customerId"`
	BuiltinIndex *int16    `json:"builtinIndex"`
	Enabled      bool      `json:"enabled"`
	UpdatedAt    time.Time `json:"updatedAt"`
}

func toMediaResponse(m db.Medium) mediaResponse {
	r := mediaResponse{
		ID: m.ID, Name: m.Name, Scope: m.Scope, CustomerID: int8Ptr(m.CustomerID),
		Enabled: m.Enabled, UpdatedAt: m.UpdatedAt.Time,
	}
	if m.NameEn.Valid {
		r.NameEn = &m.NameEn.String
	}
	if m.BuiltinIndex.Valid {
		r.BuiltinIndex = &m.BuiltinIndex.Int16
	}
	return r
}

// requireMediaAdmin answers the request itself unless the caller is currently a platform admin.
func requireMediaAdmin(c *gin.Context, deps Deps, action string) (db.User, bool) {
	actor, ok := requireAdminActor(c, deps)
	if !ok {
		return db.User{}, false
	}
	if actor.Role != control.RolePlatformAdmin {
		refuse(c, deps, actor, action, "platform admin only", nil)
		return db.User{}, false
	}
	return actor, true
}

func validMediaName(s string, limit int) (string, bool) {
	s = strings.TrimSpace(s)
	return s, s != "" && utf8.RuneCountInString(s) <= limit
}

// mediaNameEn normalizes an optional English name: absent or blank means "no translation".
func mediaNameEn(s *string) (pgtype.Text, bool) {
	if s == nil || strings.TrimSpace(*s) == "" {
		return pgtype.Text{}, true
	}
	v, ok := validMediaName(*s, mediaNameEnMax)
	return pgtype.Text{String: v, Valid: ok}, ok
}

func writeMediaError(c *gin.Context, err error) {
	switch {
	case isPgCode(err, "23505"):
		apiError(c, http.StatusConflict, "media_name_exists", "a medium with this name already exists in this scope")
	case isPgCode(err, "23503"):
		apiError(c, http.StatusBadRequest, "unknown_customer", "unknown customerId")
	default:
		internalError(c, err)
	}
}

func listMediaHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if _, ok := requireMediaAdmin(c, deps, "media.list"); !ok {
			return
		}
		rows, err := deps.Queries.ListMedia(c.Request.Context())
		if err != nil {
			internalError(c, err)
			return
		}
		out := make([]mediaResponse, 0, len(rows))
		for _, r := range rows {
			out = append(out, toMediaResponse(r))
		}
		c.JSON(http.StatusOK, gin.H{"rows": out})
	}
}

type createMediaRequest struct {
	Name       string  `json:"name"`
	NameEn     *string `json:"nameEn"`
	Scope      string  `json:"scope"`
	CustomerID *int64  `json:"customerId"`
}

func createMediaHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireMediaAdmin(c, deps, "media.create")
		if !ok {
			return
		}
		var req createMediaRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			badRequest(c, err)
			return
		}
		name, ok := validMediaName(req.Name, mediaNameMax)
		if !ok {
			apiError(c, http.StatusBadRequest, "invalid_media_name", "name must be 1-20 characters")
			return
		}
		nameEn, ok := mediaNameEn(req.NameEn)
		if !ok {
			apiError(c, http.StatusBadRequest, "invalid_media_name", "nameEn must be at most 40 characters")
			return
		}
		var customer pgtype.Int8
		switch {
		case req.Scope == mediaScopePublic && req.CustomerID == nil:
		case req.Scope == mediaScopeCustomer && req.CustomerID != nil:
			customer = pgtype.Int8{Int64: *req.CustomerID, Valid: true}
		default:
			apiError(c, http.StatusBadRequest, "invalid_media_scope", "scope must be public without customerId, or customer with customerId")
			return
		}

		ctx := c.Request.Context()
		var created db.Medium
		err := withTx(ctx, deps, func(q *db.Queries) error {
			var err error
			created, err = q.CreateMedia(ctx, db.CreateMediaParams{
				Name: name, NameEn: nameEn, Scope: req.Scope, CustomerID: customer,
				UpdatedBy: pgtype.Int8{Int64: actor.ID, Valid: true},
			})
			if err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "media.create", "", pgtype.Int8{}, "allowed",
				map[string]any{"mediaId": created.ID, "name": created.Name, "scope": created.Scope, "customerId": req.CustomerID})
		})
		if err != nil {
			writeMediaError(c, err)
			return
		}
		c.JSON(http.StatusCreated, toMediaResponse(created))
	}
}

// updateMediaRequest: absent fields stay as they are. Scope and owner cannot change: a medium already in
// use must not silently become another customer's asset.
type updateMediaRequest struct {
	Name    *string `json:"name"`
	NameEn  *string `json:"nameEn"`
	Enabled *bool   `json:"enabled"`
}

func updateMediaHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		actor, ok := requireMediaAdmin(c, deps, "media.update")
		if !ok {
			return
		}
		id, ok := parseIDParam(c)
		if !ok {
			return
		}
		var req updateMediaRequest
		if err := c.ShouldBindJSON(&req); err != nil {
			badRequest(c, err)
			return
		}
		errBuiltinName := errors.New("builtin medium cannot be renamed")
		errBadName := errors.New("invalid medium name")

		ctx := c.Request.Context()
		var updated db.Medium
		err := withTx(ctx, deps, func(q *db.Queries) error {
			cur, err := q.GetMediaForUpdate(ctx, id)
			if err != nil {
				return err
			}
			params := db.UpdateMediaParams{
				ID: id, Name: cur.Name, NameEn: cur.NameEn, Enabled: cur.Enabled,
				UpdatedBy: pgtype.Int8{Int64: actor.ID, Valid: true},
			}
			if req.Name != nil || req.NameEn != nil {
				if cur.BuiltinIndex.Valid {
					return errBuiltinName
				}
			}
			if req.Name != nil {
				v, ok := validMediaName(*req.Name, mediaNameMax)
				if !ok {
					return errBadName
				}
				params.Name = v
			}
			if req.NameEn != nil {
				v, ok := mediaNameEn(req.NameEn)
				if !ok {
					return errBadName
				}
				params.NameEn = v
			}
			if req.Enabled != nil {
				params.Enabled = *req.Enabled
			}
			if updated, err = q.UpdateMedia(ctx, params); err != nil {
				return err
			}
			return auditAdmin(ctx, q, actor, "media.update", "", pgtype.Int8{}, "allowed",
				map[string]any{"mediaId": id, "from": cur.Name, "to": updated.Name, "enabled": updated.Enabled})
		})
		switch {
		case err == nil:
			c.JSON(http.StatusOK, toMediaResponse(updated))
		case errors.Is(err, pgx.ErrNoRows):
			apiError(c, http.StatusNotFound, "media_not_found", "medium not found")
		case errors.Is(err, errBuiltinName):
			apiError(c, http.StatusConflict, "media_builtin_readonly", "a built-in medium cannot be renamed")
		case errors.Is(err, errBadName):
			apiError(c, http.StatusBadRequest, "invalid_media_name", "name must be 1-20 characters, nameEn at most 40")
		default:
			writeMediaError(c, err)
		}
	}
}

// deviceMediaOptionsHandler lists what may be selected for this device: the customer always comes from the
// device's current owner, never from the request.
func deviceMediaOptionsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		if _, ok := requireMediaAdmin(c, deps, "media.options"); !ok {
			return
		}
		ctx := c.Request.Context()
		device, err := deps.Queries.GetDevice(ctx, c.Param("device"))
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				apiError(c, http.StatusNotFound, "device_not_found", "device not found")
				return
			}
			internalError(c, err)
			return
		}
		rows, err := deps.Queries.ListMediaOptionsForCustomer(ctx, device.CustomerID)
		if err != nil {
			internalError(c, err)
			return
		}
		public, own := []mediaResponse{}, []mediaResponse{}
		for _, r := range rows {
			if r.Scope == mediaScopePublic {
				public = append(public, toMediaResponse(r))
			} else {
				own = append(own, toMediaResponse(r))
			}
		}
		synced, err := deviceSyncsMedia(ctx, deps.Queries, device.DeviceName)
		if err != nil {
			internalError(c, err)
			return
		}
		slots, err := deps.Queries.ListDeviceMediaSlotUsage(ctx, device.DeviceName)
		if err != nil {
			internalError(c, err)
			return
		}
		used := 0
		for _, s := range slots {
			if s.InUse {
				used++
			}
		}
		c.JSON(http.StatusOK, gin.H{"public": public, "customer": own, "customMediaSync": synced, "slotsUsed": used, "slotsTotal": mediaSlotLast - mediaSlotFirst + 1})
	}
}

const (
	mediaSlotFirst = 32
	mediaSlotLast  = 39
	// Firmware without name sync treats 18–25 as custom positions (#11), so these numbers mean something
	// else there and must not be sent blind.
	firstNewBuiltinIndex = 18
)

func deviceSyncsMedia(ctx context.Context, q *db.Queries, device string) (bool, error) {
	s, err := q.GetDeviceSettings(ctx, device)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return s.CustomMediaSync.Valid && s.CustomMediaSync.Int32 == 1, nil
}

type mediaRefusal struct {
	status     int
	code, text string
}

func (e *mediaRefusal) Error() string { return e.text }

// resolveMediaArgs turns the client's mediaId into what the device executes. Everything is decided here from
// the database, under the device row lock: the client's name, owner or number is never trusted, so
// oilTypeIndex and the name fields are refused as direct input. On false the response has been written.
func resolveMediaArgs(c *gin.Context, deps Deps, actor control.Actor, device string, raw json.RawMessage) (json.RawMessage, bool) {
	var fields map[string]json.RawMessage
	if json.Unmarshal(raw, &fields) != nil {
		return raw, true // ValidateArgs words this one
	}
	mediaRaw, hasMedia := fields[protocol.ArgMediaID]
	_, direct := fields[protocol.AttrOilTypeIndex]
	_, name := fields[protocol.AttrCustomOilName]
	_, nameEn := fields[protocol.AttrCustomOilNameEn]
	if !hasMedia && !direct && !name && !nameEn {
		return raw, true
	}
	if actor.Role != control.RolePlatformAdmin {
		// Leave the verdict to control so the device check, its order and the audit stay in one place.
		delete(fields, protocol.ArgMediaID)
		fields[protocol.AttrOilTypeIndex] = json.RawMessage("0")
		out, err := json.Marshal(fields)
		if err != nil {
			internalError(c, err)
			return nil, false
		}
		return out, true
	}
	if direct || name || nameEn {
		apiError(c, http.StatusBadRequest, "media_id_required", "the medium is selected by mediaId")
		return nil, false
	}
	var mediaID int64
	if json.Unmarshal(mediaRaw, &mediaID) != nil || mediaID <= 0 {
		apiError(c, http.StatusBadRequest, "media_not_selectable", "mediaId must be a positive integer")
		return nil, false
	}

	ctx := c.Request.Context()
	err := withTx(ctx, deps, func(q *db.Queries) error {
		dev, err := q.GetDeviceForUpdate(ctx, device)
		if err != nil {
			return err
		}
		m, err := q.GetMedia(ctx, mediaID)
		if errors.Is(err, pgx.ErrNoRows) {
			return &mediaRefusal{http.StatusBadRequest, "media_not_selectable", "medium not found"}
		}
		if err != nil {
			return err
		}
		if !m.Enabled || (m.Scope == mediaScopeCustomer && (!dev.CustomerID.Valid || m.CustomerID.Int64 != dev.CustomerID.Int64)) {
			return &mediaRefusal{http.StatusBadRequest, "media_not_selectable", "medium is not available for this device"}
		}
		synced, err := deviceSyncsMedia(ctx, q, device)
		if err != nil {
			return err
		}
		delete(fields, protocol.ArgMediaID)
		unsupported := &mediaRefusal{http.StatusConflict, "media_sync_unsupported", "this device firmware cannot receive this medium"}
		if m.BuiltinIndex.Valid {
			if !synced && m.BuiltinIndex.Int16 >= firstNewBuiltinIndex {
				return unsupported
			}
			fields[protocol.AttrOilTypeIndex] = json.RawMessage(strconv.Itoa(int(m.BuiltinIndex.Int16)))
			return nil
		}
		if !synced {
			return unsupported
		}
		slot, err := deviceMediaSlot(ctx, q, device, m.ID)
		if err != nil {
			return err
		}
		fields[protocol.AttrOilTypeIndex] = json.RawMessage(strconv.Itoa(int(slot)))
		en := m.Name
		if m.NameEn.Valid {
			en = m.NameEn.String
		}
		if fields[protocol.AttrCustomOilName], err = json.Marshal(m.Name); err != nil {
			return err
		}
		fields[protocol.AttrCustomOilNameEn], err = json.Marshal(en)
		return err
	})
	var refusal *mediaRefusal
	switch {
	case err == nil:
	case errors.As(err, &refusal):
		apiError(c, refusal.status, refusal.code, refusal.text)
		return nil, false
	case errors.Is(err, pgx.ErrNoRows):
		apiError(c, http.StatusNotFound, "device_not_found", "device not found")
		return nil, false
	default:
		internalError(c, err)
		return nil, false
	}
	out, err := json.Marshal(fields)
	if err != nil {
		internalError(c, err)
		return nil, false
	}
	return out, true
}

// deviceMediaSlot returns the device position of a medium, taking a free one if it has none. When all
// eight are mapped, one no tank uses and no unsettled command points at is handed over; a position in use
// is never reassigned, so another tank's name cannot be overwritten. The caller holds the device row lock.
func deviceMediaSlot(ctx context.Context, q *db.Queries, device string, mediaID int64) (int16, error) {
	slots, err := q.ListDeviceMediaSlotUsage(ctx, device)
	if err != nil {
		return 0, err
	}
	mapped := map[int16]bool{}
	for _, s := range slots {
		if s.MediaID == mediaID {
			return s.Slot, nil
		}
		mapped[s.Slot] = true
	}
	take := func(slot int16) (int16, error) {
		return slot, q.CreateDeviceMediaSlot(ctx, db.CreateDeviceMediaSlotParams{DeviceName: device, Slot: slot, MediaID: mediaID})
	}
	for slot := int16(mediaSlotFirst); slot <= mediaSlotLast; slot++ {
		if !mapped[slot] {
			return take(slot)
		}
	}
	for _, s := range slots {
		if !s.InUse {
			if err := q.DeleteDeviceMediaSlot(ctx, db.DeleteDeviceMediaSlotParams{DeviceName: device, Slot: s.Slot}); err != nil {
				return 0, err
			}
			return take(s.Slot)
		}
	}
	return 0, &mediaRefusal{http.StatusConflict, "media_slots_exhausted", "all 8 custom medium positions on this device are in use"}
}
