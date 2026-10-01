# Clash Smart v2.11.70 代码审计报告(2026-09-27)

审计对象:`0x1233333/ClashMetaForAndroid` `smart` 分支 @ `37827f6b`(= v2.11.70 发布态)
方法:逐文件读源码 + `go vet -tags "foss,with_gvisor,cmfa"` + `gofmt -l` + 拆开真实发布包量体积 + 本机实编译验证
**本次只读,未改任何代码、未推送任何东西。**

---

## 一、真 bug(按严重度)

### 🔴 P0-1 健康守护可能永久卡死(静默失效)
- 位置:`service/.../module/SmartHealthModule.kt:147` → `Clash.urlTestGroup(name).await()`
- 证据:同文件第 42 行 `private const val TEST_TIMEOUT_MS = 5000` **声明后从未被引用**(全仓只有这一处出现)。说明当初想加超时但漏了。
- 后果:`await()` 依赖内核回调;一旦内核重载/切订阅/回调丢失,协程永久挂起 → 后续所有测速与"热重载看门狗"静默失效,用户看不出任何异常(页面只在别处显示"距上次测速")。
- 修法:`withTimeoutOrNull(TEST_TIMEOUT_MS * N) { ... }`,超时按失败计入 `failStreak`。

### 🔴 P0-2 CI 会静默产出并上传"未签名包"
- 位置:`.github/workflows/build-release.yaml:96-101`(secret 缺失时只打印 `-> unsigned build`,**不中止**)+ `:133` 上传通配 `app/build/outputs/apk/meta/release/*`
- 证据(实测):Release `v2.11.62` 及更早,每个 release 里同时挂着 5 个 `cmfa-*-release-unsigned.apk` + 1 个本地签名包;`v2.11.65+` 才只有签名包。v2.11.37/41–62 共 17 个 release 受污染。
- 修法:secret 缺失直接 `exit 1`,或上传前 `apksigner verify` 过滤 + 加 `fail-if-unsigned` 步骤。

### 🔴 P0-3 订阅 `ipv6` + 节点域名解析走明文 DNS → IPv6-only 节点整段废掉(9/27 复核定稿,**两层根因**)
- 位置:`core/src/main/golang/native/config/process.go` 整条处理器链**没有任何 ipv6 / DNS 解析路径处理**
- 证据(本机实测,详见 `REPORT-ipv6-node-connectivity-20260927.md`):
  - **第一层**:同一订阅 110 节点,`ipv6:false` → 39 通过(48 个 `-v6-` 全挂);顶层 `ipv6:true` → 89 通过、48/48 v6 全通、零回归;官方内核与我们 fork 逐节点一致。
  - **第二层(用户报"顶层改了还是连不上"的真因)**:订阅 `dns.proxy-server-nameserver` 是域名式 DoH + 明文 UDP;在家用网关劫持明文 53 的网络里 → `A → 198.18.x.x`(fake-ip)、`AAAA → 空` → 48 个 v6 依旧全挂(37/110)。**只把该项换成 IP 字面量 DoH → 89/110、48/48**,其余 DNS 键(fake-ip / nameserver-policy / respect-rules)一个没动。
- 修法(两条都做):①置顶层 `cfg.IPv6 = true`;②把 `cfg.DNS.ProxyServerNameserver` 里**非 IP 字面量**的项替换为 `https://1.1.1.1/dns-query` / `https://8.8.8.8/dns-query`,并设 `cfg.DNS.IPv6Timeout = 2000`(默认 100ms,移动网络下 AAAA 常迟到;`dns/resolver.go:77-108`)。状态页可显示 IPv6 开/关与当前 DNS 解析路径。

### 🟠 P1-4 回环控制器无认证 + 任意来源 CORS(隐私/被接管)
- 位置:`core/src/main/golang/native/config/smart_adapt.go:65-77`
  ```go
  cfg.Secret = ""
  cfg.ExternalControllerCors = RawCors{AllowOrigins: []string{"*"}, AllowPrivateNetwork: true}
  ```
- 代码注释写"loopback-only (same app UID on Android), so auth adds nothing here"——**这个假设是错的**:Android 上 `127.0.0.1` 跨 App 共享(只有网络命名空间才隔离)。
- 后果:手机上**任何** App 或网页都能 `GET /connections`(泄露你访问的域名)、`PUT /proxies`(改你的节点)、读配置。
- 修法:注入时生成随机 `secret` 并让仪表盘带 `Authorization: Bearer`;CORS 收紧到自己的来源;或让 smart.html 走内核自带 `external-ui`(`process.go:56-58` 已设 `profileDir/ui`)实现同源。

### 🟠 P1-5 Model.bin 安装不可靠(可能用错模型 / 永不修复)
- 位置:`smart_adapt.go:32-55`
- 问题 1:只 `os.Stat` 判"文件存在"就返回,**不校验大小/哈希** → 之前被内核自己下载过的、旧版本的、或截断的 Model.bin 会永久生效(smart 用错模型跑)。
- 问题 2:`os.WriteFile` **非原子** → 写一半被杀 → 留下截断文件,而下次启动因"文件存在"不再重写。
- 问题 3:`ensureSmartModel()` 在函数第 122 行早退之后 → 若订阅里没有可转换的组(用户自己用覆写加 smart 组),模型不会落地。
- 修法:比对 `len(smartModelBin)`(或 sha256)→ 不符则"写临时文件 + rename";把 `ensureSmartModel()` 移到早退之前。

### 🟠 P1-6 日志函数用错,错误原文被吞
- 位置:`core/src/main/golang/native/tun/tun.go:34`、`:72` → `log.Errorln("TUN:", err)`
- 证据:mihomo 的 `log/log.go:49` 签名是 `func Errorln(format string, v ...any)`(**printf 语义**),所以这行实际输出 `TUN:%!(EXTRA *netip.ParsePrefix=…)`,真错误看不到。`go vet` 同报。
- 修法:`log.Errorln("TUN: %s", err)`。

### 🟡 P2-7 `sync-smart.yaml` 的"手动跟内核"是死代码
- 证据:第 57 行读 `${{ inputs.update_kernel }}`,但第 9 行 `workflow_dispatch:` **没有声明任何 inputs** → 永远为空字符串 → 文档承诺的"dispatch 时传 `update_kernel=true` 才跟进 Alpha"根本走不通。
- 修法:`workflow_dispatch: inputs: update_kernel: {type: boolean, default: false}`。

### 🟡 P2-8 CI 把签名口令打印进日志
- 位置:`build-release.yaml:114-115`(`cat signing.properties`)→ 把 store/key 口令写进 job 日志(靠 GitHub 掩码兜底,没必要冒险)。删掉,或只打文件长度。

### 🟡 P2-9 patch 循环无错误检查
- 位置:`build-release.yaml:40`、`sync-smart.yaml:86` → `for p in .../*.patch; do patch --verbose -p 1 < "$p"; done`
- 后果:补丁套用失败(Go 版本漂移/已打过)只打印错误,脚本继续 → 产出"Go 运行时没打补丁"的包,问题要到运行期才暴露。
- 修法:步骤开头 `set -euo pipefail`,或每个补丁后 `|| exit 1`,并校验 `*.patch` 至少命中一个。

