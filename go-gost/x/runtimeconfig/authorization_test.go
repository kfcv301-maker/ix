package runtimeconfig

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
)

func TestRevokedAuthorizationStopsDDNSAndClearsOfflineCache(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(status) }))
			defer server.Close()
			m := testManager(t)
			started := make(chan DDNS, 1)
			stopped := make(chan struct{}, 1)
			m.updater = func(ctx context.Context, d DDNS) error {
				started <- d
				<-ctx.Done()
				stopped <- struct{}{}
				return ctx.Err()
			}
			if err := m.Apply(validConfig()); err != nil {
				t.Fatal(err)
			}
			receive(t, started)
			m.endpoint = server.URL
			m.Refresh()
			select {
			case <-stopped:
			default:
				t.Fatal("revoked worker still running")
			}
			data, err := os.ReadFile(m.path)
			if err != nil {
				t.Fatal(err)
			}
			var doc map[string]any
			if err := json.Unmarshal(data, &doc); err != nil {
				t.Fatal(err)
			}
			if doc["runtime"] != nil || strings.Contains(string(data), "token-one") {
				t.Fatal("revoked credentials can restart offline")
			}
			if doc["secret"] != "node-secret" || doc["http"] != float64(0) || doc["extension"] == nil {
				t.Fatal("unrelated settings changed")
			}
			// The same authenticated config can recover when authority is restored.
			m.updater = func(context.Context, DDNS) error { return nil }
			if err := m.Apply(validConfig()); err != nil {
				t.Fatal(err)
			}
			if m.current == nil {
				t.Fatal("authorized recovery failed")
			}
		})
	}
}

func TestStaleAuthorizationFailureCannotUndoNewPush(t *testing.T) {
	arrived, release := make(chan struct{}), make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(arrived)
		<-release
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()
	m := testManager(t)
	m.endpoint = server.URL
	done := make(chan struct{})
	go func() { defer close(done); m.Refresh() }()
	<-arrived
	if err := m.Apply(validConfig()); err != nil {
		close(release)
		t.Fatal(err)
	}
	close(release)
	<-done
	if m.current == nil || !m.current.DDNS.Enabled {
		t.Fatal("stale rejection undid new authenticated push")
	}
}
