// Command tbconfig versions ThingsBoard's own configuration — device profiles and rule chains —
// as files in this repository, and applies them to an installation.
//
// Why this exists: ThingsBoard keeps its configuration in its own database, not in the repo.
// Without this, "install a new environment by following the document" degrades into clicking
// through a console from screenshots, and nobody can answer "does production match what the repo
// says". GitHub issue #2 asks for exactly this.
//
//	tbconfig apply    write deploy/tb-config/*.json into ThingsBoard (idempotent, matched by name)
//	tbconfig export   dump the installation's current config for diffing against the repo
//
// It is a Go binary rather than a shell script because the machines that run it are air-gapped and
// minimal: debian:12-slim has neither python3 nor curl, and shipping another runtime dependency for
// an install-time tool is not worth it when this repo already ships static binaries.
package main

import (
	"context"
	"crypto/rand"
	"encoding/base32"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"yeweiyi-cloud/internal/tbclient"
)

func main() { os.Exit(run()) }

func run() int {
	fs := flag.NewFlagSet("tbconfig", flag.ExitOnError)
	dir := fs.String("dir", "deploy/tb-config", "directory holding the versioned configuration")
	tbURL := fs.String("tb", envOr("TB_BASE_URL", "http://localhost:8081"), "ThingsBoard base URL")
	user := fs.String("user", envOr("TB_USERNAME", "tenant@thingsboard.org"), "tenant administrator")
	pass := fs.String("pass", envOr("TB_PASSWORD", "tenant"), "tenant administrator password")

	if len(os.Args) < 2 {
		usage()
		return 2
	}
	action := os.Args[1]
	fs.Parse(os.Args[2:])

	c, err := tbclient.New(tbclient.Config{BaseURL: *tbURL, Username: *user, Password: *pass})
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := c.Login(ctx); err != nil {
		fmt.Fprintf(os.Stderr, "ThingsBoard 登录失败：%v\n", err)
		return 1
	}

	switch action {
	case "apply":
		if err := apply(ctx, c, *dir); err != nil {
			fmt.Fprintf(os.Stderr, "!!! %v\n", err)
			return 1
		}
	case "export":
		if err := export(ctx, c, filepath.Join(*dir, "current")); err != nil {
			fmt.Fprintf(os.Stderr, "!!! %v\n", err)
			return 1
		}
	default:
		usage()
		return 2
	}
	return 0
}

func usage() {
	fmt.Fprintln(os.Stderr, `用法：
  tbconfig apply    把 deploy/tb-config/ 里的定义写入 ThingsBoard（幂等，按名字创建或更新）
  tbconfig export   把实例当前配置导出到 deploy/tb-config/current/，用于与仓库定义比对

  -tb -user -pass -dir  （亦可用 TB_BASE_URL / TB_USERNAME / TB_PASSWORD 环境变量）`)
}

// page is ThingsBoard's paged list envelope.
type page struct {
	Data []map[string]any `json:"data"`
}

