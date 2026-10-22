package runtimeconfig

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

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
