package config

import (
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"github.com/metacubex/mihomo/component/profile/cachefile"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// Model.bin is the LightGBM weight model released by vernesong/mihomo
// (LightGBM-Model release, sha256 23633022a815fb34e510befe1c176ba50af3ced48f08b172454f4cd6b8327788).
// Bundled so the smart group works offline on first launch instead of
// downloading it from GitHub at startup.
//
//go:embed Model.bin
var smartModelBin []byte

// smartAdaptEnabled could be wired to a settings switch later; v1 keeps it always on.
const smartAdaptEnabled = true

var smartAdaptFrom = map[string]bool{"url-test": true, "fallback": true, "load-balance": true}

// Model.bin 的期望哈希(与 //go:embed 进来的是同一份文件;用来在装盘时自检)。
const smartModelSHA256 = "23633022a815fb34e510befe1c176ba50af3ced48f08b172454f4cd6b8327788"

// --- IPv6 / 节点域名解析(2026-09-27 实测新增)-------------------------------
//
// 两个坑必须一起解决,否则 IPv6-only 节点整段废掉:
//
//  1. 订阅模板常写 `ipv6: false`。顶层 ipv6 是 resolver.DisableIPv6 的唯一开关
//     (hub/executor/executor.go),关着时解析器只查 A;而自建节点常用"只有 AAAA 记录"
//     的服务器域名 → 这类节点 100% 解析失败(实测 48/48 全挂)。
//  2. 只开顶层 ipv6 还不够:代理服务器域名只由 dns.proxy-server-nameserver 解析
//     (dns.Resolver 的 ProxyResolver 没有 fallback)。订阅里那几项通常是"域名式 DoH + 明文 UDP",
//     两者都要碰明文 53 端口;在会劫持 53 的网络里(家用 fake-ip 网关),内核会拿到假 A 记录和空 AAAA
//     → AAAA-only 节点照样连不上。换成 IP 字面量 DoH 走 TLS,劫持不了。
//
// 实测同一订阅 110 节点,只改 dns.proxy-server-nameserver:通过 37→89、AAAA-only 0/48→48/48。
const (
	smartForceIPv6 = true
	// mihomo 等 AAAA 的窗口默认只有 100ms(dns/resolver.go 的 LookupIP),
	// 移动网络/DoH 常迟到,迟到的 AAAA 被丢掉后只会报"找不到 IP"。
	smartIPv6TimeoutMS = 2000
)

var smartNodeResolvers = []string{
	"https://1.1.1.1/dns-query",
	"https://8.8.8.8/dns-query",
}

// patchSmartDns 让"代理服务器域名"的解析不依赖明文 DNS(会被本地网关劫持)。
// 跑在 patchOverride 之前,用户仍可用覆写完全接管这两项。
func patchSmartDns(cfg *config.RawConfig, _ string) error {
	if !smartForceIPv6 || cfg == nil {
		return nil
	}

	if !cfg.IPv6 {
		cfg.IPv6 = true

		log.Infoln("[SmartDns] ipv6 forced on (needed for IPv6-only server hostnames)")
	}

	if cfg.DNS.IPv6Timeout < smartIPv6TimeoutMS {
		cfg.DNS.IPv6Timeout = smartIPv6TimeoutMS
	}

	for _, ns := range cfg.DNS.ProxyServerNameserver {
		if isDoHWithLiteralIP(ns) {
			// 订阅自己已经给了 TLS/IP 字面量解析器,尊重它
			return nil
		}
	}

	old := strings.Join(cfg.DNS.ProxyServerNameserver, ", ")
	cfg.DNS.ProxyServerNameserver = append([]string(nil), smartNodeResolvers...)

	log.Infoln("[SmartDns] proxy-server-nameserver [%s] -> %v (plaintext DNS is hijackable; node hostnames need AAAA)",
		old, cfg.DNS.ProxyServerNameserver)

	return nil
}

// isDoHWithLiteralIP 判断一条 nameserver 是否"TLS + IP 字面量"(如 https://1.1.1.1/dns-query)。
// 这类不需要明文 UDP 做引导,因此不会被本地网关的 DNS 劫持换掉 —— 只有它们能安全解析
// AAAA-only 的服务器域名。
func isDoHWithLiteralIP(ns string) bool {
	entry := strings.TrimSpace(ns)
	if entry == "" {
		return false
	}

	// 去掉 mihomo 的附加后缀:#策略组 / ?参数
	entry = strings.SplitN(entry, "#", 2)[0]
	entry = strings.SplitN(entry, "?", 2)[0]

	u, err := url.Parse(entry)
	if err != nil {
		return false
	}

	if u.Scheme != "https" && u.Scheme != "tls" {
		return false
	}

	host := u.Hostname()
	if host == "" {
		return false
	}

	_, err = netip.ParseAddr(host)

	return err == nil
}

// ensureSmartModel installs the bundled model into the kernel home dir
// where component/smart/lightgbm looks it up (C.Path.SmartModel()).
//
// 原子安装 + 尺寸校验:旧实现是"文件存在就跳过 + 直接 WriteFile",
// 一次写一半(进程被杀/磁盘满)会留下坏文件被永久跳过,smart 组从此算不出权重。
func ensureSmartModel() {
	dir := C.Path.HomeDir()
	if dir == "" {
		return
	}

	target := filepath.Join(dir, "Model.bin")

	if st, err := os.Stat(target); err == nil && st.Size() == int64(len(smartModelBin)) {
		return
	}

	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Warnln("[SmartAdapt] create home dir: %s", err.Error())

		return
	}

	sum := sha256.Sum256(smartModelBin)
	digest := hex.EncodeToString(sum[:])
	if digest != smartModelSHA256 {
		log.Warnln("[SmartAdapt] bundled Model.bin sha256 mismatch: got %s want %s", digest, smartModelSHA256)
	}

	tmp := target + ".tmp"
	if err := os.WriteFile(tmp, smartModelBin, 0o644); err != nil {
		log.Warnln("[SmartAdapt] write Model.bin: %s", err.Error())

		return
	}

	if err := os.Rename(tmp, target); err != nil {
		_ = os.Remove(tmp)
		log.Warnln("[SmartAdapt] install Model.bin: %s", err.Error())

		return
	}

	log.Infoln("[SmartAdapt] Model.bin installed: %s (%d bytes, sha256 %s)", target, len(smartModelBin), digest)
}

