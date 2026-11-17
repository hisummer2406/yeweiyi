package api_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"yeweiyi-cloud/internal/control"
	"yeweiyi-cloud/internal/protocol"
)

type mediaRow struct {
	ID           int64   `json:"id"`
	Name         string  `json:"name"`
	NameEn       *string `json:"nameEn"`
	Scope        string  `json:"scope"`
	CustomerID   *int64  `json:"customerId"`
	BuiltinIndex *int16  `json:"builtinIndex"`
	Enabled      bool    `json:"enabled"`
}

type mediaOptions struct {
	Public       []mediaRow `json:"public"`
	Customer     []mediaRow `json:"customer"`
	OilColorSync bool       `json:"oilColorSync"`
}

func mediaName(label string) string {
	return fmt.Sprintf("%s%d", label, time.Now().UnixNano()%1_000_000_000_000)
}

func (tn *tenancy) createMedia(t *testing.T, body map[string]any) mediaRow {
	t.Helper()
	rec := tn.send(t, "POST", "/api/media", tn.platform, body)
	if rec.Code != http.StatusCreated {
		t.Fatalf("create medium: %d %s", rec.Code, rec.Body.String())
	}
	m := decodeAs[mediaRow](t, rec)
	t.Cleanup(func() {
		ctx := context.Background()
		tn.env.pool.Exec(ctx, `DELETE FROM device_media_slots WHERE media_id = $1`, m.ID)
		tn.env.pool.Exec(ctx, `DELETE FROM media WHERE id = $1`, m.ID)
	})
	return m
}

func names(rows []mediaRow) map[string]bool {
	out := map[string]bool{}
	for _, r := range rows {
		out[r.Name] = true
	}
	return out
}

func TestMedia_KeroseneMigrationPreservesCustomMedia(t *testing.T) {
	up, err := os.ReadFile("../../db/migrations/000042_builtin_kerosene.up.sql")
	if err != nil {
		t.Fatal(err)
	}
	down, err := os.ReadFile("../../db/migrations/000042_builtin_kerosene.down.sql")
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		name, seed, downSeed, upName, downName string
	}{
		{name: "no conflict", upName: "煤油", downName: "米糠油"},
		{
			name:     "disabled public names conflict",
			seed:     `INSERT INTO media (id, name, scope, enabled) VALUES (101, '煤油', 'public', false);`,
			downSeed: `INSERT INTO media (id, name, scope, enabled) VALUES (201, '米糠油', 'public', false);`,
			upName:   "煤油（内置）", downName: "米糠油（内置）",
		},
		{
			name:     "suffix also occupied",
			seed:     `INSERT INTO media (id, name, scope) VALUES (101, '煤油', 'public'), (102, '煤油（内置）', 'public');`,
			downSeed: `INSERT INTO media (id, name, scope) VALUES (201, '米糠油', 'public'), (202, '米糠油（内置）', 'public');`,
			upName:   "煤油（内置2）", downName: "米糠油（内置2）",
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			conn, err := pgx.Connect(ctx, testDatabaseURL())
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close(context.Background())
			exec := func(sql string) {
				t.Helper()
				if _, err := conn.Exec(ctx, sql); err != nil {
					t.Fatal(err)
				}
			}
			// 临时表复制真实约束，迁移只作用于当前连接，不修改共享测试数据。
			exec(`CREATE TEMP TABLE media (LIKE public.media INCLUDING ALL);
CREATE TEMP TABLE device_media_slots (LIKE public.device_media_slots INCLUDING ALL);
INSERT INTO media (id, builtin_index, name, name_en, scope) VALUES (10, 10, '米糠油', 'Rice Bran Oil', 'public');
INSERT INTO media (id, name, scope) VALUES (103, '保留介质', 'public'), (104, '保留介质2', 'public');
INSERT INTO device_media_slots (device_name, slot, media_id) VALUES ('migration-test', 32, 103), ('migration-test', 39, 104);`)
			exec(tc.seed)
			customSnapshot := func() string {
				t.Helper()
				var snapshot string
				if err := conn.QueryRow(ctx, `SELECT jsonb_build_object(
'media', (SELECT jsonb_agg(to_jsonb(m) ORDER BY id) FROM media m WHERE builtin_index IS NULL),
'slots', (SELECT jsonb_agg(to_jsonb(s) ORDER BY slot) FROM device_media_slots s))::text`).Scan(&snapshot); err != nil {
					t.Fatal(err)
				}
				return snapshot
			}
			for _, step := range []struct{ sql, name, english string }{
				{string(up), tc.upName, "Kerosene"},
				{string(down), tc.downName, "Rice Bran Oil"},
			} {
				if step.english == "Rice Bran Oil" {
					exec(tc.downSeed)
				}
				before := customSnapshot()
				exec(step.sql)
				var id int64
				var name, english string
				if err := conn.QueryRow(ctx, `SELECT id, name, name_en FROM media WHERE builtin_index = 10`).Scan(&id, &name, &english); err != nil {
					t.Fatal(err)
				}
				if id != 10 || name != step.name || english != step.english {
					t.Fatalf("builtin = %d %q %q, want 10 %q %q", id, name, english, step.name, step.english)
				}
				if customSnapshot() != before {
					t.Fatal("migration changed custom media or slot mappings")
				}
			}
		})
	}
}