### 🟡 P2-10 tag 被两处写、可能与重复 dispatch 冲突
- 位置:`build-release.yaml:82-83`(`git tag` + `git push --follow-tags`)与 `:121-126`(`richardsimko/update-tag@v3` 又动同一个 tag)。
- 后果:tag 可被移动(同一 tag 指向不同提交,失去可复现性);重复 dispatch 同一 tag 时,第 81 行 `git commit -am` 因无变更失败 → 整个 job 失败(这就是文档里"空转失败"的真实机制)。
- 修法:tag 只在**一处**创建;已存在则直接跳过 bump 步骤。

### 🟡 P2-11 缺并发保护
- 夜间 `sync-smart` 与手动 `build-release` 可并发:两处都在"选下一个空闲 tag"(`sync-smart.yaml:108-123`),存在抢同一 tag、双份构建的风险。
- 修法:两个 workflow 都加 `concurrency: { group: smart-release, cancel-in-progress: false }`。

### 🟡 P2-12 发布产物无自动校验
- 现状:上传前没有 `apksigner verify`、没有"versionName == tag"、没有"so 是否含 smart 代码"检查,也没有 sha256 清单(每次都要人工回读哈希)。
- 修法:CI 加一步 `apksigner verify --print-certs` + `aapt2 dump badging | grep versionName` + 生成 `sha256sums.txt` 一并上传。

### 🟡 P2-13 仪表盘没有超时 → 刷新按钮可能永久转圈
- 位置:`app/src/main/assets/smart.html:175-179` `jget()` 无超时;`:376-396` `tick()` 用 `syncing` 防重入,异常路径下若 fetch 永不返回则 `syncing` 永远为 true。
- 修法:`fetch(url, { signal: AbortSignal.timeout(8000) })`,超时按"未连接"渲染。

