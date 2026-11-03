package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"
	"unicode/utf8"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgtype"

	"yeweiyi-cloud/internal/protocol"
	"yeweiyi-cloud/internal/store/db"
)

// GET /api/oil-in-out — 出入库 (stock in/out) history, served from the business database.
//
// **Source of truth.** Stock movements are business records: the device keeps them locally and never
// deletes them on its own, so the cloud must hold them just as long (CLAUDE.md constraint 5, issue
// #29). internal/ingest writes each `stockEvent` it sees — live, and by periodic and one-off
// backfill from ThingsBoard — into `stock_events`, and this reads it back from there. ThingsBoard is
// transport and short-term buffer only; deleting its copy (TTL, manual cleanup) changes nothing here.
// This endpoint used to read through to ThingsBoard, which is why a 7-day TTL could not be enabled.
//
// What survived the move, unchanged, because the mini-program contract must not move again:
//
//   - the response is always an object `{rows, parsed, unparsable, legacySkipped, …}`;
//   - a record that cannot be read is counted, never silently dropped, and past
//     `stock.legacyFormatBefore` it makes the response a 502 (details below);
//   - a movement is listed in the window containing its close time (`tsMs`), exactly once, and a
//     corrected re-report under the same eventId replaces the original (folding is done at write
//     time, see db/queries/business_records.sql UpsertStockEvent).
//
// What changed: `offset` is accepted, and `partial` no longer means "a ThingsBoard scan cap hid a
// correction" (a table has no such cap) but "the initial backfill has not completed". `folded` still
// reports how many re-reports were superseded.
//
// **Why an unreadable record is a 502, deliberately.** The original behaviour was to skip unparseable
// points and serve 200 with whatever was left. For a device on the pre-V1 firmware that means 200 and
// `[]` — an empty stock-movement history, which is a well-formed, confident, *wrong* answer to "what
// oil moved through this tank?". 出入库 records are precisely what gets argued over with suppliers.
// So an unreadable record past the cutoff is a 502 (Bad Gateway: the caller did nothing wrong and
// this backend works; the upstream device's payload cannot be interpreted). `code` is stable so a
// client can say 设备固件尚未升级 instead of a generic error.
//
// A flat "unparseable ⇒ 502" is only right until the fleet is upgraded: the device-side fix converts
// nothing already stored, so any window reaching back past the upgrade would answer 502 forever.
// `stock.legacyFormatBefore` is the instant from which every device is expected to publish V1 JSON;
// the payloads of the two cases are byte-identical, so only time can tell them apart:
//
//	unparseable, before the cutoff   a closed data hole. Counted as `legacySkipped`, still 200.
//	unparseable, at or after it      a device that was not upgraded. 502 — somebody has to look.
//
// Unset means "the upgrade has not happened": every unreadable record counts as current breakage.
// The classification uses the envelope ts the device stamped when it generated the record, which
// replay preserves (docs/protocol-v1-stock-event.md §3). The unreadable originals themselves are
// kept in `record_rejects`, so a later decision to back-fill them needs no ThingsBoard.
//
// **The response is always an object with a `rows` array**, on both paths: a bare array cannot carry
// `legacySkipped`, and `{"rows": [...]}` is what the mini-program already expects here
// (yeweiyi-miniapp `miniprogram/pages/device-detail/device-detail.ts:98`) and this repo's house shape
// for list endpoints. The *rows themselves* remain incompatible with that client for reasons that
// predate this change — see docs/protocol-v1-open-items.md §D.

// StockEventFormatUnsupported is the stable `code` on the degraded response. Clients should match on
// this rather than on the message, which is prose and will be translated.
const StockEventFormatUnsupported = "STOCK_EVENT_FORMAT_UNSUPPORTED"

// unparsableSampleRunes bounds the echoed sample. Long enough to recognise
// `DetailInoutRecord{oilcanId=1, ...}` and to paste into a bug report, short enough that a
// pathological value cannot make the error response the largest thing on the wire.
const unparsableSampleRunes = 200

