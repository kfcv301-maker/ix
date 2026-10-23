package runtimeconfig

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestUnsupportedOldPanelLeavesLegacyDDNSOwnerUntouched(t *testing.T) {
	for _, status := range []int{http.StatusNotFound, http.StatusOK} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			m := reviewManager(t)
			before, err := os.ReadFile(m.path)
			if err != nil {
				t.Fatal(err)
			}
			retired := false
			m.retireLegacy = func() error { retired = true; return nil }
			arrived := make(chan struct{})
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				close(arrived)
				w.WriteHeader(status)
				if status == http.StatusOK {
					// Some older panels route unknown APIs to their ordinary envelope.
					json.NewEncoder(w).Encode(map[string]any{"code": -1, "msg": "unsupported"})
				}
			}))
			defer server.Close()
			m.endpoint = server.URL
			m.Start()
			select {
			case <-arrived:
			case <-time.After(2 * time.Second):
				t.Fatal("startup did not probe panel compatibility")
			}
			m.refreshMu.Lock()
			m.refreshMu.Unlock()
			m.mu.Lock()
			unchanged := m.current == nil && m.workerDone == nil && !retired
			m.mu.Unlock()
			after, err := os.ReadFile(m.path)
			if !unchanged || err != nil || string(before) != string(after) {
				t.Fatal("unsupported old panel changed legacy DDNS ownership or private settings")
			}
		})
	}
}

func TestUnchangedAcknowledgedPushSupersedesInflightPull(t *testing.T) {
	for _, status := range []int{http.StatusOK, http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			m := reviewManager(t)
			latest := reviewConfig("latest", "latest.example.invalid")
			if err := m.Apply(latest); err != nil {
				t.Fatal(err)
			}
			before, err := os.ReadFile(m.path)
			if err != nil {
				t.Fatal(err)
			}
			started, release := make(chan struct{}), make(chan struct{})
			var releaseOnce sync.Once
			unblock := func() { releaseOnce.Do(func() { close(release) }) }
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				close(started)
				<-release
				w.WriteHeader(status)
				if status == http.StatusOK {
					json.NewEncoder(w).Encode(reviewConfig("old", "old.example.invalid"))
				}
			}))
			defer server.Close()
			defer unblock()
			m.endpoint = server.URL
			done := make(chan struct{})
			go func() { defer close(done); m.Refresh() }()
			<-started
			// Reconnects may push the same current snapshot. Its successful ACK
			// must still supersede an older request already in flight.
			if err := m.Apply(latest); err != nil {
				t.Fatal(err)
			}
			unblock()
			<-done
			m.mu.Lock()
			preserved := m.current != nil && *m.current == latest
			m.mu.Unlock()
			if !preserved {
				t.Fatal("in-flight response undid unchanged acknowledged push")
			}
			after, err := os.ReadFile(m.path)
			if err != nil || string(before) != string(after) {
				t.Fatal("in-flight response rewrote private runtime configuration")
			}
		})
	}
}

func reviewManager(t *testing.T) *Manager {
	t.Helper()
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte(`{"secret":"test-node-secret"}`), 0600); err != nil {
		t.Fatal(err)
	}
	m := New(context.Background(), path, "https://panel.invalid", "test-node-secret")
	m.retireLegacy = func() error { return nil }
	m.updater = func(context.Context, DDNS) error { return nil }
	t.Cleanup(m.Stop)
	return m
}
func reviewConfig(revision, domain string) Config {
	return Config{SchemaVersion: 1, Revision: revision, DDNS: DDNS{Enabled: true, Provider: "cloudflare", APIToken: "test-token", RecordName: domain, IntervalSeconds: 60}}
}

func TestReviewSlowPullMustNotUndoAcknowledgedPush(t *testing.T) {
	m := reviewManager(t)
	started := make(chan struct{})
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(started)
		<-release
		json.NewEncoder(w).Encode(reviewConfig("old", "old.example.invalid"))
	}))
	defer server.Close()
	m.endpoint = server.URL
	done := make(chan struct{})
	go func() { defer close(done); m.Refresh() }()
	<-started
	if err := m.Apply(reviewConfig("new", "new.example.invalid")); err != nil {
		close(release)
		t.Fatal(err)
	}
	close(release)
	<-done
	m.mu.Lock()
	current := m.current.Revision
	m.mu.Unlock()
	if current != "new" {
		t.Fatalf("stale HTTP response undid acknowledged WS push: current revision=%s", current)
	}
}

func TestReviewFailedLocalWriteMustKeepLegacyWorker(t *testing.T) {
	m := reviewManager(t)
	retired := false
	m.retireLegacy = func() error { retired = true; return nil }
	// Missing local file exercises the same UpdateLocal failure branch as EACCES
	// or a read-only/full filesystem, without changing machine permissions.
	if err := os.Remove(m.path); err != nil {
		t.Fatal(err)
	}
	if err := m.Apply(reviewConfig("new", "new.example.invalid")); err == nil {
		t.Fatal("expected local write failure")
	}
	if retired {
		t.Fatal("legacy DDNS stopped despite failure to persist replacement; no new worker took over")
	}
}