func patchSmartController(cfg *config.RawConfig, _ string) error {
	if cfg == nil {
		return nil
	}

	// Expose the RESTful controller on loopback so the bundled Smart status
	// dashboard (app/assets/smart.html) can read group weights. Runs before
	// patchOverride, so a user override can still replace or disable it.
	if cfg.ExternalController == "" {
		cfg.ExternalController = "127.0.0.1:9090"
		cfg.ExternalControllerUnix = ""
		// Subscriptions built for router use often carry `secret:` and
		// `external-controller-cors:` — both would 401/block the dashboard.
		// This controller is loopback-only (same app UID on Android), so auth
		// adds nothing here; drop both for our injected controller.
		cfg.Secret = ""
		cfg.ExternalControllerCors = config.RawCors{
			AllowOrigins:        []string{"*"},
			AllowPrivateNetwork: true,
		}
	}

	return nil
}

// smartTuning carries optional per-app smart preferences, read from the
// persist override JSON (Settings → Override) as:
//
//	{"smart-options": {"tolerance": 50, "policy-priority": "pattern:2.0;...", "sample-rate": 0.5, "collectdata": true}}
//
// It is applied to every converted group; patchOverride still runs later and
// a full proxy-groups override can always replace the result.
type smartTuning struct {
	Tolerance      uint16  `json:"tolerance"`
	PolicyPriority string  `json:"policy-priority"`
	SampleRate     float64 `json:"sample-rate"`
	CollectData    *bool   `json:"collectdata"`
}

func readSmartTuning() smartTuning {
	var raw struct {
		SmartOptions *smartTuning `json:"smart-options"`
	}
	content := ReadOverride(OverrideSlotPersist)
	if content == "" {
		return smartTuning{}
	}
	if err := json.Unmarshal([]byte(content), &raw); err != nil {
		return smartTuning{}
	}
	if raw.SmartOptions == nil {
		return smartTuning{}
	}
	log.Infoln("[SmartAdapt] applying smart-options from override: %+v", *raw.SmartOptions)
	return *raw.SmartOptions
}

func patchSmartAdapt(cfg *config.RawConfig, _ string) error {
	// CMFA forces Profile.StoreSelected=false, so without this call nothing
	// initializes the cachefile DB and the smart store silently runs memory-only
	// (all weights lost on every restart). Cache() is a once-guarded singleton;
	// running it here — before any smart group calls GetSmartStore() — makes the
	// store disk-backed. Data still flushes to disk every 5 minutes.
	cachefile.Cache()

	if cfg == nil {
		return nil
	}

	// 无论有没有可转换的组都要装模型:否则"这一版订阅里暂时没有 url-test/fallback 组"
	// 会让 Model.bin 永远不装,smart 组只能用默认权重。
	ensureSmartModel()

	if !smartAdaptEnabled || len(cfg.ProxyGroup) == 0 {
		return nil
	}

	tuning := readSmartTuning()

	converted := make([]string, 0, len(cfg.ProxyGroup))

	for _, g := range cfg.ProxyGroup {
		t, _ := g["type"].(string)
		if !smartAdaptFrom[strings.ToLower(strings.TrimSpace(t))] {
			continue
		}

		g["type"] = "smart"
		// LightGBM prediction on by default: the model ships inside this app.
		g["uselightgbm"] = true
		// Never enable prefer-asn implicitly: a missing ASN.mmdb adds a startup
		// network dependency (GitHub download) that can stall startup for ~80s.
		delete(g, "prefer-asn")

		// user tuning from the persist override file, e.g.
		// {"smart-options": {"tolerance": 50, "policy-priority": "IEPL:2.0;家宽:0.5"}}
		if tuning.Tolerance > 0 {
			g["tolerance"] = tuning.Tolerance
		}
		if tuning.PolicyPriority != "" {
			g["policy-priority"] = tuning.PolicyPriority
		}
		if tuning.SampleRate > 0 {
			g["sample-rate"] = tuning.SampleRate
		}
		if tuning.CollectData != nil && *tuning.CollectData {
			g["collectdata"] = true
		}

		name, _ := g["name"].(string)
		converted = append(converted, fmt.Sprintf("%s(%s→smart)", name, t))
	}

	if len(converted) > 0 {
		log.Infoln("[SmartAdapt] converted %d group(s): %s", len(converted), strings.Join(converted, ", "))
	}

	return nil
}