type stockEventResponse struct {
	EventID   string `json:"eventId"`
	Tank      int    `json:"tank"`
	Direction string `json:"direction"`
	OilType   string `json:"oilType,omitempty"`
	// Source is "MANUAL" for a BuLuFragment backfill, "AUTO" for InOilManager's automatic detection —
	// normalised here (unlike protocol.StockEvent.Source) so API consumers never have to special-case
	// an absent field as "must be automatic": see protocol.SourceAuto/SourceManual.
	Source string `json:"source"`

	BeginTime time.Time `json:"beginTime"`
	EndTime   time.Time `json:"endTime"`

	// The readings are null when the record carries no such key (a manual entry only knows the level
	// before and the volume moved), never 0: a zero would read as a measured one (cloud#69, #15).
	BeginLevelMm  *float64 `json:"beginLevelMm"`
	EndLevelMm    *float64 `json:"endLevelMm"`
	BeginVolumeL  *float64 `json:"beginVolumeL"`
	EndVolumeL    *float64 `json:"endVolumeL"`
	BeginTempC    *float64 `json:"beginTempC"`
	EndTempC      *float64 `json:"endTempC"`
	BeginWeightKg *float64 `json:"beginWeightKg"`
	EndWeightKg   *float64 `json:"endWeightKg"`

	NetVolumeL            float64 `json:"netVolumeL"`
	DuringDeliveryVolumeL float64 `json:"duringDeliveryVolumeL,omitempty"`
	// DeliveredVolumeL is what actually moved: for an inbound delivery the tank's net gain plus
	// whatever the pumps dispensed during it. Computed here rather than left to every client,
	// because getting it wrong understates deliveries and that is the number people argue with
	// suppliers about.
	DeliveredVolumeL float64 `json:"deliveredVolumeL"`
	// DeliveredWeightKg is the device's own figure (delivered volume × density when the record
	// closed); null on manual entries and older firmware. Not derived here: the cloud has no density
	// history, and begin/end weights include what was sold during a delivery (cloud#85).
	DeliveredWeightKg *float64 `json:"deliveredWeightKg"`
	// OilTypeIndex identifies the oil independently of the display language OilType was rendered in;
	// null when the device did not report it.
	OilTypeIndex *int `json:"oilTypeIndex"`

	Time time.Time `json:"time"` // when the device closed the record

	// Origin is "device" for what the device reported (including its own BuLuFragment backfill) and
	// "cloud" for an entry made through POST /api/devices/:device/stock-events. Only "cloud" rows can
	// be withdrawn (DELETE .../stock-events/:eventId).
	Origin string `json:"origin"`
}

// stockListResponse is the body on both paths. `rows` is always present and always an array — a
// client that renders the list before checking the status code shows the truthful partial list
// rather than crashing on a missing field — and the counts are always present too, so a caller that
// ignores the status code still cannot mistake an incomplete answer for a complete one.
type stockListResponse struct {
	Rows   []stockEventResponse `json:"rows"`
	Parsed int                  `json:"parsed"`
	// Unparsable counts records this deployment expects to be readable and is not. Non-zero means
	// HTTP 502.
	Unparsable int `json:"unparsable"`
	// LegacySkipped counts records from before stock.legacyFormatBefore: a closed, known hole in
	// the history rather than a fault. Reported rather than hidden, because the operator
	// reconciling this window still needs to know the numbers are incomplete.
	LegacySkipped int `json:"legacySkipped"`
	// InvalidSource counts in-window records that parsed but carry a `source` other than
	// AUTO/MANUAL. They are left out of rows rather than failing the window: one off-protocol tag
	// should not hide every other movement. Not a 502, but reported so the list is not mistaken for
	// complete.
	InvalidSource int `json:"invalidSource"`
	// Folded counts superseded re-reports of an eventId already in the list (see foldByEventID).
	// Not a fault; reported so a row count that differs from the raw telemetry count is explicable.
	Folded int `json:"folded"`
	// Truncated is true when more movements matched the window than `limit` allowed. Rows are
	// ordered by `time` in the requested direction and cut from the far end.
	Truncated bool `json:"truncated"`
	// Partial is true while the device's initial takeover from ThingsBoard has not completed
	// (record_backfills): the stored history may then be only what arrived live. It is a different
	// cause from the old ThingsBoard scan cap, but the same meaning for the reader — "this list is
	// not known to be whole".
	Partial bool `json:"partial"`

	// Only on the 502 path.
	Error  string `json:"error,omitempty"`
	Code   string `json:"code,omitempty"`
	Device string `json:"device,omitempty"`
	Sample string `json:"sample,omitempty"`
	// LegacyCutoff echoes the configured boundary so an operator seeing an unexpected 502 can tell
	// at a glance whether it is unset (the common cause) without reading the deployment's config.
	LegacyCutoff string `json:"legacyCutoff,omitempty"`
}

// optional returns v when the stored report carries key, nil when it does not. present == nil means the
// stored text could not be inspected, in which case every value is shown as it decoded.
func optional(present map[string]bool, key string, v float64) *float64 {
	if present != nil && !present[key] {
		return nil
	}
	return &v
}