func TestMedia_PlatformAdminOnly(t *testing.T) {
	tn := newTenancy(t)
	for who, token := range map[string]string{"customer admin": tn.adminA, "customer user": tn.userA} {
		for _, r := range []struct {
			method, path string
			body         any
		}{
			{"GET", "/api/media", nil},
			{"POST", "/api/media", map[string]any{"name": "x", "scope": "public"}},
			{"PUT", "/api/media/1", map[string]any{"enabled": false}},
		} {
			if rec := tn.send(t, r.method, r.path, token, r.body); rec.Code != http.StatusForbidden {
				t.Errorf("%s %s %s: %d, want 403", who, r.method, r.path, rec.Code)
			}
		}
	}
}

func TestMedia_ManualEntryAndCustomerIsolation(t *testing.T) {
	tn := newTenancy(t)
	pub := tn.createMedia(t, map[string]any{"name": mediaName("公"), "scope": "public"})
	own := tn.createMedia(t, map[string]any{"name": mediaName("专"), "nameEn": "HCl", "scope": "customer", "customerId": tn.custA})

	a := decodeAs[mediaOptions](t, tn.send(t, "GET", "/api/devices/"+tn.devA1+"/media-options", tn.platform, nil))
	if !names(a.Public)[pub.Name] || !names(a.Public)["92#"] || !names(a.Customer)[own.Name] {
		t.Fatalf("customer A device options = %+v", a)
	}
	if !names(a.Public)["煤油"] || names(a.Public)["米糠油"] {
		t.Fatalf("builtin 10 must be kerosene after migration: %+v", a.Public)
	}
	if names(a.Public)["二厂盐酸"] {
		t.Error("二厂 media must stay unclassified until their owner is confirmed")
	}
	b := decodeAs[mediaOptions](t, tn.send(t, "GET", "/api/devices/"+tn.devB1+"/media-options", tn.platform, nil))
	if names(b.Customer)[own.Name] || !names(b.Public)[pub.Name] {
		t.Errorf("customer B device options = %+v", b)
	}
	none := decodeAs[mediaOptions](t, tn.send(t, "GET", "/api/devices/"+tn.devUnassigned+"/media-options", tn.platform, nil))
	if len(none.Customer) != 0 {
		t.Errorf("unassigned device got customer media: %+v", none.Customer)
	}

	// 停用后不可新选，但字典里仍在。
	rec := tn.send(t, "PUT", fmt.Sprintf("/api/media/%d", own.ID), tn.platform, map[string]any{"enabled": false})
	if rec.Code != http.StatusOK {
		t.Fatalf("disable: %d %s", rec.Code, rec.Body.String())
	}
	a = decodeAs[mediaOptions](t, tn.send(t, "GET", "/api/devices/"+tn.devA1+"/media-options", tn.platform, nil))
	if names(a.Customer)[own.Name] {
		t.Error("a disabled medium is still selectable")
	}
	if n := tn.auditCount(t, `action = 'media.create' AND detail->>'mediaId' = $1`, fmt.Sprint(own.ID)); n != 1 {
		t.Errorf("media.create audit rows = %d", n)
	}
}

