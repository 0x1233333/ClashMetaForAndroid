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
	// 256MiB soft heap limit. Durable smart state lives on disk (cache.db);
	// memory holds read caches and a bounded write queue. Under heavy traffic
	// the runtime only starts GC-ing harder as the heap approaches the limit —
	// RSS stays bounded (LMK-friendly) while idle cost stays zero.
	debug.SetMemoryLimit(256 << 20)
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
