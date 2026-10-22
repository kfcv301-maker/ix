// Package runtimeconfig owns the private, dynamically supplied node settings.
// Its documents and errors must never be copied into public node telemetry.
package runtimeconfig

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"
)

type DDNS struct {
	Enabled         bool   `json:"enabled"`
	Provider        string `json:"provider"`
	APIToken        string `json:"apiToken"`
	RecordName      string `json:"recordName"`
	IntervalSeconds int    `json:"intervalSeconds"`
}

type Config struct {
	SchemaVersion        int    `json:"schemaVersion"`
	Revision             string `json:"revision"`
	DDNS                 DDNS   `json:"ddns"`
	TCPTuningProfile     string `json:"tcpTuningProfile,omitempty"`
	TCPTuningAutoEnabled bool   `json:"tcpTuningAutoEnabled"`
	TCPTuningProfileMin  string `json:"tcpTuningProfileMin,omitempty"`
	TCPTuningProfileMax  string `json:"tcpTuningProfileMax,omitempty"`
}

var domainPattern = regexp.MustCompile(`^([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`)

func (c *Config) validate() error {
	if c.SchemaVersion != 1 {
		return errors.New("unsupported node runtime configuration schema")
	}
	if c.DDNS.IntervalSeconds == 0 {
		c.DDNS.IntervalSeconds = 60
	}
	if c.DDNS.IntervalSeconds < 30 || c.DDNS.IntervalSeconds > 86400 {
		return errors.New("invalid DDNS update interval")
	}
	if !c.DDNS.Enabled {
		// Disabled configurations should not retain a revoked Cloudflare token.
		c.DDNS.APIToken = ""
		return nil
	}
	c.DDNS.RecordName = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(c.DDNS.RecordName), "."))
	if c.DDNS.Provider != "cloudflare" || len(c.DDNS.RecordName) > 253 || !domainPattern.MatchString(c.DDNS.RecordName) {
		return errors.New("invalid DDNS provider or record name")
	}
	if c.DDNS.APIToken == "" || len(c.DDNS.APIToken) > 4096 || strings.ContainsAny(c.DDNS.APIToken, "\r\n\x00") {
		return errors.New("invalid DDNS credential")
	}
	return nil
}

// Shared with protocol-switch writes: preserve unknown fields and prevent a
// concurrent switch operation from erasing private runtime settings.
var localConfigMu sync.Mutex

func UpdateLocal(path string, mutate func(map[string]any) error) error {
	localConfigMu.Lock()
	defer localConfigMu.Unlock()
	data, err := os.ReadFile(path)
	if err != nil {
		return errors.New("cannot read private node configuration")
	}
	var doc map[string]any
	if json.Unmarshal(data, &doc) != nil || doc == nil {
		return errors.New("invalid local node configuration")
	}
	if err := mutate(doc); err != nil {
		return err
	}
	data, err = json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return errors.New("cannot encode private node configuration")
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".node-config-*")
	if err != nil {
		return errors.New("cannot create private node configuration")
	}
	defer os.Remove(f.Name())
	if err = f.Chmod(0600); err == nil {
		_, err = f.Write(data)
	}
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		return errors.New("cannot persist private node configuration")
	}
	if err = os.Rename(f.Name(), path); err != nil {
		return errors.New("cannot replace private node configuration")
	}
	if dir, openErr := os.Open(filepath.Dir(path)); openErr == nil {
		_ = dir.Sync()
		_ = dir.Close()
	}
	return nil
}

type Manager struct {
	ctx                    context.Context
	cancel                 context.CancelFunc
	path, endpoint, secret string
	client                 *http.Client
	mu                     sync.Mutex
	current                *Config
	generation             uint64
	workerCancel           context.CancelFunc
	workerDone             chan struct{}
	refreshMu              sync.Mutex
	retireLegacy           func() error
	updater                func(context.Context, DDNS) error
}

func privateHTTPClient() *http.Client {
	return &http.Client{Timeout: 20 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
}

func New(parent context.Context, path, panel, secret string) *Manager {
	ctx, cancel := context.WithCancel(parent)
	if !strings.Contains(panel, "://") {
		panel = "http://" + panel
	}
	u, err := url.Parse(panel)
	endpoint := ""
	if err == nil && u.Host != "" && u.User == nil && (u.Scheme == "http" || u.Scheme == "https") {
		u.Path, u.RawPath, u.RawQuery, u.Fragment = "/api/v1/node/runtime-config", "", "", ""
		endpoint = u.String()
	}
	m := &Manager{ctx: ctx, cancel: cancel, path: path, endpoint: endpoint, secret: secret, client: privateHTTPClient(), retireLegacy: retireLegacyDDNS}
	m.updater = newCloudflareUpdater().Sync
	return m
}

func (m *Manager) Start() {
	// Continue the last validated configuration even during a panel outage.
	if b, err := os.ReadFile(m.path); err == nil {
		var doc struct {
			Runtime *Config `json:"runtime"`
		}
		if json.Unmarshal(b, &doc) == nil && doc.Runtime != nil {
			_ = m.Apply(*doc.Runtime)
		}
	}
	go func() {
		m.Refresh()
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-m.ctx.Done():
				return
			case <-ticker.C:
				m.Refresh()
			}
		}
	}()
}