func TestMedia_Validation(t *testing.T) {
	tn := newTenancy(t)
	name := mediaName("同")
	own := tn.createMedia(t, map[string]any{"name": name, "scope": "customer", "customerId": tn.custA})

	cases := []struct {
		body map[string]any
		code int
		want string
	}{
		{map[string]any{"name": "  ", "scope": "public"}, 400, "invalid_media_name"},
		{map[string]any{"name": "一二三四五六七八九十一二三四五六七八九十一", "scope": "public"}, 400, "invalid_media_name"},
		{map[string]any{"name": "x", "scope": "public", "customerId": tn.custA}, 400, "invalid_media_scope"},
		{map[string]any{"name": "x", "scope": "customer"}, 400, "invalid_media_scope"},
		{map[string]any{"name": "x", "scope": "nope"}, 400, "invalid_media_scope"},
		{map[string]any{"name": "x", "scope": "customer", "customerId": 999999999}, 400, "unknown_customer"},
		{map[string]any{"name": " " + name + " ", "scope": "customer", "customerId": tn.custA}, 409, "media_name_exists"},
	}
	for _, c := range cases {
		rec := tn.send(t, "POST", "/api/media", tn.platform, c.body)
		got := decodeAs[struct {
			Code string `json:"code"`
		}](t, rec)
		if rec.Code != c.code || got.Code != c.want {
			t.Errorf("%v: %d %s, want %d %s", c.body, rec.Code, got.Code, c.code, c.want)
		}
	}

	// 同名在别的客户下可以，且不泄露第一家的存在。
	tn.createMedia(t, map[string]any{"name": name, "scope": "customer", "customerId": tn.custB})

	// 客户归属不可改：请求里的 customerId 被忽略。
	rec := tn.send(t, "PUT", fmt.Sprintf("/api/media/%d", own.ID), tn.platform, map[string]any{"name": name + "新", "customerId": tn.custB})
	if got := decodeAs[mediaRow](t, rec); rec.Code != 200 || got.CustomerID == nil || *got.CustomerID != tn.custA {
		t.Errorf("update: %d %+v", rec.Code, got)
	}

	if rec := tn.send(t, "PUT", "/api/media/999999999", tn.platform, map[string]any{"enabled": false}); rec.Code != 404 {
		t.Errorf("missing medium: %d", rec.Code)
	}

	var builtin int64
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT id FROM media WHERE builtin_index = 5`).Scan(&builtin); err != nil {
		t.Fatal(err)
	}
	path := fmt.Sprintf("/api/media/%d", builtin)
	if rec := tn.send(t, "PUT", path, tn.platform, map[string]any{"name": "改名"}); rec.Code != http.StatusConflict {
		t.Errorf("rename built-in: %d %s", rec.Code, rec.Body.String())
	}
}

func TestMedia_CustomerWithMediaCannotBeDeleted(t *testing.T) {
	tn := newTenancy(t)
	tn.createMedia(t, map[string]any{"name": mediaName("删"), "scope": "customer", "customerId": tn.custA})
	if rec := tn.send(t, "DELETE", fmt.Sprintf("/api/customers/%d", tn.custA), tn.platform, nil); rec.Code != http.StatusConflict {
		t.Errorf("delete customer with media: %d %s", rec.Code, rec.Body.String())
	}
}

// --- 选用与下发 ---------------------------------------------------------------------------------

func selectMedium(t *testing.T, tn *tenancy, token, device string, tank int, mediaID int64) *httptest.ResponseRecorder {
	t.Helper()
	return tn.send(t, "POST", "/api/devices/"+device+"/commands", token, map[string]any{
		"method": protocol.MethodConfigTankSettings, "reason": "换介质", "requestId": fmt.Sprintf("m%d", time.Now().UnixNano()),
		"expectedStationId": nil, "args": map[string]any{"tank": tank, "mediaId": mediaID},
	})
}

func (tn *tenancy) markSynced(t *testing.T, device string) {
	t.Helper()
	if _, err := tn.env.pool.Exec(context.Background(),
		`INSERT INTO device_settings (device_name, custom_media_sync, reported_at) VALUES ($1, 1, now())`, device); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx := context.Background()
		tn.env.pool.Exec(ctx, `DELETE FROM device_media_slots WHERE device_name = $1`, device)
		tn.env.pool.Exec(ctx, `DELETE FROM device_settings WHERE device_name = $1`, device)
	})
}

func (tn *tenancy) lastArgs(t *testing.T, device string) map[string]any {
	t.Helper()
	var raw []byte
	if err := tn.env.pool.QueryRow(context.Background(),
		`SELECT args FROM control_commands WHERE device_name = $1 ORDER BY issued_at DESC LIMIT 1`, device).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func mediaCode(t *testing.T, rec *httptest.ResponseRecorder) string {
	t.Helper()
	return decodeAs[struct {
		Code string `json:"code"`
	}](t, rec).Code
}

func builtinID(t *testing.T, tn *tenancy, index int) int64 {
	t.Helper()
	var id int64
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT id FROM media WHERE builtin_index = $1`, index).Scan(&id); err != nil {
		t.Fatal(err)
	}
	return id
}

