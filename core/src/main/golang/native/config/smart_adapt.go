package config

import (
	_ "embed"
	"encoding/json"
	"fmt"
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

	if !smartAdaptEnabled || cfg == nil || len(cfg.ProxyGroup) == 0 {
		return nil
	}

	ensureSmartModel()
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
