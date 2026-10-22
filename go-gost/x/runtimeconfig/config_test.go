package runtimeconfig

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

func privateConfig(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"addr":"https://panel.invalid","secret":"node-secret","http":0,"extension":{"keep":true}}`), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}
func validConfig() Config {
	return Config{SchemaVersion: 1, Revision: "one", DDNS: DDNS{Enabled: true, Provider: "cloudflare", APIToken: "token-one", RecordName: "node.example.invalid", IntervalSeconds: 60}}
}
func testManager(t *testing.T) *Manager {
	t.Helper()
	m := New(context.Background(), privateConfig(t), "https://panel.invalid", "node-secret")
	m.retireLegacy = func() error { return nil }
	m.updater = func(context.Context, DDNS) error { return nil }
	t.Cleanup(m.Stop)
	return m
}
func receive(t *testing.T, ch <-chan DDNS) DDNS {
	t.Helper()
	select {
	case c := <-ch:
		return c
	case <-time.After(2 * time.Second):
		t.Fatal("DDNS worker did not apply configuration")
		return DDNS{}
	}
}

func TestHotApplyRotateAndDisable(t *testing.T) {
	m := testManager(t)
	started := make(chan DDNS, 4)
	stopped := make(chan struct{}, 4)
	m.updater = func(ctx context.Context, c DDNS) error {
		started <- c
		<-ctx.Done()
		stopped <- struct{}{}
		return ctx.Err()
	}
	cfg := validConfig()
	if err := m.Apply(cfg); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, started); got.APIToken != "token-one" {
		t.Fatal("wrong credential")
	}
	before, _ := os.Stat(m.path)
	if err := m.Apply(cfg); err != nil {
		t.Fatal(err)
	}
	after, _ := os.Stat(m.path)
	if before.ModTime() != after.ModTime() {
		t.Fatal("unchanged configuration rewritten")
	}
	cfg.Revision = "two"
	cfg.DDNS.APIToken = "token-two"
	cfg.DDNS.RecordName = "new.example.invalid"
	cfg.DDNS.IntervalSeconds = 90
	if err := m.Apply(cfg); err != nil {
		t.Fatal(err)
	}
	if got := receive(t, started); got.APIToken != "token-two" || got.RecordName != "new.example.invalid" || got.IntervalSeconds != 90 {
		t.Fatal("runtime still uses old configuration")
	}
	select {
	case <-stopped:
	default:
		t.Fatal("old worker still running after ACK")
	}
	cfg.Revision = "three"
	cfg.DDNS.Enabled = false
	if err := m.Apply(cfg); err != nil {
		t.Fatal(err)
	}
	select {
	case <-stopped:
	default:
		t.Fatal("disabled worker still running after ACK")
	}
	data, _ := os.ReadFile(m.path)
	if strings.Contains(string(data), "token-two") {
		t.Fatal("disabled credential retained")
	}
	var doc map[string]any
	json.Unmarshal(data, &doc)
	if doc["secret"] != "node-secret" || doc["http"] != float64(0) || doc["extension"] == nil {
		t.Fatal("unrelated configuration lost")
	}
	if runtime.GOOS != "windows" {
		st, _ := os.Stat(m.path)
		if st.Mode().Perm() != 0600 {
			t.Fatalf("mode %o", st.Mode().Perm())
		}
	}
}

func TestInvalidConfigAndFailedMigrationDoNotReplaceLastGood(t *testing.T) {
	m := testManager(t)
	cfg := validConfig()
	if err := m.Apply(cfg); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(m.path)
	for _, mutate := range []func(*Config){func(c *Config) { c.SchemaVersion = 2 }, func(c *Config) { c.DDNS.RecordName = "bad;cmd.invalid" }, func(c *Config) { c.DDNS.APIToken = "bad\nheader" }, func(c *Config) { c.DDNS.IntervalSeconds = 1 }} {
		bad := cfg
		mutate(&bad)
		if err := m.Apply(bad); err == nil {
			t.Fatal("invalid configuration accepted")
		}
	}
	m.retireLegacy = func() error { return errors.New("cannot stop legacy timer") }
	cfg.Revision = "new"
	if err := m.Apply(cfg); err == nil {
		t.Fatal("migration failure acknowledged")
	}
	after, _ := os.ReadFile(m.path)
	var beforeDoc, afterDoc any
	json.Unmarshal(before, &beforeDoc)
	json.Unmarshal(after, &afterDoc)
	if !reflect.DeepEqual(beforeDoc, afterDoc) {
		t.Fatal("last good configuration replaced")
	}
}

func TestAuthenticatedPullAndOfflineRecovery(t *testing.T) {
	status := http.StatusOK
	var lock sync.Mutex
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		lock.Lock()
		defer lock.Unlock()
		calls++
		if r.URL.Path != "/api/v1/node/runtime-config" || r.Header.Get("Authorization") != "Bearer node-secret" {
			t.Error("wrong authenticated endpoint")
		}
		w.WriteHeader(status)
		if status == 200 {
			json.NewEncoder(w).Encode(validConfig())
		}
	}))
	defer server.Close()
	m := testManager(t)
	m.endpoint = server.URL + "/api/v1/node/runtime-config"
	m.Refresh()
	before, _ := os.ReadFile(m.path)
	for _, code := range []int{401, 404, 503} {
		lock.Lock()
		status = code
		lock.Unlock()
		m.Refresh()
		after, _ := os.ReadFile(m.path)
		if string(before) != string(after) {
			t.Fatal("failed fetch discarded saved configuration")
		}
	}
	recovered := New(context.Background(), m.path, "https://panel.invalid", "node-secret")
	defer recovered.Stop()
	recovered.retireLegacy = func() error { return nil }
	updates := make(chan DDNS, 1)
	recovered.updater = func(_ context.Context, d DDNS) error { updates <- d; return nil }
	recovered.endpoint = ""
	recovered.Start()
	if got := receive(t, updates); got.APIToken != "token-one" {
		t.Fatal("saved configuration not recovered")
	}
}

func TestFetchDoesNotFollowCredentialRedirect(t *testing.T) {
	hits := 0
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hits++ }))
	defer target.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, target.URL, 302) }))
	defer server.Close()
	m := testManager(t)
	m.endpoint = server.URL
	m.Refresh()
	if hits != 0 {
		t.Fatal("credential redirect followed")
	}
}

func TestConcurrentProtocolUpdatesPreserveRuntime(t *testing.T) {
	m := testManager(t)
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			if err := UpdateLocal(m.path, func(d map[string]any) error { d["http"] = 1; return nil }); err != nil {
				t.Error(err)
			}
		}()
		go func() {
			defer wg.Done()
			if err := m.Apply(validConfig()); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	var doc map[string]any
	b, _ := os.ReadFile(m.path)
	if json.Unmarshal(b, &doc) != nil || doc["runtime"] == nil || doc["http"] != float64(1) {
		t.Fatal("concurrent configuration write lost fields")
	}
}