func TestMedia_SelectResolvesOnTheServer(t *testing.T) {
	tn := newTenancyWith(t, control.Config{AllowConfigCommands: true})
	tn.markSynced(t, tn.devA1)
	own := tn.createMedia(t, map[string]any{"name": mediaName("酸"), "nameEn": "HCl", "scope": "customer", "customerId": tn.custA})

	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 1, builtinID(t, tn, 10)); rec.Code != http.StatusAccepted {
		t.Fatalf("builtin: %d %s", rec.Code, rec.Body.String())
	}
	args := tn.lastArgs(t, tn.devA1)
	if args["oilTypeIndex"] != float64(10) || args["customOilName"] != nil || args["mediaId"] != nil {
		t.Errorf("builtin args = %v", args)
	}

	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 2, own.ID); rec.Code != http.StatusAccepted {
		t.Fatalf("custom: %d %s", rec.Code, rec.Body.String())
	}
	args = tn.lastArgs(t, tn.devA1)
	if args["oilTypeIndex"] != float64(32) || args["customOilName"] != own.Name || args["customOilNameEn"] != "HCl" {
		t.Errorf("custom args = %v", args)
	}

	// 同一介质用于另一个罐，位置不变。
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 3, own.ID); rec.Code != http.StatusAccepted {
		t.Fatalf("same medium: %d %s", rec.Code, rec.Body.String())
	}
	if args = tn.lastArgs(t, tn.devA1); args["oilTypeIndex"] != float64(32) {
		t.Errorf("reused position = %v", args)
	}
}

func TestMedia_SelectRefusals(t *testing.T) {
	tn := newTenancyWith(t, control.Config{AllowConfigCommands: true})
	own := tn.createMedia(t, map[string]any{"name": mediaName("专"), "scope": "customer", "customerId": tn.custA})
	calls := tn.sender.calls

	// 旧固件：手动介质和 18 以上编号都不盲发；旧的固定编号不受影响。
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 1, own.ID); rec.Code != 409 || mediaCode(t, rec) != "media_sync_unsupported" {
		t.Errorf("old firmware custom: %d %s", rec.Code, rec.Body.String())
	}
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 1, builtinID(t, tn, 20)); rec.Code != 409 {
		t.Errorf("old firmware 20: %d %s", rec.Code, rec.Body.String())
	}

	tn.markSynced(t, tn.devA1)
	tn.markSynced(t, tn.devB1)
	if rec := selectMedium(t, tn, tn.platform, tn.devB1, 1, own.ID); rec.Code != 400 || mediaCode(t, rec) != "media_not_selectable" {
		t.Errorf("other customer's medium: %d %s", rec.Code, rec.Body.String())
	}
	direct := tn.send(t, "POST", "/api/devices/"+tn.devA1+"/commands", tn.platform, map[string]any{
		"method": protocol.MethodConfigTankSettings, "reason": "r", "requestId": "direct1", "expectedStationId": nil,
		"args": map[string]any{"tank": 1, "oilTypeIndex": 33, "customOilName": "伪造"},
	})
	if direct.Code != 400 || mediaCode(t, direct) != "media_id_required" {
		t.Errorf("direct index: %d %s", direct.Code, direct.Body.String())
	}
	if rec := selectMedium(t, tn, tn.userA, tn.devA1, 1, own.ID); rec.Code != http.StatusForbidden {
		t.Errorf("employee: %d %s", rec.Code, rec.Body.String())
	}
	if tn.sender.calls != calls {
		t.Errorf("refused selections reached the device %d time(s)", tn.sender.calls-calls)
	}

	// 位置用尽明确报错，不覆盖在用位置。
	for i := 0; i < 8; i++ {
		m := tn.createMedia(t, map[string]any{"name": fmt.Sprintf("%s%d", mediaName("满"), i), "scope": "customer", "customerId": tn.custA})
		if rec := selectMedium(t, tn, tn.platform, tn.devA1, i+1, m.ID); rec.Code != http.StatusAccepted {
			t.Fatalf("slot %d: %d %s", i, rec.Code, rec.Body.String())
		}
	}
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 9, own.ID); rec.Code != 409 || mediaCode(t, rec) != "media_slots_exhausted" {
		t.Errorf("exhausted: %d %s", rec.Code, rec.Body.String())
	}
}