func (m *Manager) Stop() {
	m.cancel()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.workerCancel != nil {
		m.workerCancel()
	}
	if m.workerDone != nil {
		<-m.workerDone
	}
}

func (m *Manager) Refresh() {
	// Reconnect and timer can coincide. Keep only one authenticated request.
	if !m.refreshMu.TryLock() {
		return
	}
	defer m.refreshMu.Unlock()
	if m.endpoint == "" {
		return
	}
	m.mu.Lock()
	generation := m.generation
	m.mu.Unlock()
	req, err := http.NewRequestWithContext(m.ctx, http.MethodGet, m.endpoint, nil)
	if err != nil {
		return
	}
	req.Header.Set("Authorization", "Bearer "+m.secret)
	req.Header.Set("Cache-Control", "no-store")
	resp, err := m.client.Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()
	// Old panels return 404. Preserve existing DDNS until a new panel has
	// supplied an authenticated, complete and validated configuration.
	if resp.StatusCode != http.StatusOK {
		return
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 65537))
	if err != nil || len(b) > 65536 {
		return
	}
	var cfg Config
	if json.Unmarshal(b, &cfg) != nil {
		return
	}
	if cfg.validate() != nil {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	// A WS push may have applied a newer configuration while this HTTP request
	// was in flight. Its ACK is final: the older snapshot must not undo it.
	if m.generation != generation {
		return
	}
	_ = m.applyLocked(cfg)
}

func (m *Manager) Apply(cfg Config) error {
	if err := cfg.validate(); err != nil {
		return err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.applyLocked(cfg)
}

func (m *Manager) applyLocked(cfg Config) error {
	if m.ctx.Err() != nil {
		return errors.New("node configuration manager stopped")
	}
	if m.current != nil && reflect.DeepEqual(*m.current, cfg) {
		return nil
	}
	// Establish that private persistence works before interrupting the previous
	// DNS owner. Restore only this field on a failed handover, preserving any
	// concurrent protocol-switch changes to the same JSON document.
	var oldRuntime any
	var hadRuntime bool
	if err := UpdateLocal(m.path, func(doc map[string]any) error {
		oldRuntime, hadRuntime = doc["runtime"]
		doc["runtime"] = cfg
		return nil
	}); err != nil {
		return err
	}
	if err := m.retireLegacy(); err != nil {
		if rollbackErr := UpdateLocal(m.path, func(doc map[string]any) error {
			if hadRuntime {
				doc["runtime"] = oldRuntime
			} else {
				delete(doc, "runtime")
			}
			return nil
		}); rollbackErr != nil {
			return errors.New("DDNS handover and private configuration rollback failed")
		}
		return err
	}
	if m.workerCancel != nil {
		m.workerCancel()
	}
	if m.workerDone != nil {
		<-m.workerDone
	}
	m.current = &cfg
	m.generation++
	if cfg.DDNS.Enabled {
		ctx, cancel := context.WithCancel(m.ctx)
		m.workerCancel = cancel
		done := make(chan struct{})
		m.workerDone = done
		go func() { defer close(done); m.runDDNS(ctx, cfg.DDNS) }()
	} else {
		m.workerCancel = nil
		m.workerDone = nil
	}
	return nil
}

func (m *Manager) runDDNS(ctx context.Context, cfg DDNS) {
	for {
		if ctx.Err() != nil {
			return
		}
		if err := m.updater(ctx, cfg); err != nil && ctx.Err() == nil {
			// Errors deliberately exclude HTTP response bodies, URLs and secrets.
			fmt.Println("DDNS synchronization failed; retrying with saved configuration")
		}
		timer := time.NewTimer(time.Duration(cfg.IntervalSeconds) * time.Second)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}

func retireLegacyDDNS() error {
	if runtime.GOOS != "linux" {
		return nil
	}
	if _, err := os.Stat("/etc/systemd/system/flux-panel-ddns.timer"); os.IsNotExist(err) {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	wasEnabled := exec.CommandContext(ctx, "systemctl", "is-enabled", "--quiet", "flux-panel-ddns.timer").Run() == nil
	wasActive := exec.CommandContext(ctx, "systemctl", "is-active", "--quiet", "flux-panel-ddns.timer").Run() == nil
	restore := func() {
		restoreCtx, restoreCancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer restoreCancel()
		if wasEnabled {
			_ = exec.CommandContext(restoreCtx, "systemctl", "enable", "flux-panel-ddns.timer").Run()
		}
		if wasActive {
			_ = exec.CommandContext(restoreCtx, "systemctl", "start", "flux-panel-ddns.timer").Run()
		}
	}
	if exec.CommandContext(ctx, "systemctl", "disable", "--now", "flux-panel-ddns.timer").Run() != nil {
		restore()
		return errors.New("cannot disable legacy DDNS timer")
	}
	// A oneshot that already started must finish/stop before changing ownership.
	if exec.CommandContext(ctx, "systemctl", "stop", "flux-panel-ddns.service").Run() != nil {
		restore()
		return errors.New("cannot stop legacy DDNS worker")
	}
	return nil
}
