package delegate

import (
	"errors"
	"fmt"
	"runtime/debug"
	"strings"
	"syscall"

	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/log"

	"cfa/native/app"
	"cfa/native/platform"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/constant"
)

var errBlocked = errors.New("blocked")

func Init(home, versionName, gitVersion string, platformVersion int) {
	log.Infoln("Init core, home: %s, versionName: %s, gitVersion: %s, platformVersion: %d", home, versionName, gitVersion, platformVersion)
	constant.SetHomeDir(home)

	// Mobile memory policy: normal GC pacing (no extra CPU/battery cost) plus a
	// soft heap limit. Durable smart state lives on disk (cache.db); memory holds
	// read caches and a bounded write queue. Under heavy traffic the runtime only
	// starts GC-ing harder as the heap approaches the limit, so RSS stays bounded
	// (LMK-friendly) while idle cost stays zero.
	//
	// 实测(2026-09-27,真机):内核长时间运行后 RSS 已到 215~220MiB,即旧 256MiB 上限的
	// ~86%,且以 ~0.7MiB/分钟继续爬升 → 约 1 小时后贴着上限持续 GC(CPU 抖、网络卡),
	// 而重载/重启会把它降回 ~212MiB(用户感受就是"重启一下就不卡了")。上限是 GC 的
	// 目标线而非硬上限,抬到 384MiB 只是给稳态数据留出余量,空闲时不额外占用。
	debug.SetMemoryLimit(384 << 20)
	// 用 Warn 级别:Init() 执行时配置里的 log-level 还没生效(此时是默认级别),
	// Info 会被吞掉,导致这条关键启动参数在真机 logcat 里看不到。
	log.Warnln("Core memory limit: %d MiB", 384)
	// gitVersion = ${CURRENT_BRANCH}_${COMMIT_HASH}_${COMPILE_TIME}
	if versions := strings.Split(gitVersion, "_"); len(versions) == 3 {
		constant.Version = fmt.Sprintf("%s-%s-CMFA-%s", strings.ToLower(versions[0]), versions[1], strings.ToLower(versionName))
		constant.BuildTime = versions[2]
	} else {
		constant.Version = gitVersion
	}
	constant.Version = strings.ToLower(constant.Version)
	app.ApplyVersionName(versionName)
	app.ApplyPlatformVersion(platformVersion)

	process.DefaultPackageNameResolver = func(metadata *constant.Metadata) (string, error) {
		src, dst := metadata.RawSrcAddr, metadata.RawDstAddr

		if src == nil || dst == nil {
			return "", process.ErrInvalidNetwork
		}

		uid := app.QuerySocketUid(metadata.RawSrcAddr, metadata.RawDstAddr)
		pkg := app.QueryAppByUid(uid)

		log.Debugln("[PKG] %s --> %s by %d[%s]", metadata.SourceAddress(), metadata.RemoteAddress(), uid, pkg)

		return pkg, nil
	}

	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		if platform.ShouldBlockConnection() {
			return errBlocked
		}

		return conn.Control(func(fd uintptr) {
			app.MarkSocket(int(fd))
		})
	}
}