func TestMedia_TransferClearsDevicePositions(t *testing.T) {
	tn := newTenancyWith(t, control.Config{AllowConfigCommands: true})
	tn.markSynced(t, tn.devA1)
	own := tn.createMedia(t, map[string]any{"name": mediaName("转"), "scope": "customer", "customerId": tn.custA})
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 1, own.ID); rec.Code != http.StatusAccepted {
		t.Fatalf("select: %d %s", rec.Code, rec.Body.String())
	}
	if rec := tn.send(t, "PUT", "/api/devices/"+tn.devA1+"/customer", tn.platform, map[string]any{"customerId": tn.custB}); rec.Code != http.StatusOK {
		t.Fatalf("transfer: %d %s", rec.Code, rec.Body.String())
	}
	var n int
	if err := tn.env.pool.QueryRow(context.Background(), `SELECT count(*) FROM device_media_slots WHERE device_name = $1`, tn.devA1).Scan(&n); err != nil || n != 0 {
		t.Errorf("positions after transfer = %d, err %v", n, err)
	}
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 2, own.ID); rec.Code != 400 {
		t.Errorf("old customer's medium after transfer: %d %s", rec.Code, rec.Body.String())
	}
}

func TestMedia_ReclaimsUnusedPosition(t *testing.T) {
	tn := newTenancyWith(t, control.Config{AllowConfigCommands: true})
	tn.markSynced(t, tn.devA1)
	ctx := context.Background()
	t.Cleanup(func() { tn.env.pool.Exec(ctx, `DELETE FROM tank_settings WHERE device_name = $1`, tn.devA1) })
	exec := func(sql string, args ...any) {
		t.Helper()
		if _, err := tn.env.pool.Exec(ctx, sql, args...); err != nil {
			t.Fatal(err)
		}
	}
	// 罐 i+1 选用第 i 种手动介质，占满 32–39。
	media := make([]mediaRow, 8)
	for i := range media {
		media[i] = tn.createMedia(t, map[string]any{"name": fmt.Sprintf("%s%d", mediaName("收"), i), "scope": "customer", "customerId": tn.custA})
		if rec := selectMedium(t, tn, tn.platform, tn.devA1, i+1, media[i].ID); rec.Code != http.StatusAccepted {
			t.Fatalf("slot %d: %d %s", i, rec.Code, rec.Body.String())
		}
	}
	next := tn.createMedia(t, map[string]any{"name": mediaName("新"), "scope": "customer", "customerId": tn.custA})

	// 命令都还在途：设备可能已在执行，一个都不能回收。
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 9, next.ID); rec.Code != 409 || mediaCode(t, rec) != "media_slots_exhausted" {
		t.Fatalf("in flight: %d %s", rec.Code, rec.Body.String())
	}

	// 全部执行成功。罐 1 的镜像早于命令（还没跟上），罐 2–8 的镜像已改成内置 1 号介质，只有罐 8 仍用 39。
	exec(`UPDATE control_commands SET status = 'succeeded', finished_at = now() WHERE device_name = $1 AND status IN ('pending','sent','received','executing')`, tn.devA1)
	exec(`INSERT INTO tank_settings (device_name, tank, oil_type_index, reported_at) VALUES ($1, 1, 5, now() - interval '1 hour')`, tn.devA1)
	for tank := 2; tank <= 8; tank++ {
		idx := 1
		if tank == 8 {
			idx = 39
		}
		exec(`INSERT INTO tank_settings (device_name, tank, oil_type_index, reported_at) VALUES ($1, $2, $3, now() + interval '1 second')`, tn.devA1, tank, idx)
	}

	var opts struct{ SlotsUsed int }
	if rec := tn.send(t, "GET", "/api/devices/"+tn.devA1+"/media-options", tn.platform, nil); rec.Code != http.StatusOK || json.Unmarshal(rec.Body.Bytes(), &opts) != nil || opts.SlotsUsed != 2 {
		t.Errorf("slotsUsed = %d (%d %s), want 2 (32 awaiting mirror, 39 on tank 8)", opts.SlotsUsed, rec.Code, rec.Body.String())
	}
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 9, next.ID); rec.Code != http.StatusAccepted {
		t.Fatalf("reclaim: %d %s", rec.Code, rec.Body.String())
	}
	args := tn.lastArgs(t, tn.devA1)
	if args["oilTypeIndex"] != float64(33) || args["customOilName"] != next.Name {
		t.Errorf("reclaimed args = %v, want position 33 (32 awaits mirror)", args)
	}
	var owner int64
	if err := tn.env.pool.QueryRow(ctx, `SELECT media_id FROM device_media_slots WHERE device_name = $1 AND slot = 33`, tn.devA1).Scan(&owner); err != nil || owner != next.ID {
		t.Errorf("position 33 owner = %d, err %v", owner, err)
	}
	// 被回收的介质再次选用时重新拿位置、重新下发名称。
	if rec := selectMedium(t, tn, tn.platform, tn.devA1, 10, media[1].ID); rec.Code != http.StatusAccepted {
		t.Fatalf("reselect: %d %s", rec.Code, rec.Body.String())
	}
	if args = tn.lastArgs(t, tn.devA1); args["oilTypeIndex"] != float64(34) || args["customOilName"] != media[1].Name {
		t.Errorf("reselect args = %v", args)
	}
}

