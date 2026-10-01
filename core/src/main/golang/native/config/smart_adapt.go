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
	"runtime/debug"
	"strconv"
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

// smartGroupHealthInterval 是转换后的 smart 组保留的 url-test 健康检查周期(秒)。
//
// 订阅里这类组通常写 interval: 300(5 分钟),内核就对整组每个节点做一遍
// "DNS 解析 + TLS 握手 + HTTP 请求",**熄屏也照跑** —— 在移动网络上表现为
// "用一段时间卡一下",而 App 侧的任何省电/门控都管不到它(那不是 App 发的)。
// smart 组自身有 10~15 分钟的稳定性/失效检测,且节点真实失败会立刻进入存储统计,
// 被选择器即时绕开,所以组级健康检查没必要每 5 分钟一次。
const smartGroupHealthInterval = 600

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

var (
	// smartNodeResolvers 解析 **代理服务器域名**。全部是 IP 字面量 + TLS:
	// 不能被本地网关劫持,也不需要先解析自己的域名(不依赖明文 bootstrap)。
	// 2026-09-28 本机实测(同一域名):
	//   120.53.53.53 0.21s OK / 1.12.12.12 0.21s OK / 1.1.1.1 0.41s OK
	//   8.8.8.8 本网络不可达(1.18s 无响应)/ 223.5.5.5 证书校验失败(故不用)
	// ⚠️ 旧实现只留 1.1.1.1 + 8.8.8.8 两条境外 DoH:国内网络下被阻断就彻底没有
	//    解析能力 → 节点域名解析失败 → 断流(用户 2026-09-28 反馈:关开代理才恢复)。
	smartNodeResolvers = []string{
		"https://120.53.53.53/dns-query", // DNSPod(国内)
		"https://1.12.12.12/dns-query",   // DNSPod(国内)
		"https://1.1.1.1/dns-query",      // Cloudflare(境外,对抗域名级污染)
		"https://8.8.8.8/dns-query",      // Google(本网络不可达,换网络时可用)
	}

	// smartDirectResolvers 解析 **直连域名**:只用国内字面量 DoH。
	// 不掺境外 —— 直连要的是本地答案,境外解析器可能返回海外 CDN 地址。
	smartDirectResolvers = []string{
		"https://120.53.53.53/dns-query",
		"https://1.12.12.12/dns-query",
	}

	// smartFallbackResolvers 只用于"main 返回黑洞地址时"的兜底重查:
	// 国内解析器对部分域名会给出 0.0.0.0/127.0.0.1(投毒/封禁响应),这时改问境外。
	smartFallbackResolvers = []string{
		"https://1.1.1.1/dns-query",
	}
)

// isHijackablePlaintext 判断一条 nameserver 是否是明文形式(裸 IP / tcp:// / udp://)。
//
// ⚠️ 关键: mihomo 解析域名不是"按列表顺序回退",而是 batchExchange **并发竞速** ——
// 第一个 err == nil 的回答获胜并 cancel 其余;且只有 SERVFAIL/REFUSED 算失败,
// **被本地网关劫持后返回的 NOERROR 算成功**。明文应答是毫秒级,必然抢赢 DoH(约 0.2s),
// 坏答案随后进缓存。这正是"服务还在 Running、但流量断掉,关开代理(ClearCache)才恢复"的成因。
// 所以明文条目必须从列表里**剔除**,不能指望"放在后面当后备"。
func isHijackablePlaintext(ns string) bool {
	s := strings.ToLower(strings.TrimSpace(ns))
	if s == "" {
		return true
	}
	// 只认真正走 TLS 的三种 scheme。刻意**不**放行这三种(2026-10-01 Grok 复核指出):
	//   h3://     —— mihomo 没有这个 scheme,留着会让整份 DNS 配置加载失败
	//   system:// —— Android 上就是 UpdateSystemDNS 的明文 UDP(dns/patch_android.go)
	//   dhcp://   —— DHCP 拿到的 DNS 同样是明文 UDP(dns/dhcp.go),照样会抢赢
	for _, p := range []string{"https://", "tls://", "quic://"} {
		if strings.HasPrefix(s, p) {
			return false
		}
	}
	return true
}

// filterUnhijackable 丢弃明文解析器,只保留 TLS / 系统形式。
func filterUnhijackable(list []string) []string {
	out := make([]string, 0, len(list))
	for _, ns := range list {
		if !isHijackablePlaintext(ns) {
			out = append(out, strings.TrimSpace(ns))
		}
	}
	return out
}