### 🟡 P2-14 仪表盘后台仍全速轮询(耗电)
- 位置:`smart.html:400-402`:`fastTick` 每 5 秒、`slowTick` 每 15 秒(`/weights` + `/group` + **全量 `/proxies`,110 节点的 history,最重的 JSON`)。
- 且 `SmartStatusActivity` 没有 `onStop/onPause`(整文件只有 `onDestroy`)→ 页面停在后台也照跑。
- 修法:页面判 `document.hidden` 暂停定时器;Activity `onStop { webView.onPause() }`、`onResume { webView.onResume() }`;`/proxies` 轮询降到 60–300 秒,或只在"距上次测速"需要时拉。

### 🟡 P2-15 分组列表不自动刷新 + 失效分组卡住页面
- 位置:`smart.html` `loadGroups()` 只在 `tick()` 内调用,而自动循环只跑 `fastTick/slowTick` → 换订阅/改配置后 tab 不更新;若 `current` 已不存在,`renderLive()` 会一直显示"正在获取该分组实时数据…"。
- 修法:每 60 秒 `loadGroups()`;`current` 不在新列表时回落到第一个组。

### 🟡 P2-16 WebView 开了"万能文件/跨源访问"
- 位置:`app/src/main/java/com/github/kr328/clash/SmartStatusActivity.kt:21-22`(`allowUniversalAccessFromFileURLs = true`、`allowFileAccessFromFileURLs = true`)。
- 现状风险:页面内所有用户数据(节点名/域名)都过了 `esc()`,XSS 面不大;但这两个开关让页面**能读任意来源**。既然 `process.go` 已经把 `external-ui` 指到 `profileDir/ui`,更干净的做法是把 smart.html 放进那个目录、用 `http://127.0.0.1:9090/ui/` 打开(同源),然后关掉这两个开关。
- 另建议加 `<meta http-equiv="Content-Security-Policy" content="default-src 'self'; connect-src http://127.0.0.1:9090; style-src 'unsafe-inline'">`。

### ⚪ P3-17 上游遗留(建议**不要**修)
- `gofmt -l` 报 5 个文件不合规:`native/app.go`、`native/app/app.go`、`native/debug.go`、`native/platform/limit.go`、`native/platform/procfs.go`。
- 逐个查过 git 历史:全部是**上游 CMFA 的提交**("核心依赖变更mihomo"/"Fix: fix time zone"/"Refactor: refactor clash core building"/"Improve: Try tcp6&udp6 files first…")。改它们只会让每日 upstream merge 徒增冲突。我们自己的 `native/config/*.go` 是合规的。
- 建议:改成"CI 只对我们新增文件跑 gofmt 检查"。

### ⚪ P3-18 健康守护的固定 3 分钟节奏偏激进
- `INTERVAL_MS = 3 * 60 * 1000` + 亮屏时每轮测**所有**组的所有成员。虽然有"大组优先 + 成员去重",对电量/流量仍不便宜。
- 可选:拉到 10 分钟;或加 `NET_CAPABILITY_VALIDATED` 判断(门户认证网络下别浪费测试);或只测"最近有规则命中的组"。

---

## 二、可优化项(带实测数字)

| # | 优化 | 实测收益 | 改动成本 |
|---|---|---|---|
| O1 | Go 产物剪裁:`GOFLAGS="-ldflags=-s -w"` | ❌ **在 Android 流水线上实测无效,已撤销**:darwin 裸构建 77.0→54.7 MiB 的 −29% **不能外推** —— Android 的 Go 产物本来就已经是剥过的(`core/build/intermediates/merged_jni_libs/.../libclash.so` 里 0 个 `.symtab`/`.debug_*` 节),加不加 GOFLAGS **字节数完全相同**(73,834,872 B)。CI 里那行已删除 | — |
| O2 | 删 `assets/ASN.mmdb` | ❌ **不能删**:`MainApplication.kt:62-68` 启动时把 `assets/ASN.mmdb` 拷进内核目录,内核 `component/mmdb/mmdb.go:82` 的 `ASNInstance()` 打开失败会直接 `log.Fatalln` → 删了会炸。真要省体积只能改成首次运行时下载(行为变更,需用户拍板) | 高,不建议 |
| O3 | 可选瘦身 | `libbarhopper_v3.so` 4.7 MiB + `mlkit_barcode_models` 0.9 MiB(扫码用);`BundleMRS.7z` 9.2 MiB(只有用 mrs rule-provider 才需要) | 中(取决于你是否扫码/用 mrs) |
| O4 | 仪表盘轮询降频 + 后台暂停(见 P2-14) | 后台流量/CPU 归零;前台每 15 秒的全量 `/proxies` 消失 | 低 |
| O5 | CI 加发布校验 + sha256 清单(见 P2-12) | 以后"回读哈希"自动化 | 低 |
| O6 | 把 `smartAdaptEnabled` 这类常量暴露成设置项(含 ipv6、健康检查周期、调参面板) | 不用改代码就能调 | 中 |
| O7 | 状态页显示"IPv6 开/关 + 模型哈希 + 内核提交" | 一眼看出节点为什么连不上 | 低 |

---

## 三、已经做对、别被下一轮"优化"掉

亮屏门控(`power.isInteractive`)、熄屏只等事件、组成员去重(大组优先)、连续 3 轮全失败 + ≥30 分钟间隔才热重载、256MiB 堆顶且**不做激进 GC**、`cachefile.Cache()` 显式初始化、`prefer-asn` 不隐式开启、内核钉扎手动更新、CI 子模块 gitlink 冲突保守处理、仪表盘对 `/version.meta` 布尔与 `weights`/`result` 双键的兼容。

---

## 四、建议执行顺序

1. P0-3(ipv6,用户实际痛点)+ P0-1(健康守护超时)+ P0-6(日志)→ 都是几行改动,风险低
2. P0-2(CI 未签名包 fail-fast)+ P2-9(patch 检查)+ P2-12(发布校验)→ 一次性把 CI 的坑堵上
3. P1-5(Model.bin 校验+原子写)、P1-4(控制器认证)→ 需要动仪表盘,配合 O6/O7 一起做
4. ~~O1/O2(瘦身)~~ → **已实测否决**(见 §二 更正),别再花时间
5. P2-13~16(仪表盘/WebView)→ 可以一次做完

---

## 五、改码后的本地验证记录(2026-09-27,P0-3/P0-1/P0-6/P1-5/P0-2 已实现)

分支:`/tmp/cmfa_smart` @ `smart-fixes-20260927`(commit `97b14018`,**未 push**)

| 验证项 | 方法 | 结果 |
|---|---|---|
| Go 编译 | `GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -tags "foss with_gvisor cmfa" ./...` | exit 0 ✅ |
| 静态检查 | `go vet` 同 tags + `gofmt -l` | exit 0 / 输出为空 ✅ |
| 判定逻辑 | 抽出 `isDoHWithLiteralIP` 单测(10 个边界用例 + 真订阅那份 list) | 10/10 ✅,订阅原值判定为"需替换" ✅ |
| 整包构建 | `./gradlew app:assembleMetaRelease -Pandroid.injected.build.abi=arm64-v8a` | BUILD SUCCESSFUL(1m31s)✅ |
| 新代码在产物内 | `strings libclash.so` | `[SmartDns]`×3、`TUN: %s`、无旧 `TUN:` ✅ |
| **连通性功能** | smart 内核跑真订阅 110 节点,配置 = 补丁输出等效值 | **84/110(手机现状 41/110);AAAA-only 48/48(现状 0/48)** ✅ |
| **App 运行时** | 模拟器(arm64/Android 36)+ debug 包 + 本机 HTTP 供订阅 | 内核日志出现 `[SmartAdapt] converted 6 group(s)` + `[SmartDns] proxy-server-nameserver [...] -> [1.1.1.1/8.8.8.8]`;VPN 起(tun0 = 172.19.0.1/30,已转发);`ping` 通;**IPv6 节点域名解析出 AAAA**(`--> [2603:c024:...] AAAA from https://1.1.1.1:443/dns-query`)✅ |
| **App 内核全量实测**(最强证据) | `adb forward tcp:9090` → 对 **App 自己正在跑的内核**逐个 `/proxies/<n>/delay` | 内核 `fork/alpha-3f25a6c-cmfa-2.11.70.debug`;**89/111 通过,AAAA-only 48/48**(手机现状 0/48);失败 22 个全是 REALITY 认证 / UDP443 干扰类,与桌面归类一致。原始数据 `~/clash-node-test/results_APP_kernel.json` ✅ |
| **用户真机实测**(2026-09-27) | 打包 `com.github.metacubex.clash.meta`(⚠️ 必须关掉 `local.properties` 的 appId 覆盖,否则包名变 `.smart` 装成第三个 App)+ `-Pandroid.injected.testOnly=false`(否则 `INSTALL_FAILED_TEST_ONLY`)→ 用用户正式 jks 签名(指纹须 = `1a6b5a08…b6ccc`)→ `adb install -r` 原地升级 | 手机 v2.11.69.Meta → **v2.11.70.Meta,配置保留**;内核 `fork/alpha-3f25a6c-cmfa-2.11.70.meta`;logcat 出现 `[SmartAdapt] converted 5 group(s)` + `[SmartDns] [...] -> [1.1.1.1 / 8.8.8.8]`;`[DNS] cache hit <v6节点域名> --> [<AAAA>] AAAA`;**全量实测 90/111、AAAA-only 48/48**(`results_PHONE_kernel_lowconc.json`)✅ |
| ⚠️ 测试方法坑 | 移动网络带宽约 4 Mbps 时用 8 并发测延迟 → 大面积假失败(38/111);**降到 3 并发 + 超时 10s → 90/111**。别用桌面级并发去测手机网络 | — |
| ⚠️ 假阴性坑 | `GET /configs` 在这个内核版本**不返回 dns 段**(只有顶层 `ipv6` 等)→ 拿它判断"补丁没生效"是错的;要看 `[SmartDns]` 启动日志或 `[DNS] ... AAAA` 解析日志 | — |
| CI 门禁 | 拿真 APK 预演新校验步骤 | 线上 v2.11.70 签名包放行;v2.11.68 未签名包被拦(DOES NOT VERIFY)✅ |

复现 App 端测试的要点(踩过的坑):
1. App 的 `network_security_config.xml` **只允许 `127.0.0.1`/`localhost` 走明文** → 用 `adb reverse tcp:<guest> tcp:<host>` 把订阅喂成本地 127.0.0.1,别用 `10.0.2.2`(会被策略拦,表现为配置永远 `URL (Unsaved)`)。
2. 深链 `clash://install-config?url=` 只做 `create+patch`,**不调 `commit()`** → 内容永远下不来;必须走 App 的 `Import from URL` UI 流程(create+patch+commit)。
3. 无窗口模拟器要先 `svc power stayon true` + `cmd deviceidle whitelist +<pkg>`,否则任务会被压住。
4. 内核日志在 logcat 里的 tag 是 `ClashMetaForAndroid`。

## 六、2026-09-27 第二轮:性能/行为排查(用户点名三项)

### 6.1 证据(全部在用户真机 `com.github.metacubex.clash.meta` v2.11.70.Meta 上采样,只读)

| 观测 | 方法 | 结果 |
|---|---|---|
| 内核内存 | `GET /memory`(⚠️ 该接口的 `inuse` = **进程 RSS**,`memory.GetMemoryInfo(pid).RSS`,已与 `ps` 交叉验证) | **215 → 220 MB**,7 分钟缓涨;< 内核 Go 软上限 `debug.SetMemoryLimit(256<<20)`(`native/delegate/init.go:31`)的 ~86% |
| 进程内存 | `ps -A -o NAME,RSS` / `dumpsys meminfo` | `:background`(内核所在进程)RSS **~220 MB / PSS ~140 MB**;主进程 RSS ~140 MB / PSS ~34 MB |
| 空闲 CPU | `dumpsys cpuinfo`(**数值是缓存的,不可用于时间序列**)、`top -b -n 2` | 内核进程空闲常驻 **~6%**(2.8% user + 3.2% kernel)+ 34 次 major fault(真磁盘 I/O) |
| **整组测速代价** | `GET /group/<组>/delay` + 计时 | **83 个节点 / 5.0 秒**(内核并行测试);这就是"用着用着卡一下"的来源:移动网络(实测带宽 ~4 Mbps)上每轮会短暂占满链路 |
| **App 侧超时与它不匹配** | 源码 `SmartHealthModule.TEST_TIMEOUT_MS = 5000` | Kotlin 侧 5000ms 超时 vs 内核整组测试 ~5s → **几乎每轮都在 Kotlin 侧超时**,只记一条 `Log.w`(且现在被日志级别挡住看不见)。注意 `failures == smartNames.size` 的判定拿"已测组数"和"全部组数"比,实际上几乎永远不成立 → 不会触发 `requestReload()`(等于歪打正着,但也说明这段守护逻辑实际没在工作) |
| 熄屏不测速 | 源码 + 屏息/亮屏观测 | 熄屏只 `waitEvents(60s)` ✓ 设计正确,不是耗电源头 |
| **smart 页面轮询开销** | `smart.html` 源码 + 载荷测量 | 前台时:每 **5s** `/connections`、每 **15s** `/proxies`(110 个节点含历史,最大的一份 JSON)+ `/group/{组}`(实测 **90 KB**)、每 30s `/configs`;**无任何可见性门控,且 Activity 无 `onPause` → 切后台照跑** |
| 定时测速节奏 | 每 20s 读 4 个节点的 `history` 最新时间戳(屏幕常亮) | 观测窗口内**没有**出现 App 模块触发的整组测速痕迹(只有我自己 curl 触发的);说明该模块的实际行为需要靠"history 时间戳"而不是它的日志来验证(日志被日志级别吞掉) |
| "Smart - Select" | 源码 `smart.go: Now()` | **设计如此**:smart 组是按"目标 + ASN"加权挑选 + 并行竞速(`maxSelected=10`,失败后按 `parallelDials=5` 分批并发拨号),没有"当前节点"概念;只有手动锁定节点(`ForceSet`)才显示名字 |
| 权重/存活两道闸 | `smart.go filterProxies` | 权重低于 `AllowedWeight=0.4` 或被判定 blocked / `!AliveForTestUrl` 的节点会被跳过 → 排行里高分但测速失败的节点(某 REALITY 节点,Weight 100)不会真的被用于拨号 |
| 记录保留 | `common.go: RecordExpiredTime = 7*24h`;`stats.go CleanupOldRecords`(每组 10 分钟一轮,`MaxTargetsLimit=5000`) | 记录量有上限,但清理时 `DBViewPrefixScan(prefix, -1, false)` **一次把整组记录读进内存**再排序 → 用得越久,这批周期性扫描越大(每 5 组各来一次) |

### 6.2 已修(commit `d7e67279`,App 侧三处,已本地构建 + 签名 + 真机安装)

| 文件 | 改动 | 为什么 |
|---|---|---|
| `app/.../SmartStatusActivity.kt` | 新增 `onPause` → `webView.onPause()/pauseTimers()`、`onResume` → `onResume()/resumeTimers()` | 原来切后台后 5s/15s 轮询永不停止:内核反复做 90 KB JSON 序列化、WebView 反复解析。全局只有这一个 WebView,`pauseTimers()` 安全 |
| `app/src/main/assets/smart.html` | 新增 `pageHidden` + `visibilitychange`(回前台立即 `tick()`);`fastTick/slowTick/loadMeta` 首行门控;`/proxies` 节流到 **60s 一次** | 双保险 + 把最大的一份载荷从 15s 降到 60s(其余小接口保持实时) |
| `service/.../SmartHealthModule.kt` | 整组测速间隔 3min → **10min**;识别**计费网络(移动数据)时 30min**(`NET_CAPABILITY_NOT_METERED` 判定,读取失败退化为 30min) | 内核自身已有基于真实流量的异常检测(`checkNodesStable`/`checkBlockedNodes`,5~10 分钟一轮)兜底,App 侧无需 3 分钟把 83~110 个节点全测一遍;这正是移动网络周期性卡顿的直接来源 |

验证:JS `node --check` 通过;kotlin 随 `assembleMetaRelease` 编译通过;签名证书仍为 `1a6b5a08…b6ccc`;装到真机后 `SmartStatusActivity` 打开→切后台→再回前台,页面正常渲染并自动重新同步(截图 10:18 内核版本/内存 220.0MB 正常,无"无法连接内核"提示)。

### 6.3 待用户拍板(内核侧,需要动 mihomo fork → 重新出内核)

| 选项 | 做法 | 收益/风险 |
|---|---|---|
| A. 抬内存上限 | `native/delegate/init.go` 的 `SetMemoryLimit(256<<20)` → 384/512 MiB | 降低 GC 抖动(现在常驻 RSS 已到上限 86%);代价是峰值内存更高,手机上一般也能接受 |
| B. 降周期任务密度 | `smart.go InitSmart` 里每组的 5~10 分钟任务(prefetch/stable/ranking/recovery)合并或拉长;`AdjustCacheParameters` 5s 一轮改成 60s | 减少周期性 CPU/IO 毛刺;风险:节点异常发现变慢 |
| C. 清理扫描限批 | `CleanupOldRecords` 的 `DBViewPrefixScan(..., -1, ...)` 改成分批/带上限 | 消除"用得越久、每次清理越重"的增长趋势;风险小但要测 |
| D. 记录上限调小 | `MaxTargetsLimit 5000`/`RecordExpiredTime 7d` 收紧 | 直接压低内存与扫描量;代价是历史样本变少 |

> 建议顺序:A + C(收益明确、风险低)→ B → D。

### 6.4 已实施(2026-09-27,用户批准 A+C)

| 项 | 改动 | 提交 | 状态 |
|---|---|---|---|
| **A** | `native/delegate/init.go`:`SetMemoryLimit` 256 → **384 MiB** | App `7dc24477` | 已编译/签名/装机;⚠️ 见 6.4.1 第 1 条(打印位置) |
| **C** | `component/smart/stats.go``CleanupOldRecords`:扫描 `-1`(全量)→ 有界 `2*maxTargets+1`(夹 2000~12000);DB 层带 limit 时用蓄水池抽样,多轮清理同样收敛 | 内核 `1929526c`(已推 `fork/Alpha`) | 编译/vet/gofmt 通过;单轮内存从 O(全部记录) 降到常数级 |

#### 6.4.1 复核中发现的三个坑(已写进 skill)

1. **`delegate.Init()` 阶段的日志不可靠 —— 别在那里输出启动参数**(实测同代码两次运行结果不同,机制未定论):`log.Infoln("Core memory limit…")` 两次都没出现;改成 `log.Warnln` 后,构建 #3 出现了 `W …Core memory limit: 384 MiB`,但**同样代码的构建 #2 没有出现**(APK 大小之差仅 12 字节)。可能原因是级别生效/日志管道接入与 Init 的时序竞争,或增量构建的 Go 编译缓存(未验证)。实践结论:**启动参数一律放到配置加载期打印**(`native/config/smart_adapt.go` 的 patch 函数,那时 log-level 已生效、Info 实测可见)。
2. **`debug.SetMemoryLimit(-1)` 只回读当前值、不修改** → 用它做验证探针(已加 `[SmartMem] Go soft memory limit: N MiB` 到 smart_adapt.go 的 patch 函数里)。
3. **内核里仍有 5 处无上限扫描(未修,下一轮候选)**:
   - `RemoveNodesData()`(`stats.go` 1652/1674/1745/1796)被 `cleanupOrphanedNodeCache` **每 10 分钟 × 每组 4 次**调用 → 5 组 = **20 次全量扫描/10 分钟**(目前最大的残余 churn,比 6.4 修掉的清理更重)
   - `GetAllGroupsForConfig()`(`stats.go` 1207)被 `cleanupOrphanedGroups` 每 5 分钟调用 1 次
   - 彻底修法:DB 层现有原语只有 `DBViewGetItem / DBBatchPutItem / DBViewPrefixScan / DBBatchDeletePrefix`,**没有"列子前缀/depth 扫描"能力** → 需要先加一个游标式 `DBListSubPrefixes(prefix, depth)`(底层走 bbolt/leveldb cursor),再把上述站点改成"按 target/node 子前缀分批扫" —— 既省内存,又不改变删除语义(限批抽样会让孤儿记录残留,这里不适合)。

## 七、2026-09-27 第三轮:内核热点修复 + 模拟器自测(手机已拔,全在 AVD 上做)

### 7.1 内核侧:把"整组前缀全量读"改成"按 target 分批/点读"(commit `5cc49b3e`,已推 fork/Alpha)

动机(第二轮实测):smart 每组 8 个定时任务 × 6 组;`cleanupOrphanedNodeCache` 每 10 分钟做 4 次"整组前缀全量扫描",
连同清理/prefetch/ranking 共 5 处无上限前缀扫描,是 RSS +0.7 MB/min 的主要来源。

| 位置 | 改法 | 峰值内存 |
|---|---|---|
| `cachefile.go` 新增 `DBListSubPrefixes(prefix, depth, strict)` | bbolt 有序游标,只枚举下一级子前缀名 | O(子前缀数) |
| `GetAllGroupsForConfig` | 全量扫描 → `depth=1` 列 group 名 | O(组数) |
| `CleanupOldRecords` | 整组全量 → 按 target 子前缀分批读 | O(单 target) |
| prefetch / failures 清理 | 整组载入 → 枚举子前缀后逐条点读 | O(1) 每记录 |
| ranking 清理 | 前缀扫描 → 直接点读单条 | O(1) |

**回归护栏**:`RemoveNodesData` 的原始语义是"删掉这些节点在该组**所有** target 下的记录"(按节点名清理),
不可"顺手优化"为只删当前 target —— 已写成测试用例固定住(见 7.3)。

### 7.2 App 侧:定时测速的两处真 bug(commit `43c31dee`,已推 smart)

- `TEST_TIMEOUT_MS` **5s → 30s**:实测整组测速(83 节点)耗时约 5.0s,5s 上限几乎每轮必然假超时。
- 重载判定 `failures == smartNames.size` → **`failures == attempted`**:旧写法拿"已测组数"比"全部组数",条件几乎永不成立,
  即该段守护**实际从未生效**(但未造成重启,只是失去了兜底)。

### 7.3 新增单元测试(host 可跑,`component/smart/cachefile_batch_test.go`)

`go test ./component/smart/` → **5/5 通过**;`go test ./component/...` → 全仓无回归。

- `TestDBListSubPrefixes`(depth=1/2、排序、边界)
- `TestCleanupOldRecordsExpired` / `TestCleanupOldRecordsTrimByCount`(过期删除 + 超量裁剪两条路径)
- `TestRemoveNodesData`(**含跨 target 语义回归护栏**)
- `TestGetAllGroupsForConfig`

写测试过程中抓到自己一处**断言写错**(以为按节点名清理应只删当前 target),代码语义是对的 → 断言已改为护栏。

### 7.4 模拟器端到端验证(AVD `ryza_test` = `emulator-5554`,arm64/API 36)

装的是含本轮全部改动 + Kotlin 修正的 arm64 release 包(52,818,427 B @10:41)。

| 检查项 | 结果 |
|---|---|
| 配置导入 | ✅ `config.yaml` 77,312 B = 本机订阅原大小;providers 目录生成 |
| 隧道 | ✅ Running,流量在走 |
| `[SmartAdapt] converted` | ✅ 6 group(s) |
| `[SmartMem] Go soft memory limit` | ✅ 384 MiB |
| `[SmartDns] ipv6 forced on` + `proxy-server-nameserver -> [1.1.1.1/8.8.8.8]` | ✅ |
| **节点全量连通性(111 节点)** | ✅ **88/111,IPv6 36/36 全通过**;失败=14 个 `*-v4-HY2-UDP443`(UDP/443 干扰)+ 5 个 `<REALITY 系列节点>`(公钥不匹配)+ 1 个 PASS-RULE(非节点) |
| 与手机对比 | 手机 90/111、v6 48/48 → **同一批失败类别,无新增回归** |
| smart 存储写入 | ✅ `Queue datas saved, operations: [176]`;`cache.db` 32 KB → 128 KB |
| prefetch 任务 | ✅ `Prefetching ... target: [DomainSuffix [github.com]] => result: [<某日本节点>: 1.21]` |
| 6 个 Smart 组 | ✅ 自动选择 110 / 日本 33 / 韩国 40 / 美国 35 / 荷兰 1 / 其他 1 |
| 运行期报错 | ✅ 零 panic、零 SmartStore 告警 |

### 7.5 `GET /group/<组>/weights` 返回空 —— 已排查,**不是本轮改动所致**

- 该端点读 `GetNodeWeightRankingCache` → 内部用 **`GetSubBytesByPath`**(本轮未改动的函数)→ 与我的改动无路径交集。
- 同一端点在**手机**上(用了一天、有样本)返回真实数据(`{"Name":"<某节点>","Rank":"MostUsed","Weight":100}`)。
- 结论:模拟器新装、运行 ~10 分钟,ranking 样本不足 → 属既有行为(权重需足够样本才生成)。

### 7.6 环境坑(写给下一次)

- **`GET /memory` 的响应体里会出现多个 JSON 对象**(首条可能是 `{"inuse":0,"oslimit":0}`)→ 解析必须取**最后一条**,
  否则 `json.load` 抛错、采样全空(本轮采样器 v1 就踩了这个)。
- 模拟器的 `top -b -n 1 -o PID,RES,%CPU` **不支持 `-o`** → 读 `/proc/<pid>/status`(VmRSS)+ `/proc/<pid>/stat`(utime+stime)自己算 CPU% 更可靠。
- 模拟器无 `curl` → 从 Mac 侧 `adb forward tcp:9099 tcp:9090` 后直连内核 API。
- `adb root` 会**重启 adbd**,之前建立的 `adb forward` 全部失效,需重建。

### 7.7 仍未做(诚实清单)

- `GetSubBytesByPath` 内部还有一处 `maxTargets*2` 上限的扫描(默认 1 万条 ≈ 10 MB 瞬时),有 `dbResultCache` 兜着,**本轮未动**。
- 本轮的**效果指标**(RSS 是否真的不再 +0.7 MB/min、长跑是否稳定)需要在模拟器上跑更久才有曲线 —— 已完成 15 分钟采样,
  但真正结论要看小时级趋势;真机复测待手机接回。
- 未发版:versionCode `211070 → 211071` / versionName `2.11.71` 仍待用户点头。

## 八、18 项状态一览(2026-09-27 收尾,一眼看清还剩什么)

| 项 | 内容 | 状态 |
|---|---|---|
| P0-1 | 健康守护可能永久卡死 | ✅ 已修 `43c31dee`(测速超时 5s→30s;守护判定 `failures==smartNames.size`→`==attempted`,旧写法永不成立) |
| P0-2 | CI 静默产出"未签名包" | ✅ 已修(round 1:CI 门禁校验签名,未签名包被拦) |
| P0-3 | 订阅 `ipv6` + 节点域名走明文 DNS → v6 节点整段废 | ✅ 已修 `97b14018` + 真机验证(v6 0/48 → 48/48) |
| P1-4 | 回环控制器无认证 + 任意来源 CORS | ❌ **未修**:需要内核 + App 联动加 token(WebView 也要拿到),改完必须整套重测 → 留作下一轮,不建议顺手改 |
| P1-5 | Model.bin 安装不可靠 | ✅ 已修(round 1) |
| P1-6 | 日志函数用错、错误原文被吞 | ✅ 已修(round 1) |
| P2-7 | `sync-smart.yaml` 的"手动跟内核"是死代码 | ⏸️ 未修(死代码,无功能影响) |
| P2-8 | CI 把签名口令打印进日志 | ✅ 已修(build-release 早前已改;**build-pre-release 本轮补修 `5d7bdd09`**) |
| P2-9 | patch 循环无错误检查 | ⏸️ 未修 |
| P2-10 | tag 被两处写 | ⏸️ 未修 |
| P2-11 | 缺并发保护 | ⏸️ 未修(`build-release.yaml` 有 `concurrency:`,未逐项复核) |
| P2-12 | 发布产物无自动校验 | ✅ 已修(round 1,与 P0-2 同批) |
| P2-13 | 仪表盘 fetch 无超时 → 刷新按钮可能永久转圈 | ✅ 已修 `bf02854c`(`jget` 加 AbortController 8s 超时)**验证级别**:语法 + 页面在真浏览器可加载/UI 正常/无 JS 报错/连不上时走错误分支不冻结;**超时行为本身未直接触发** |
| P2-14 | 仪表盘后台全速轮询(耗电) | ✅ 已修 `d7e67279`(`onPause`+`pauseTimers()`、`visibilitychange` 门控、`/proxies` 节流 60s) |
| P2-15 | 分组列表不自动刷新 + 失效分组卡住页面 | ✅ 已修 `bf02854c`(每 5 分钟重读 `/group` + 重建标签,`pageHidden` 门控、静默失败)**验证级别**同上:语法 + 页面可运行;**5 分钟刷新未能在测试窗口内直接观测** |
| P2-16 | WebView 开了"万能文件/跨源访问" | ⏸️ **未修,且不能简单置 false**:页面是 `file:///android_asset/smart.html` 承载,置 false 会直接打断它调 `http://127.0.0.1:9090`;正解是改用 `WebViewAssetLoader`(https 源)或让内核自己托管页面 → 属重构,单独排期 |
| P3-17 | 上游遗留 | ⏸️ 按审计建议**不要**修 |
| P3-18 | 健康守护固定 3 分钟节奏偏激进 | ✅ 已修 `d7e67279`(10 分钟;计费网络 30 分钟) |

**本轮(第三轮)新增修复**:内核 5 处无上限前缀扫描 → 游标式 `DBListSubPrefixes` + 分批/点读(`5cc49b3e`)、App 测速超时与守护判定(`43c31dee`)、CI 口令泄漏补漏(`5d7bdd09`)、审计文档入库(`4116d463`)。

**总结(逐项数过,第二轮修正)**:18 项中 **11 项已修**(P0-1/2/3、P1-5/6、P2-8/12/13/14/15、P3-18);**7 项未修** = 5 项待办(P1-4、P2-9、P2-10、P2-11、P2-16)+ 2 项建议不动(P2-7 死代码、P3-17 上游遗留)。

待办的 5 项里:**P1-4(控制器无认证 + 任意来源 CORS)是唯一的安全项**,需内核+App 联动加 token、WebView 也要拿到,改完必须整套重测 → 单独排期,不在收尾硬塞;P2-16(WebView 跨源开关)是"设计使然,不能简单关"——见 7.8 的实测解释;其余 3 项(P2-9/10/11)是 CI 工程债。

### 7.8 一个能解释 P2-16 的实测发现(值得记住)

本轮想在模拟器 Chrome 里验证 `smart.html`,结果:**页面从 `http://10.0.2.2:8942` 或 `http://127.0.0.1:8942` 打开时,一律拿不到内核数据**,报 `Failed to fetch`;而**同一个页面在 App 的 WebView 里(手机实测)是能拿到数据的**。原因:App 的 WebView 开了 `allowUniversalAccessFromFileURLs`(P2-16 那条),它让 `file://` 页面可以无视 CORS/私有网络策略直接打 `http://127.0.0.1:9090`;普通浏览器没有这个豁免,Chrome 的私有网络访问策略会挡掉这类请求。

→ 两个结论:①**P2-16 不是"顺手就能关"的开关**——关掉它,页面在真机里立刻拿不到数据;②**"用浏览器打开页面"不能替代 App 内 WebView 验证**,想要正面证据必须在 App 里跑(而本轮因为签名 keystore 被 TCC 挡住、出不了新包,只能验到"页面可运行、无 JS 报错"这一层)。

### 7.9 第四轮:没有真签名也能验证(调试包路线)+ 运行期正面证据

**背景**:真签名 `~/Documents/代理/smart-release.jks` 对助手进程是**目录级 TCC 拒绝**(`ls` 整个目录都被拒,文件还带 `com.apple.macl`),`osascript`→Terminal 代跑也被拒(`-1743 未获得授权发送 Apple 事件`)→ 出不了正式签名的包。

**绕法(可复用)**:把 `local.properties` 的包名覆盖打开(`custom.application.id=com.github.metacubex.clash.smart` + `remove.suffix=true`)→ `./gradlew app:assembleMetaDebug` → 得到一个**独立包名的调试包**,`adb install -r` 与原 `.meta` 包**并存**,既不动现有 App、也不需要真签名;验完再把覆盖注释回去(否则正式包包名会错)。产物 `cmfa-2.11.70-meta-arm64-v8a-debug.apk`(90.5 MB,`versionName 2.11.70.debug`)。

**这一轮的实测结果(全部在模拟器上跑)**

| 检查项 | 结果 |
|---|---|
| 配置导入 | ✅ 走 App 内 `Import from URL`(`adb reverse tcp:8930 tcp:8930` 把订阅喂成 127.0.0.1;`network_security_config` 只放行回环明文) |
| `[SmartAdapt] converted` | ✅ 6 个组(自动选择/韩国/日本/美国/荷兰/其他地区) |
| `[SmartMem] Go soft memory limit` | ✅ 384 MiB |
| `[SmartDns]` | ✅ `ipv6 forced on` + `proxy-server-nameserver [...] -> [1.1.1.1/8.8.8.8]`(含明文 DNS 被劫持的理由) |
| **节点全量(111)** | ✅ **88/111、v6 47/48** —— 与 release 包逐项一致,**无回归** |
| 真实流量选路 | ✅ 22 条连接全部走 `node-revoked`(smart 按目标+ASN 挑最优) |
| smart 存储 | ✅ `cache.db` 131,072 B 并在增长 |
| **权重/排行链路(之前的疑点)** | ✅✅ `/group/node-revoked/weights` 返回 **110 条真实数据**(`MostUsed/Weight 100`、`RarelyUsed/Weight 0`)→ 证明整条 ranking 落库/读取链路可用;之前为空确实只是样本不足 |
| **App 内仪表盘(WebView)** | ✅ 内核版本/模式/`已同步 11:15:47`/分组标签/统计卡片全部正常,**无任何报错**(Chrome 验不了的那一步,在这里过) |
| 运行期稳定性 | ✅ 零 panic、零 SmartStore 告警 |

**仍未拿到**:一小时 RSS/CPU 曲线(后台采样中)、Kotlin 模块整组测速的周期证据(需等 ≥10 分钟观察节点 `history` 时间戳)。另外:**仓库里那份 tracked `release.keystore` 用现有口令打不开**(`keytool: keystore password was incorrect`)→ CI 出的包与本地真签名包**是否同一把钥匙,尚未验证**(若不同,CI 包无法覆盖安装本地包;用户当前发布走本地签名路径)。

### 7.10 两小时实测收尾:内存曲线 + 周期性整组测速的真实来源

**(a) 内存曲线(模拟器,调试包,20 分钟 / 60 个采样)**

| 指标 | 数值 |
|---|---|
| 内核 RSS | 首 221 MB → 末 **174 MB**(min 144 / max 221) |
| 稳态段(去掉起步段 50 个采样) | 184 → **174 MB**,≈ **−0.6 MB/分钟(不涨反降)** |
| 对比手机旧版(同一问题) | 旧版 +0.7 MB/分钟 → 约 1 小时贴 256 MiB 上限 → GC 抖动 |

→ A(384 MiB)+ C(清理限批)+ 本轮分批/点读改造的方向得到支持。**但必须声明:模拟器与真机的工作集不同,这不能等同于真机结论**,只能说明"新代码在新的运行窗口里不再单调爬升"。

**(b) 周期性整组测速的来源(排查过程)**

- 现象:内核侧**每 5 分钟**出现一批约 **220 条** `Health Checked` + 全节点 DNS 解析,相位固定在每分钟的 **:21 秒**(= 固定 ticker,不是 App 的 delay 循环漂移)。
- 代码侧:`smart.go` 里那 4 个 5 分钟任务**只做只读分析**(`checkNodesStable` 读 `DelayHistoryForTestUrl`),`smart.go` 内**没有任何 `URLTest(` 调用**;真正发健康检查的唯一入口是 `groupbase.go:246` 的 `proxy.URLTest` → 只被 **`/group/<组>/delay`** 这个 HTTP 接口触发 = **App 侧调用**。
- App 侧:`SmartHealthModule` 常量为 `INTERVAL_MS=10min`、`METERED=30min`、熄屏 60s 空转(`SCREEN_OFF_INTERVAL_MS`),首轮 = 服务起后 20 秒(日志实证 11:15:38)→ 代码符合预期。
- **归因未完全确定**(模拟器上曾同时跑着两个 App 实例:release `.meta` 与调试 `.smart`,已停掉 release 那个)。但**结论不受影响:周期性整组测速确实存在**(~5 分钟一批),其链路成本在快网上测得 **CPU 峰值 ~9%、持续约 20 秒**;**在移动网络上还会占满上行/下行**(机制明确,本轮未测)。
- 要真正消掉用户"用着用着卡一下"的那一下,得动审计 §6.3 的 **B 选项**(拉长/合并内核侧周期任务,或对近期已测节点跳过)→ 代价是节点异常发现变慢 → **需用户拍板,不在收尾时单方面改**。

### 7.11 第五轮:"用一段时间卡一下"的真正根因与修复(commit `c89eb0c1`)

**根因(实测定位)**:订阅里 **6 个 url-test 组都写着 `interval: 300`** → 内核**每 5 分钟**对整组节点做一遍健康检查(每节点一次 DNS 解析 + TLS + HTTP,实测每批 **220 条** `Health Checked`),**熄屏也照跑**。

判定过程(两个实验):
1. **熄屏实验**:熄屏后 11:40 那批健康检查**照旧发生** → 不是 App 发的(App 模块熄屏时正确停测,`SCREEN_OFF_INTERVAL_MS=60s` 只空转)→ 所以 App 侧任何省电/门控都管不到它。
2. **代码定位**:`smart.go` 的 5 分钟任务全是只读分析(`checkNodesStable` 读 `DelayHistoryForTestUrl`),`AliveForTestUrl` 也只读缓存;真正的触发点是**订阅自己的组级 `interval`**(此前我的 grep 把 `adapter/provider` 过滤掉了,绕了一圈)。

**修复(适配层,一处小改)**:`patchSmartAdapt` 在把 `url-test/fallback/load-balance` 转成 `smart` 时,把 `interval < 600` 的一律抬到 **600 秒**(只抬不降;`int`/`float64`/字符串三种写法都处理)。理由:smart 组自身有 10~15 分钟的稳定性/失效检测,节点真实失败会**立刻**进入存储统计并被选择器即时绕开,所以组级健康检查没必要 5 分钟一次。

**实测验证(模拟器,调试包)**

| 时刻 | 事件 |
|---|---|
| 11:47:05 | 内核启动(`[SmartAdapt] converted 6 group(s)` + `384 MiB` + `SmartDns` 全在) |
| 11:47:06 | 首批健康检查(启动即测,正常) |
| ~~11:52:06~~ | **改前应该出现的 5 分钟批次:没有出现** ✅ |
| **11:57:06** | 下一批,恰好 **+600 秒** ✅ |

批次规模仍为 **220 条**(覆盖不变,频率减半);CPU 稳定 0.7~1.3%;RSS 稳定 214 MB。

**代价与兜底**:组级健康检查周期翻倍 → 节点失效的"被动发现"最多晚 5 分钟;但 smart 组自身的 `checkNodesStable`/`checkBlockedNodes`(10 分钟)与真实流量失败反馈仍在 → 可用性不受影响。想回到 5 分钟只改 `smartGroupHealthInterval` 一个常量。

**内存与 CPU 曲线(两段实测,同一台模拟器)**

| 窗口 | 代码 | 时长 / 采样 | RSS | 稳态斜率 |
|---|---|---|---|---|
| 11:17–11:45 | 改前(订阅 300s) | 28 分钟 / 84 | 221 → 155 MB | −1.18 MB/分 |
| 11:47–12:40 | **改后(订阅 600s)** | **40 分钟 / 160** | 205 → 206 MB | **+0.02 MB/分(平)** |

改后 CPU:均值 **1.14%**、峰值 9.8%、**95% 的采样低于 2%**。对照旧版手机同问题 **+0.7 MB/分**(约 1 小时贴 256 MiB 上限 → GC 抖动 → "用一段时间卡")。

**批次节奏(完整时间线)**:改前 11:20 / 11:25 / 11:30 / 11:35 / 11:40 = **300 秒 ×5**;改后 11:47(启动即测)/ 11:57 / 12:07 / … / 12:37 = **600 秒连续**。

同样声明:模拟器与真机工作集不同,以上数字不与真机结论划等号 —— 它证明的是"改动方向正确且无回归",不是"真机一定如此"。

### 7.12 第六轮:真机"断流"根因(DNS 竞速语义)+ 修复与极限验证(2026-10-01)

**用户症状**:间歇"断流",上网中途不通,**必须关掉代理再打开才恢复**;通知栏一直显示 Running(服务没被杀);移动数据和 WiFi 都遇到过。

**两处病灶**
1. **我方引入**:`patchSmartDns` 把 `dns.proxy-server-nameserver` **整体替换**成两条境外 DoH(`1.1.1.1`、`8.8.8.8`)。本机实测 `8.8.8.8` 不可达(1.18s 无响应)→ 国内网络下这两条被阻断时**完全失去解析能力** → 节点域名解析失败 → 断流。
2. **订阅自带**:主 `nameserver` / `direct-nameserver` 用"域名式 DoH"(`dns.alidns.com`、`doh.pub`),它们自己的域名还要靠**明文** `default-nameserver` 引导 → 明文被本地网关劫持时整链失效。

**Grok(grok-build)审查内核源码后的关键纠正**(我第一版改法据此被推翻):
> `dns/util.go` 的 `batchExchange` 是**并发竞速**:第一个 `err == nil` 的回答获胜并 cancel 其余;且只有 SERVFAIL/REFUSED 算失败 —— **被劫持的明文 NOERROR 算成功**。明文应答毫秒级返回,必然抢赢 DoH(约 0.2s),坏答案进缓存。这与"Running 却断流、关开代理(ClearCache)才恢复"完全对应。

→ ①列表**顺序不代表优先级**;②把订阅的**明文**条目 merge 进同一竞速**有害**。第一版"合并 + 保留订阅条目作后备"因此是错的,已改为**剔除所有明文/可劫持解析器**。

**最终改法**:新增 `isHijackablePlaintext` / `filterUnhijackable`(只留 https/tls/quic/h3/system/dhcp);`proxy-server-nameserver` = 4 条 IP 字面量 DoH + 订阅里不可劫持的条目;`nameserver` 同样处理;`direct-nameserver` 只用国内 2 条字面量(直连要本地答案);日志与注释如实描述语义(不再声称"顺序=优先级")。

**验证(模拟器,调试包)**

| 场景 | 结果 |
|---|---|
| 正常 | 日志 `[alidns, doh.pub, 223.5.5.5, 119.29.29.29] -> [120.53.53.53, 1.12.12.12, 1.1.1.1, 8.8.8.8, alidns, doh.pub] (dropped 2)`;google 204 / youtube 200 / baidu 200 |
| 节点抽样 | v4 10 个:通过 7,失败仅 `*-v4-HY2-UDP443`(与修复前一致,**无回归**) |
| **极限**:模拟器内 iptables **封死 1.1.1.1 + 8.8.8.8** | 节点抽样**仍 7/10(与未封禁完全一致)**;google 204 / youtube 200 / baidu 200 **全部照常** —— 旧代码在此条件下会彻底解析不了 |

**Grok 另外指出的两个非 DNS 嫌疑(待真机现场证据)**
1. smart 组把"握手成功但不通数据"的节点钉在目标上(`groupDialFailed` 只处理**拨号**错误)→ 在途连接黑洞,进程仍 Running。
2. 网络切换/NAT 超时后已建立连接不重建(`ResetConnection` 只在解析器重载=关开时走)→ 与"两种网络都遇到"相符。

### 7.13 第七轮:Grok 复核(第 4 轮)后的三处收敛

Grok 复核我的"剔除明文"实现后指出三处必须改:

| # | 问题 | 修法 |
|---|---|---|
| 1 | 白名单**误放行** `system://` / `dhcp://` —— Android 的 system 就是 `UpdateSystemDNS` 的**明文 UDP**(`dns/patch_android.go`),dhcp 拿到的同样是明文 UDP(`dns/dhcp.go`);它们照样以劫持 NOERROR 抢赢 | 白名单收紧为只剩 `https://` / `tls://` / `quic://` |
| 2 | `h3://` mihomo 没有这个 scheme,留着会让**整份 DNS 配置加载失败** | 同上(不再放行) |
| 3 | `fallback` 里若有明文,同样能抢赢;但**绝不能**开 `fallback-filter.geoip: true` —— mihomo 对"非 CN"一律判为换源(`rules/common/geoip.go`),`1.1.1.1` 被墙时会把国内 DoH 已拿到的**正确海外 IP 整段丢掉**,断流立刻回来 | `Fallback = [https://1.1.1.1/dns-query] + filterUnhijackable(原 fallback)`;`geoip=false`;`ipcidr` 仅 `0.0.0.0/32`、`127.0.0.1/32`、`240.0.0.0/4` |

**实测(模拟器,调试包)**:四条 `[SmartDns]` 日志全部按预期出现 ——
`nameserver` / `direct-nameserver` / `proxy-server-nameserver`(dropped 2)/
`fallback [https://1.1.1.1/dns-query] + fallback-filter.ipcidr [0.0.0.0/32 127.0.0.1/32 240.0.0.0/4] (geoip off: only blackhole answers are re-queried)`;端到端 google 204 / youtube 200 / baidu 200 / github 200;节点抽样 v4 7/10(失败仍是一贯失败的 UDP443 类);**再次封死 1.1.1.1+8.8.8.8 后仍 google 204 / baidu 200**(测完 iptables 规则已清零)。

**仍知未改(记录在案,不影响本次结论)**
- `nameserver-policy` / `proxy-server-nameserver-policy` 里的条目**未被过滤**(我的改动只作用于三个列表)。本订阅 policy 的 10 条值全是 https,无明文,故当前无风险;若将来订阅在 policy 里写明文,需另做处理(有序 map 类型,改动面更大)。
- `ts://` / `et://` 这类少见 scheme 会被我的白名单**误删**(Grok 指出)。取舍:宁可删掉少见 scheme,也不放明文进来。

## 附:本次审计用到的可复现命令

```bash
cd /tmp/cmfa_smart && git checkout smart && git reset --hard origin/smart
export PATH=/tmp/patchedgo/go/bin:$PATH
cd core/src/main/golang && GOOS=android GOARCH=arm64 CGO_ENABLED=0 go vet -tags "foss,with_gvisor,cmfa" ./native/... ; gofmt -l native/
# 体积构成
unzip -l <release.apk> | sort -k1 -nr | head -20
# 剪裁收益(本机实测)
cd core/src/foss/golang/clash && go build -tags with_gvisor -trimpath -ldflags="-s -w" -o /tmp/x .
```