func (tn *tenancy) markColorSynced(t *testing.T, device string) {
	t.Helper()
	if _, err := tn.env.pool.Exec(context.Background(), `
		INSERT INTO device_settings (device_name, oil_color_sync, reported_at) VALUES ($1, 1, now())
		ON CONFLICT (device_name) DO UPDATE SET oil_color_sync = 1`, device); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		tn.env.pool.Exec(context.Background(), `DELETE FROM device_settings WHERE device_name = $1`, device)
	})
}

// 2026-11-14：客户管理员可读本客户设备的选项（公共 + 本客户专属）；别的客户、不存在的设备同一 403；员工 403。
func TestMedia_OptionsAccess(t *testing.T) {
	tn := newTenancy(t)
	ownA := tn.createMedia(t, map[string]any{"name": mediaName("甲"), "scope": "customer", "customerId": tn.custA})
	ownB := tn.createMedia(t, map[string]any{"name": mediaName("乙"), "scope": "customer", "customerId": tn.custB})

	rec := tn.send(t, "GET", "/api/devices/"+tn.devA1+"/media-options", tn.adminA, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("customer admin own device: %d %s", rec.Code, rec.Body.String())
	}
	opts := decodeAs[mediaOptions](t, rec)
	if !names(opts.Customer)[ownA.Name] || names(opts.Customer)[ownB.Name] || len(opts.Public) == 0 || opts.OilColorSync {
		t.Errorf("options = %+v", opts)
	}

	other := tn.send(t, "GET", "/api/devices/"+tn.devB1+"/media-options", tn.adminA, nil)
	ghost := tn.send(t, "GET", "/api/devices/"+tn.devGhost+"/media-options", tn.adminA, nil)
	if other.Code != http.StatusForbidden || ghost.Code != http.StatusForbidden || other.Body.String() != ghost.Body.String() {
		t.Errorf("other customer %d %s / missing %d %s: want the same 403", other.Code, other.Body.String(), ghost.Code, ghost.Body.String())
	}
	if rec := tn.send(t, "GET", "/api/devices/"+tn.devA1+"/media-options", tn.userA, nil); rec.Code != http.StatusForbidden {
		t.Errorf("employee: %d, want 403", rec.Code)
	}

	tn.markColorSynced(t, tn.devA1)
	if opts := decodeAs[mediaOptions](t, tn.send(t, "GET", "/api/devices/"+tn.devA1+"/media-options", tn.platform, nil)); !opts.OilColorSync {
		t.Error("oilColorSync should follow the device's report")
	}
}