// mergeResolvers 合并两组解析器(去重、丢弃空项)。
// 注意:顺序**不代表优先级** —— 见 isHijackablePlaintext 的注释(并发竞速语义)。
func mergeResolvers(priority, existing []string) []string {
	merged := make([]string, 0, len(priority)+len(existing))
	seen := make(map[string]bool, len(priority)+len(existing))
	for _, list := range [][]string{priority, existing} {
		for _, ns := range list {
			key := strings.TrimSpace(ns)
			if key == "" || seen[key] {
				continue
			}
			seen[key] = true
			merged = append(merged, key)
		}
	}
	return merged
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

	// 旧实现是"整体替换成 1.1.1.1 + 8.8.8.8 两条境外 DoH":境外 DoH 在国内被阻断时
	// 就完全失去解析能力 → 节点域名解析失败 → 断流。
	// 现在:IP 字面量 DoH + 订阅里**不可劫持**的条目一起参与竞速(竞速语义见
	// isHijackablePlaintext 注释 —— 顺序无意义,但明文项必须剔除)。
	oldProxyNS := strings.Join(cfg.DNS.ProxyServerNameserver, ", ")
	oldProxyNSLen := len(cfg.DNS.ProxyServerNameserver)
	keptProxyNS := filterUnhijackable(cfg.DNS.ProxyServerNameserver)
	cfg.DNS.ProxyServerNameserver = mergeResolvers(smartNodeResolvers, keptProxyNS)

	// 用户流量的解析(nameserver)同一套问题:订阅用"域名式 DoH",它自己的域名还要靠
	// 明文 default-nameserver 引导 —— 明文被劫持时它连不上;而列表里若留着明文解析器,
	// 竞速下劫持应答又必然抢赢。所以同样是"字面量 DoH + 不可劫持的订阅条目"。
	if len(cfg.DNS.NameServer) > 0 {
		oldNS := strings.Join(cfg.DNS.NameServer, ", ")
		cfg.DNS.NameServer = mergeResolvers(smartNodeResolvers, filterUnhijackable(cfg.DNS.NameServer))
		log.Infoln("[SmartDns] nameserver [%s] -> %v", oldNS, cfg.DNS.NameServer)
	}
	if len(cfg.DNS.DirectNameServer) > 0 {
		oldDN := strings.Join(cfg.DNS.DirectNameServer, ", ")
		cfg.DNS.DirectNameServer = mergeResolvers(smartDirectResolvers, filterUnhijackable(cfg.DNS.DirectNameServer))
		log.Infoln("[SmartDns] direct-nameserver [%s] -> %v (domestic literals only: direct lookups want local answers)",
			oldDN, cfg.DNS.DirectNameServer)
	}

	// 用户流量的 fallback:只有当 main 给出"黑洞地址"(0.0.0.0/127.0.0.1 这类典型投毒响应)
	// 时才改问 1.1.1.1。**故意不打开 geoip: true** —— mihomo 的 fallback-filter 对"非 CN"
	// 一律判为需要换源(rules/common/geoip.go),1.1.1.1 被墙时会把国内 DoH 已经拿到的
	// 正确海外 IP 整段丢掉,断流立刻回来(Grok 2026-10-01 复核明确警告)。
	cfg.DNS.Fallback = mergeResolvers(smartFallbackResolvers, filterUnhijackable(cfg.DNS.Fallback))
	cfg.DNS.FallbackFilter.GeoIP = false
	cfg.DNS.FallbackFilter.GeoIPCode = ""
	cfg.DNS.FallbackFilter.IPCIDR = mergeResolvers(
		[]string{"0.0.0.0/32", "127.0.0.1/32", "240.0.0.0/4"},
		cfg.DNS.FallbackFilter.IPCIDR,
	)
	log.Infoln("[SmartDns] fallback %v + fallback-filter.ipcidr %v (geoip off: only blackhole answers are re-queried)",
		cfg.DNS.Fallback, cfg.DNS.FallbackFilter.IPCIDR)

	log.Infoln("[SmartDns] proxy-server-nameserver [%s] -> %v (dropped %d plaintext/hijackable entry/entries; mihomo races resolvers in parallel, a hijacked answer would win)",
		oldProxyNS, cfg.DNS.ProxyServerNameserver, oldProxyNSLen-len(keptProxyNS))

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

		// 放宽组级健康检查周期(见 smartGroupHealthInterval 注释):
		// 订阅里的 300 秒会让内核每 5 分钟整组测一遍,熄屏也照跑。
		switch v := g["interval"].(type) {
		case int:
			if v < smartGroupHealthInterval {
				g["interval"] = smartGroupHealthInterval
			}
		case float64:
			if int(v) < smartGroupHealthInterval {
				g["interval"] = smartGroupHealthInterval
			}
		case string:
			// 有的订阅把 interval 写成字符串
			if n, err := strconv.Atoi(strings.TrimSpace(v)); err == nil && n < smartGroupHealthInterval {
				g["interval"] = smartGroupHealthInterval
			}
		}

		converted = append(converted, fmt.Sprintf("%s(%s→smart)", name, t))
	}

	if len(converted) > 0 {
		log.Infoln("[SmartAdapt] converted %d group(s): %s", len(converted), strings.Join(converted, ", "))
	}

	// 回读 Go 运行时的软内存上限。放在这里而不是 Init():Init() 执行时日志管道还没接到
	// logcat,那一刻写什么都不出声(实测 Info/Warn 都被吞)。
	// SetMemoryLimit(-1) 只返回当前值,不修改。
	log.Infoln("[SmartMem] Go soft memory limit: %d MiB", debug.SetMemoryLimit(-1)>>20)

	return nil
}
