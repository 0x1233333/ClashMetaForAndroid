package config

import (
	_ "embed"
	"fmt"
	"os"
	"path/filepath"
	"strings"

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

// ensureSmartModel installs the bundled model into the kernel home dir
// where component/smart/lightgbm looks it up (C.Path.SmartModel()).
func ensureSmartModel() {
	dir := C.Path.HomeDir()
	if dir == "" {
		return
	}

	target := filepath.Join(dir, "Model.bin")

	if _, err := os.Stat(target); err == nil {
		return
	}

	if err := os.MkdirAll(dir, 0o755); err != nil {
		log.Warnln("[SmartAdapt] create home dir: %s", err.Error())

		return
	}

	if err := os.WriteFile(target, smartModelBin, 0o644); err != nil {
		log.Warnln("[SmartAdapt] write Model.bin: %s", err.Error())
	} else {
		log.Infoln("[SmartAdapt] Model.bin installed: %s (%d bytes)", target, len(smartModelBin))
	}
}

func patchSmartAdapt(cfg *config.RawConfig, _ string) error {
	if !smartAdaptEnabled || cfg == nil || len(cfg.ProxyGroup) == 0 {
		return nil
	}

	ensureSmartModel()

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

		name, _ := g["name"].(string)
		converted = append(converted, fmt.Sprintf("%s(%s→smart)", name, t))
	}

	if len(converted) > 0 {
		log.Infoln("[SmartAdapt] converted %d group(s): %s", len(converted), strings.Join(converted, ", "))
	}

	return nil
}