func TestMedia_CustomerAdminSelects(t *testing.T) {
	tn := newTenancyWith(t, control.Config{AllowConfigCommands: true})
	tn.markSynced(t, tn.devA1)
	ownA := tn.createMedia(t, map[string]any{"name": mediaName("甲"), "scope": "customer", "customerId": tn.custA})
	ownB := tn.createMedia(t, map[string]any{"name": mediaName("乙"), "scope": "customer", "customerId": tn.custB})

	if rec := selectMedium(t, tn, tn.adminA, tn.devA1, 1, builtinID(t, tn, 5)); rec.Code != http.StatusAccepted {
		t.Fatalf("public: %d %s", rec.Code, rec.Body.String())
	}
	if args := tn.lastArgs(t, tn.devA1); args["oilTypeIndex"] != float64(5) || args["mediaId"] != nil {
		t.Errorf("public args = %v", args)
	}
	if rec := selectMedium(t, tn, tn.adminA, tn.devA1, 2, ownA.ID); rec.Code != http.StatusAccepted {
		t.Fatalf("own: %d %s", rec.Code, rec.Body.String())
	}
	if args := tn.lastArgs(t, tn.devA1); args["oilTypeIndex"] != float64(32) || args["customOilName"] != ownA.Name {
		t.Errorf("own args = %v", args)
	}
	calls := tn.sender.calls
	if rec := selectMedium(t, tn, tn.adminA, tn.devA1, 3, ownB.ID); rec.Code != 400 || mediaCode(t, rec) != "media_not_selectable" {
		t.Errorf("other customer's medium: %d %s", rec.Code, rec.Body.String())
	}
	direct := tn.send(t, "POST", "/api/devices/"+tn.devA1+"/commands", tn.adminA, map[string]any{
		"method": protocol.MethodConfigTankSettings, "reason": "r", "requestId": fmt.Sprintf("d%d", time.Now().UnixNano()), "expectedStationId": nil,
		"args": map[string]any{"tank": 4, "oilTypeIndex": 5},
	})
	if direct.Code != 400 || mediaCode(t, direct) != "media_id_required" {
		t.Errorf("customer admin direct index: %d %s", direct.Code, direct.Body.String())
	}
	if tn.sender.calls != calls {
		t.Errorf("refused selections reached the device %d time(s)", tn.sender.calls-calls)
	}
}

// 旧固件会静默忽略 oilColor，所以没有能力位就整条拒绝、不派发。
func TestMedia_OilColorNeedsCapability(t *testing.T) {
	tn := newTenancyWith(t, control.Config{AllowConfigCommands: true})
	send := func() *httptest.ResponseRecorder {
		return tn.send(t, "POST", "/api/devices/"+tn.devA1+"/commands", tn.adminA, map[string]any{
			"method": protocol.MethodConfigTankSettings, "reason": "换色", "requestId": fmt.Sprintf("c%d", time.Now().UnixNano()),
			"expectedStationId": nil, "args": map[string]any{"tank": 1, "oilColor": 0xFF0000, "capacityL": 100},
		})
	}
	calls := tn.sender.calls
	if rec := send(); rec.Code != http.StatusConflict || mediaCode(t, rec) != "color_sync_unsupported" {
		t.Errorf("old firmware: %d %s", rec.Code, rec.Body.String())
	}
	if tn.sender.calls != calls {
		t.Fatal("a refused colour change reached the device")
	}
	tn.markColorSynced(t, tn.devA1)
	if rec := send(); rec.Code != http.StatusAccepted {
		t.Errorf("synced firmware: %d %s", rec.Code, rec.Body.String())
	}
	if args := tn.lastArgs(t, tn.devA1); args["oilColor"] != float64(0xFF0000) {
		t.Errorf("args = %v", args)
	}
}