func toStockEventResponse(e protocol.StockEvent, present map[string]bool, origin string) stockEventResponse {
	delivered := e.NetVolumeL + e.DuringDeliveryVolumeL
	if e.Direction == protocol.StockOut {
		// Outbound: the tank's change is negative and there is no concurrent-delivery adjustment,
		// so the quantity moved is the magnitude of the drop.
		delivered = -e.NetVolumeL
	}

	source := e.Source
	if source == "" {
		source = protocol.SourceAuto
	}

	return stockEventResponse{
		EventID:               e.EventID,
		Tank:                  e.Tank,
		Direction:             e.Direction,
		OilType:               e.OilType,
		Source:                source,
		BeginTime:             time.UnixMilli(e.BeginTimeMs).UTC(),
		EndTime:               time.UnixMilli(e.EndTimeMs).UTC(),
		BeginLevelMm:          optional(present, "beginLevelMm", e.BeginLevelMm),
		EndLevelMm:            optional(present, "endLevelMm", e.EndLevelMm),
		BeginVolumeL:          optional(present, "beginVolumeL", e.BeginVolumeL),
		EndVolumeL:            optional(present, "endVolumeL", e.EndVolumeL),
		BeginTempC:            optional(present, "beginTempC", e.BeginTempC),
		EndTempC:              optional(present, "endTempC", e.EndTempC),
		BeginWeightKg:         optional(present, "beginWeightKg", e.BeginWeightKg),
		EndWeightKg:           optional(present, "endWeightKg", e.EndWeightKg),
		NetVolumeL:            e.NetVolumeL,
		DuringDeliveryVolumeL: e.DuringDeliveryVolumeL,
		DeliveredVolumeL:      delivered,
		DeliveredWeightKg:     e.DeliveredWeightKg,
		OilTypeIndex:          e.OilTypeIndex,
		Time:                  time.UnixMilli(e.TSMs).UTC(),
		Origin:                origin,
	}
}

// stockResponseFrom rebuilds the API row from the stored report. The payload is the device's own
// JSON kept verbatim, so it is parsed again here rather than copied into columns that would have to
// follow every future protocol field.
func stockResponseFrom(payload []byte, synthetic bool, occurred time.Time, origin string) (stockEventResponse, bool) {
	ev, err := protocol.ParseStockEvent(payload)
	if err != nil {
		// Written only after a successful parse, so this means the parser got stricter since. Leave
		// the row out of the list rather than fail the window, and let the caller count it.
		return stockEventResponse{}, false
	}
	if ev.TSMs == 0 {
		ev.TSMs = occurred.UnixMilli()
	}
	if synthetic {
		// The id was minted by the cloud to keep a replay idempotent; it is not the device's and
		// must not be presented as one.
		ev.EventID = ""
	}
	var keys map[string]json.RawMessage
	var present map[string]bool
	if json.Unmarshal(payload, &keys) == nil {
		present = make(map[string]bool, len(keys))
		for k, v := range keys {
			// An explicit null is as absent as a missing key.
			present[k] = string(v) != "null"
		}
	}
	return toStockEventResponse(ev, present, origin), true
}

