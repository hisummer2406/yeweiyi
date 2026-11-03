package api

import (
	"encoding/json"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// GET /api/shift-records and GET /api/stock-records — 交班 and 库存 records the display already keeps,
// served from the business database (cloud#85, docs/shift-stock-records-contract.md §4).
//
// One row per (record, tank), so per-tank authorization, the optional `tank` filter and paging all
// happen in SQL before LIMIT/OFFSET, the same rules as /api/oil-in-out (#84): an employee sees only the
// tanks granted to them, a named tank they may not see is a 403, never an empty page.
//
// A missing reading is null, never 0. An open shift has no end figures; a tank added mid-shift has no
// begin figures; older firmware reports no oilTypeIndex.
//
// `syncStatus` separates "no records" from "cannot know": UNSUPPORTED (this device has never reported
// this kind of record — older firmware), SYNCING (the device's initial takeover from ThingsBoard has
// not completed), SYNCED. A client may only say "no records" for SYNCED with empty rows.

const (
	syncUnsupported = "UNSUPPORTED"
	syncSyncing     = "SYNCING"
	syncSynced      = "SYNCED"
)

type shiftRecordRow struct {
	ShiftID      string     `json:"shiftId"`
	Status       string     `json:"status"`
	BeginTime    time.Time  `json:"beginTime"` // 接班时间
	EndTime      *time.Time `json:"endTime"`   // 交班时间；进行中为 null
	Tank         int        `json:"tank"`
	OilType      *string    `json:"oilType"`
	OilTypeIndex *int       `json:"oilTypeIndex"`
	BeginLevelMm *float64   `json:"beginLevelMm"`
	BeginVolumeL *float64   `json:"beginVolumeL"`
	EndLevelMm   *float64   `json:"endLevelMm"`
	EndVolumeL   *float64   `json:"endVolumeL"`
	OutVolumeL   *float64   `json:"outVolumeL"`
	OutWeightKg  *float64   `json:"outWeightKg"`
}

type stockRecordRow struct {
	RecordID     string    `json:"recordId"`
	Time         time.Time `json:"time"` // 库存生成时间
	Trigger      *string   `json:"trigger"`
	Tank         int       `json:"tank"`
	OilType      *string   `json:"oilType"`
	OilTypeIndex *int      `json:"oilTypeIndex"`
	LevelMm      *float64  `json:"levelMm"`
	VolumeL      *float64  `json:"volumeL"`
	WaterLevelMm *float64  `json:"waterLevelMm"`
	TempC        *float64  `json:"tempC"`
	WeightKg     *float64  `json:"weightKg"`
}

type recordListResponse[T any] struct {
	Rows       []T    `json:"rows"`
	Truncated  bool   `json:"truncated"`
	Partial    bool   `json:"partial"`
	Unparsable int    `json:"unparsable"`
	SyncStatus string `json:"syncStatus"`
}

func nonEmpty(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func listShiftRecordsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		p, ok := parseListParams(c)
		if !ok {
			return
		}
		begin, end, ok := recordWindow(c, deps, p)
		if !ok || !requireListTank(c, deps, p) {
			return
		}
		rows, err := deps.Queries.ListShiftRecordTanks(c.Request.Context(), db.ListShiftRecordTanksParams{
			DeviceName: p.device, BeginTime: begin, EndTime: end,
			UserID: c.MustGet("userID").(int64), Tank: p.tank, Asc: p.asc,
			Lim: p.limit + 1, Off: p.offset,
		})
		if err != nil {
			internalError(c, err)
			return
		}
		truncated := len(rows) > int(p.limit)
		if truncated {
			rows = rows[:p.limit]
		}
		out := make([]shiftRecordRow, 0, len(rows))
		for _, r := range rows {
			var t protocol.ShiftTank
			if json.Unmarshal(r.Data, &t) != nil {
				// Written only after a successful parse; the parser got stricter since. Leave the row out
				// rather than fail the page.
				continue
			}
			row := shiftRecordRow{
				ShiftID: r.ShiftID, Status: r.Status, BeginTime: r.BeginAt.Time.UTC(), Tank: int(r.Tank),
				OilType: nonEmpty(t.OilType), OilTypeIndex: t.OilTypeIndex,
				BeginLevelMm: t.BeginLevelMm, BeginVolumeL: t.BeginVolumeL,
				EndLevelMm: t.EndLevelMm, EndVolumeL: t.EndVolumeL,
				OutVolumeL: t.OutVolumeL, OutWeightKg: t.OutWeightKg,
			}
			if r.EndAt.Valid {
				e := r.EndAt.Time.UTC()
				row.EndTime = &e
			}
			out = append(out, row)
		}
		respondRecordList(c, deps, p, "shift", begin, end, out, truncated)
	}
}

func listStockRecordsHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		p, ok := parseListParams(c)
		if !ok {
			return
		}
		begin, end, ok := recordWindow(c, deps, p)
		if !ok || !requireListTank(c, deps, p) {
			return
		}
		rows, err := deps.Queries.ListStockRecordTanks(c.Request.Context(), db.ListStockRecordTanksParams{
			DeviceName: p.device, BeginTime: begin, EndTime: end,
			UserID: c.MustGet("userID").(int64), Tank: p.tank, Asc: p.asc,
			Lim: p.limit + 1, Off: p.offset,
		})
		if err != nil {
			internalError(c, err)
			return
		}
		truncated := len(rows) > int(p.limit)
		if truncated {
			rows = rows[:p.limit]
		}
		out := make([]stockRecordRow, 0, len(rows))
		for _, r := range rows {
			var t protocol.StockRecordTank
			if json.Unmarshal(r.Data, &t) != nil {
				continue
			}
			out = append(out, stockRecordRow{
				RecordID: r.RecordID, Time: r.OccurredAt.Time.UTC(), Trigger: nonEmpty(r.Trigger), Tank: int(r.Tank),
				OilType: nonEmpty(t.OilType), OilTypeIndex: t.OilTypeIndex,
				LevelMm: t.LevelMm, VolumeL: t.VolumeL, WaterLevelMm: t.WaterLevelMm, TempC: t.TempC, WeightKg: t.WeightKg,
			})
		}
		respondRecordList(c, deps, p, "stock_record", begin, end, out, truncated)
	}
}

// respondRecordList adds the completeness fields shared by both endpoints. kind is the
// record_rejects kind.
func respondRecordList[T any](c *gin.Context, deps Deps, p listParams, kind string, begin, end pgtype.Timestamptz, rows []T, truncated bool) {
	ctx := c.Request.Context()
	reported, err := deps.Queries.RecordKindReported(ctx, db.RecordKindReportedParams{Kind: kind, DeviceName: p.device})
	if err != nil {
		internalError(c, err)
		return
	}
	backfilled, err := recordsBackfilled(c, deps, p.device)
	if err != nil {
		internalError(c, err)
		return
	}
	// Unreadable originals cannot be attributed to a tank; like /api/oil-in-out's `unparsable` they are a
	// device-wide count, so even an employee learns the list may be incomplete (but never sees the text).
	unparsable, err := deps.Queries.CountRecordRejects(ctx, db.CountRecordRejectsParams{
		Kind: kind, DeviceName: p.device, BeginMs: begin.Time.UnixMilli(), EndMs: end.Time.UnixMilli(),
	})
	if err != nil {
		internalError(c, err)
		return
	}
	status := syncSynced
	switch {
	case !reported:
		status = syncUnsupported
	case !backfilled:
		status = syncSyncing
	}
	c.JSON(200, recordListResponse[T]{
		Rows: rows, Truncated: truncated, Partial: !backfilled, Unparsable: int(unparsable), SyncStatus: status,
	})
}
