package tunnel

import (
	"context"
	"sync"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

func HealthCheck(name string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}

// URLTestGroup runs a group-level URLTest, refreshing every member's
// alive flag and delay history (unlike HealthCheck, which only covers
// providers and is a no-op for groups with static proxies).
// Returns an error when every member failed (e.g. all timeout).
func URLTestGroup(name string) error {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request url test for `%s`: not found", name)

		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request url test for `%s`: invalid type %s", name, p.Type().String())

		return nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), C.DefaultTCPTimeout*4)
	defer cancel()

	_, err := g.URLTest(ctx, C.DefaultTestURL, utils.IntRanges[uint16]{})
	if err != nil {
		log.Warnln("URL test group `%s`: %s", name, err.Error())

		return err
	}

	log.Infoln("URL test group `%s` finished", name)

	return nil
}