func listOilInOutHandler(deps Deps) gin.HandlerFunc {
	return func(c *gin.Context) {
		p, ok := parseListParams(c)
		if !ok {
			return
		}
		// Optional direction filter (#84/#85: the 入库 and 出库 pages are separate), applied in SQL like the
		// tank filter so a page of the other direction cannot read as "no records".
		var direction pgtype.Text
		switch v := c.Query("direction"); v {
		case "":
		case protocol.StockIn, protocol.StockOut:
			direction = pgtype.Text{String: v, Valid: true}
		default:
			apiError(c, http.StatusBadRequest, "invalid_direction", "direction must be IN or OUT")
			return
		}
		begin, end, ok := recordWindow(c, deps, p)
		if !ok || !requireListTank(c, deps, p) {
			return
		}
		ctx := c.Request.Context()

		cutoffMs := int64(0)
		if !deps.StockLegacyFormatBefore.IsZero() {
			cutoffMs = deps.StockLegacyFormatBefore.UnixMilli()
		}

		args := db.ListStockEventsAscParams{
			DeviceName: p.device, BeginTime: begin, EndTime: end,
			Lim: p.limit + 1, Off: p.offset,
			UserID: c.MustGet("userID").(int64), Tank: p.tank, Direction: direction,
		}
		var stored []stockRowData
		if p.asc {
			rows, err := deps.Queries.ListStockEventsAsc(ctx, args)
			if err != nil {
				internalError(c, err)
				return
			}
			for _, r := range rows {
				stored = append(stored, stockRowData{r.Payload, r.SyntheticID, r.OccurredAt.Time, int(r.ReportCount), r.Origin})
			}
		} else {
			rows, err := deps.Queries.ListStockEventsDesc(ctx, db.ListStockEventsDescParams(args))
			if err != nil {
				internalError(c, err)
				return
			}
			for _, r := range rows {
				stored = append(stored, stockRowData{r.Payload, r.SyntheticID, r.OccurredAt.Time, int(r.ReportCount), r.Origin})
			}
		}

		truncated := len(stored) > int(p.limit)
		if truncated {
			stored = stored[:p.limit]
		}
		out := make([]stockEventResponse, 0, len(stored))
		folded := 0
		for _, r := range stored {
			resp, ok := stockResponseFrom(r.payload, r.synthetic, r.occurred, r.origin)
			if !ok {
				continue
			}
			out = append(out, resp)
			folded += r.reports - 1
		}

		// Unreadable records are classified by the envelope ts they arrived under, not by the window
		// of the movements above: the payload is not decodable, so there is no close time to use.
		// With a tank filter, invalidSource counts that tank only; unparsable cannot be attributed to a
		// tank or a direction and stays a device-wide count (docs/records-filter-contract.md).
		summary, err := deps.Queries.SummarizeStockRejects(ctx, db.SummarizeStockRejectsParams{
			DeviceName: p.device, BeginMs: begin.Time.UnixMilli(), EndMs: end.Time.UnixMilli(), CutoffMs: cutoffMs,
			UserID: c.MustGet("userID").(int64), Tank: p.tank,
		})
		if err != nil {
			internalError(c, err)
			return
		}
		if isEmployee(c) {
			// An unreadable record has no decodable tank, so it cannot be attributed to a tank the
			// employee was granted. Its *existence* is a device-wide format fault and is still
			// reported — otherwise a window of unreadable records would read as a successful empty
			// list, the failure this endpoint exists to prevent — but its raw text may describe
			// another tank, so the sample is withheld (cloud#63).
			summary.Sample = ""
		}

		backfilled, err := recordsBackfilled(c, deps, p.device)
		if err != nil {
			internalError(c, err)
			return
		}
		body := stockListResponse{
			Partial:       !backfilled,
			Rows:          out,
			Parsed:        len(out),
			Unparsable:    int(summary.Unparsable),
			LegacySkipped: int(summary.Legacy),
			InvalidSource: int(summary.InvalidSource),
			Folded:        folded,
			Truncated:     truncated,
		}

		if body.Unparsable > 0 {
			body.Error = "该设备上报的出入库记录中有 " + strconv.Itoa(body.Unparsable) +
				" 条无法解析：设备固件仍以 DetailInoutRecord.toString() 上报，" +
				"而非 设备协议 V1 定义的 JSON（zhongchengyeweiyi#39）。" +
				"已解析的记录一并返回，但本时间窗内的出入库历史并不完整。"
			if cutoffMs == 0 {
				body.Error += "（本部署未配置 stock.legacyFormatBefore；若车队已完成固件升级，" +
					"设置该时间点可把升级前的旧记录计为 legacySkipped 而非故障。）"
			}
			body.Code = StockEventFormatUnsupported
			body.Device = p.device
			// One example is enough to identify the format; echoing every one would make the error
			// response the largest thing on the wire.
			body.Sample = truncateRunes(summary.Sample, unparsableSampleRunes)
			if cutoffMs > 0 {
				body.LegacyCutoff = deps.StockLegacyFormatBefore.UTC().Format(time.RFC3339)
			}
			c.JSON(http.StatusBadGateway, body)
			return
		}

		// 200 even with legacySkipped > 0: those records are a closed hole, not a fault, and there
		// is no action for anyone to take. The count is in the body precisely so that "200" does
		// not get read as "this list is complete".
		c.JSON(http.StatusOK, body)
	}
}

// stockRowData is one stored report, shaped identically for both sort directions.
type stockRowData struct {
	payload   []byte
	synthetic bool
	occurred  time.Time
	reports   int
	origin    string
}

// truncateRunes cuts on a rune boundary, not a byte one: the device's payloads carry Chinese oil
// type names, and a byte-sliced sample would put an invalid UTF-8 fragment into a JSON response.
func truncateRunes(s string, max int) string {
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	n := 0
	for i := range s {
		if n == max {
			return s[:i] + "…"
		}
		n++
	}
	return s
}