func apply(ctx context.Context, c *tbclient.Client, dir string) error {
	files, err := filepath.Glob(filepath.Join(dir, "device-profile-*.json"))
	if err != nil {
		return err
	}
	if len(files) == 0 {
		return fmt.Errorf("%s 下没有 device-profile-*.json", dir)
	}
	sort.Strings(files)

	var existing page
	if err := c.GetJSON(ctx, "/api/deviceProfiles?pageSize=200&page=0", &existing); err != nil {
		return fmt.Errorf("列出现有设备模板：%w", err)
	}
	byName := map[string]map[string]any{}
	for _, p := range existing.Data {
		if n, _ := p["name"].(string); n != "" {
			byName[n] = p
		}
	}

	fmt.Println("==> 应用设备配置模板")
	for _, f := range files {
		raw, err := os.ReadFile(f)
		if err != nil {
			return err
		}
		var spec map[string]any
		if err := json.Unmarshal(raw, &spec); err != nil {
			return fmt.Errorf("%s: %w", filepath.Base(f), err)
		}
		// _comment carries the reasoning for humans; ThingsBoard would reject the unknown field.
		delete(spec, "_comment")

		name, _ := spec["name"].(string)
		if name == "" {
			return fmt.Errorf("%s: 缺少 name", filepath.Base(f))
		}

		// The repo's definition deliberately carries no id: that is per-installation state, not
		// configuration. Updating an existing profile requires echoing its id/tenantId/version back,
		// otherwise ThingsBoard treats the request as a create and rejects the duplicate name.
		verb := "创建"
		if cur, ok := byName[name]; ok {
			verb = "更新"
			spec["id"] = cur["id"]
			spec["tenantId"] = cur["tenantId"]
			if v, ok := cur["version"]; ok && v != nil {
				spec["version"] = v
			}
			// Never take over the tenant-wide default flag from what the repo says: which profile is
			// the tenant default is an installation-level choice, and silently moving it would change
			// how every *other* device gets provisioned.
			spec["default"] = cur["default"]
			// Same reasoning for the provisioning key/secret: the repo cannot carry them (they must
			// not be committed), and ThingsBoard treats an absent value on update as "clear it" —
			// measured on 4.2.1.1, not regenerated. Without this, every apply would silently disable
			// device self-registration.
			carryProvisioning(spec, cur)
		}
		generated := ensureProvisioningCredentials(spec)

		var saved map[string]any
		if err := c.PostJSON(ctx, "/api/deviceProfile", spec, &saved); err != nil {
			return fmt.Errorf("%s %s：%w", verb, name, err)
		}
		fmt.Printf("    ✓ %s %s\n", verb, name)
		if generated {
			announceProvisioning(spec)
		}
	}

	fmt.Println()
	fmt.Println("模板只影响此后新建设备的默认行为；已存在的设备不会自动改挂到新模板。")
	return nil
}

var unsafeName = regexp.MustCompile(`[^A-Za-z0-9_.-]`)

// instanceFields are per-installation state rather than configuration, and are stripped on export so
// two installations' exports can be diffed meaningfully.
var instanceFields = []string{"id", "createdTime", "tenantId", "version", "externalId"}

func export(ctx context.Context, c *tbclient.Client, out string) error {
	if err := os.MkdirAll(out, 0o755); err != nil {
		return err
	}
	fmt.Printf("==> 导出到 %s\n", out)

	var profiles page
	if err := c.GetJSON(ctx, "/api/deviceProfiles?pageSize=200&page=0", &profiles); err != nil {
		return fmt.Errorf("设备模板：%w", err)
	}
	for _, p := range profiles.Data {
		name, _ := p["name"].(string)
		for _, k := range instanceFields {
			delete(p, k)
		}
		redactProvisioning(p)
		if err := writeJSON(filepath.Join(out, "device-profile-"+safe(name)+".json"), p); err != nil {
			return err
		}
		fmt.Printf("    设备模板 %s\n", name)
	}

	var chains page
	if err := c.GetJSON(ctx, "/api/ruleChains?pageSize=200&page=0", &chains); err != nil {
		return fmt.Errorf("规则链：%w", err)
	}
	for _, ch := range chains.Data {
		name, _ := ch["name"].(string)
		id := ""
		if idObj, ok := ch["id"].(map[string]any); ok {
			id, _ = idObj["id"].(string)
		}
		if id == "" {
			continue
		}

		// The metadata endpoint returns the whole graph — nodes and connections — which is what the
		// console's "export rule chain" produces too.
		var md map[string]any
		if err := c.GetJSON(ctx, "/api/ruleChain/"+id+"/metadata", &md); err != nil {
			return fmt.Errorf("规则链 %s：%w", name, err)
		}
		delete(md, "ruleChainId")
		if nodes, ok := md["nodes"].([]any); ok {
			for _, n := range nodes {
				if node, ok := n.(map[string]any); ok {
					for _, k := range append([]string{"ruleChainId"}, instanceFields...) {
						delete(node, k)
					}
				}
			}
		}
		md["name"] = name

		if err := writeJSON(filepath.Join(out, "rule-chain-"+safe(name)+".json"), md); err != nil {
			return err
		}
		fmt.Printf("    规则链 %s\n", name)
	}

	fmt.Println()
	fmt.Println("current/ 是实例现状的快照，供 diff 用；仓库的权威定义是 tb-config/ 根目录下那几份。")
	return nil
}

