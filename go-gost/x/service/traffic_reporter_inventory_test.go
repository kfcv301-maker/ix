package service

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/go-gost/x/config"
)

func TestFreshAgentReportsExplicitEmptyRegistries(t *testing.T) {
	previous := config.Global()
	config.Set(&config.Config{})
	t.Cleanup(func() { config.Set(previous) })
	body, err := getConfigData()
	if err != nil {
		t.Fatal(err)
	}
	var payload map[string]json.RawMessage
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"services", "chains", "limiters"} {
		if string(payload[name]) != "[]" {
			t.Fatalf("fresh Agent must report authoritative empty %s, got %s", name, payload[name])
		}
	}
	if config.Global().Services != nil || config.Global().Chains != nil || config.Global().Limiters != nil {
		t.Fatal("reporting mutated the live configuration")
	}
}

func TestInventoryReportPreservesExistingServiceAndNormalizesEmptyRegistries(t *testing.T) {
	previous := config.Global()
	config.Set(&config.Config{Services: []*config.ServiceConfig{{Name: "12_7_9_tcp", Addr: "[::]:10001"}}})
	t.Cleanup(func() { config.Set(previous) })
	body, err := getConfigData()
	if err != nil {
		t.Fatal(err)
	}
	var payload struct {
		Services []config.ServiceConfig `json:"services"`
		Chains   json.RawMessage        `json:"chains"`
		Limiters json.RawMessage        `json:"limiters"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Services) != 1 || payload.Services[0].Name != "12_7_9_tcp" || payload.Services[0].Addr != "[::]:10001" {
		t.Fatalf("existing service changed: %#v", payload.Services)
	}
	if string(payload.Chains) != "[]" || string(payload.Limiters) != "[]" {
		t.Fatal("empty registries were not authoritative")
	}
}

func TestFreshAgentHTTPReportUsesCompleteInventory(t *testing.T) {
	previousConfig := config.Global()
	previousURL, previousToken, previousCrypto := configReportURL, httpReportToken, httpAESCrypto
	config.Set(&config.Config{})
	t.Cleanup(func() {
		config.Set(previousConfig)
		configReportURL, httpReportToken, httpAESCrypto = previousURL, previousToken, previousCrypto
	})
	var seen atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen.Store(true)
		if r.Header.Get("Authorization") != "Bearer test-node-only" || r.Header.Get("User-Agent") != "Config-Reporter/1.0" {
			t.Error("inventory report lost authentication or compatible User-Agent")
		}
		body, _ := io.ReadAll(r.Body)
		var data map[string]json.RawMessage
		if err := json.Unmarshal(body, &data); err != nil {
			t.Error(err)
		}
		if string(data["services"]) != "[]" || string(data["chains"]) != "[]" || string(data["limiters"]) != "[]" {
			t.Errorf("fresh HTTP inventory cannot restore rules: %s", body)
		}
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()
	configReportURL, httpReportToken, httpAESCrypto = server.URL, "test-node-only", nil
	ok, err := sendConfigReport(context.Background())
	if err != nil || !ok || !seen.Load() {
		t.Fatalf("report failed: %v %v", ok, err)
	}
}