func writeJSON(path string, v any) error {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(b, '\n'), 0o644)
}

func safe(name string) string {
	s := unsafeName.ReplaceAllString(name, "_")
	return strings.Trim(s, "_")
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// provisioningEnabled reports whether the profile lets devices register themselves.
func provisioningEnabled(spec map[string]any) bool {
	t, _ := spec["provisionType"].(string)
	return t != "" && t != "DISABLED"
}

// provisionConfig returns profileData.provisionConfiguration, creating the maps on the way.
func provisionConfig(spec map[string]any) map[string]any {
	pd, _ := spec["profileData"].(map[string]any)
	if pd == nil {
		pd = map[string]any{}
		spec["profileData"] = pd
	}
	pc, _ := pd["provisionConfiguration"].(map[string]any)
	if pc == nil {
		pc = map[string]any{}
		pd["provisionConfiguration"] = pc
	}
	return pc
}

// redactProvisioning removes the fleet-wide provisioning key/secret from an exported profile. They
// are installation state (see carryProvisioning), and export files are world-readable snapshots that
// get copied into diagnostics. Unlike provisionConfig it never creates missing maps.
func redactProvisioning(p map[string]any) {
	delete(p, "provisionDeviceKey")
	pd, _ := p["profileData"].(map[string]any)
	pc, _ := pd["provisionConfiguration"].(map[string]any)
	delete(pc, "provisionDeviceSecret")
}

// carryProvisioning copies the installation's key/secret into spec when spec does not set its own.
func carryProvisioning(spec, cur map[string]any) {
	if v, _ := cur["provisionDeviceKey"].(string); v != "" {
		if k, _ := spec["provisionDeviceKey"].(string); k == "" {
			spec["provisionDeviceKey"] = v
		}
	}
	curPD, _ := cur["profileData"].(map[string]any)
	curPC, _ := curPD["provisionConfiguration"].(map[string]any)
	if v, _ := curPC["provisionDeviceSecret"].(string); v != "" {
		pc := provisionConfig(spec)
		if k, _ := pc["provisionDeviceSecret"].(string); k == "" {
			pc["provisionDeviceSecret"] = v
		}
	}
}

// ensureProvisioningCredentials fills in a random key/secret when provisioning is on and either is
// missing, and reports whether it generated anything. The pair is installation state handed to the
// Android build out of band; it is never read from or written to the repo.
func ensureProvisioningCredentials(spec map[string]any) bool {
	if !provisioningEnabled(spec) {
		return false
	}
	changed := false
	if k, _ := spec["provisionDeviceKey"].(string); k == "" {
		spec["provisionDeviceKey"] = randomCredential()
		changed = true
	}
	pc := provisionConfig(spec)
	if k, _ := pc["provisionDeviceSecret"].(string); k == "" {
		pc["provisionDeviceSecret"] = randomCredential()
		changed = true
	}
	return changed
}

func randomCredential() string {
	b := make([]byte, 15)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand failing means the machine cannot produce credentials safely
	}
	return strings.ToLower(base32.StdEncoding.WithPadding(base32.NoPadding).EncodeToString(b))
}

// announceProvisioning prints the generated pair exactly once, and only after the save succeeded;
// printing earlier would leave the operator holding a pair that ThingsBoard never stored.
func announceProvisioning(spec map[string]any) {
	fmt.Println()
	fmt.Println("已生成设备自注册凭据（只显示这一次；重复 apply 会保留，不会再生成）：")
	fmt.Printf("    PROVISION_DEVICE_KEY=%v\n", spec["provisionDeviceKey"])
	fmt.Printf("    PROVISION_DEVICE_SECRET=%v\n", provisionConfig(spec)["provisionDeviceSecret"])
	fmt.Println("    写入 Android 构建的 local.properties，不要提交到仓库。")
}
