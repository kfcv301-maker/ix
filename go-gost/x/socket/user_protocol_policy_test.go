package socket

import (
	"encoding/json"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/service"
	"os"
	"testing"
)

func TestUserPolicyBatchIsScopedAndRejectsPartialUnknownBatch(t *testing.T) {
	old := config.Global()
	defer config.Set(old)
	a := &config.ServiceConfig{Name: "1_7_3_tcp", Listener: &config.ListenerConfig{Type: "tcp"}, Metadata: map[string]any{"interface": "eth0", "paused": true}}
	b := &config.ServiceConfig{Name: "2_8_4_tcp", Listener: &config.ListenerConfig{Type: "tcp"}}
	config.Set(&config.Config{Services: []*config.ServiceConfig{a, b}})
	w := &WebSocketReporter{}
	p := service.UserProtocolPolicy{TLS: service.TLSOnly, AllowUDP: false}
	if err := w.handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{a.Name, "missing_tcp"}, Policy: p}); err == nil {
		t.Fatal("accepted missing service")
	}
	if _, exists := a.Metadata[service.UserPolicyMetadataKey]; exists {
		t.Fatal("partially applied invalid batch")
	}
	if err := w.handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{a.Name}, Policy: p}); err != nil {
		t.Fatal(err)
	}
	cfg := config.Global()
	got, err := service.ParseUserProtocolPolicy(cfg.Services[0].Metadata)
	if err != nil || got == nil || *got != p {
		t.Fatalf("paused policy lost: %v, %v", got, err)
	}
	if cfg.Services[0].Metadata["interface"] != "eth0" || cfg.Services[0].Metadata["paused"] != true {
		t.Fatal("overwrote unrelated metadata")
	}
	if len(cfg.Services[1].Metadata) != 0 {
		t.Fatal("changed another user")
	}
	if err := w.handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{"1_7_3_tls"}, Policy: p}); err == nil {
		t.Fatal("applied policy to tunnel egress")
	}
}

func TestPolicyPersistsAcrossConfigReloadAndReportsWriteFailure(t *testing.T) {
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(t.TempDir()); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(cwd)
	before := config.Global()
	defer config.Set(before)
	cfg := &config.ServiceConfig{Name: "12_7_9_udp", Listener: &config.ListenerConfig{Type: "udp"}, Metadata: map[string]any{"paused": true}}
	config.Set(&config.Config{Services: []*config.ServiceConfig{cfg}})
	p := service.UserProtocolPolicy{TLS: service.TLSDeny, AllowUDP: false, Revision: 4}
	if err := (&WebSocketReporter{}).handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{cfg.Name}, Policy: p}); err != nil {
		t.Fatal(err)
	}
	if err := saveConfig(); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile("gost.json")
	if err != nil {
		t.Fatal(err)
	}
	var restored config.Config
	if err := json.Unmarshal(b, &restored); err != nil {
		t.Fatal(err)
	}
	got, err := service.ParseUserProtocolPolicy(restored.Services[0].Metadata)
	if err != nil || got == nil || *got != p || restored.Services[0].Metadata["paused"] != true {
		t.Fatal("persisted policy or pause state lost")
	}
	if err := os.Remove("gost.json"); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir("gost.json", 0700); err != nil {
		t.Fatal(err)
	}
	if err := saveConfig(); err == nil {
		t.Fatal("acknowledged a failed configuration write")
	}
}

func TestStaleCommandsAndLegacyUpdatesCannotRemoveNewerRestrictions(t *testing.T) {
	p := service.UserProtocolPolicy{TLS: service.TLSOnly, AllowUDP: false, Revision: 7}
	current := &config.ServiceConfig{Name: "12_7_8_tcp", Listener: &config.ListenerConfig{Type: "tcp"}, Metadata: map[string]any{service.UserPolicyMetadataKey: p}}
	legacy := &config.ServiceConfig{Name: current.Name, Metadata: map[string]any{"interface": "eth1"}}
	if err := preserveServicePolicy(legacy, current); err != nil {
		t.Fatal(err)
	}
	got, _ := service.ParseUserProtocolPolicy(legacy.Metadata)
	if got == nil || *got != p || legacy.Metadata["interface"] != "eth1" {
		t.Fatal("legacy update removed restriction or interface")
	}
	old := p
	old.Revision = 6
	old.TLS = service.TLSUnrestricted
	incoming := &config.ServiceConfig{Name: current.Name, Metadata: map[string]any{service.UserPolicyMetadataKey: old}}
	if err := preserveServicePolicy(incoming, current); err == nil {
		t.Fatal("accepted stale full update")
	}
	before := config.Global()
	defer config.Set(before)
	config.Set(&config.Config{Services: []*config.ServiceConfig{current}})
	w := &WebSocketReporter{}
	if err := w.handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{current.Name}, Policy: old}); err == nil {
		t.Fatal("accepted stale reconnect policy")
	}
	// The canonical grant ID may have changed; aliases must remain restricted.
	if err := w.handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{"12_7_9_tcp"}, Policy: p}); err != nil {
		t.Fatal(err)
	}
	admin := service.UserProtocolPolicy{TLS: service.TLSUnrestricted, AllowUDP: true, Administrator: true}
	if err := w.handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{current.Name}, Policy: admin}); err != nil {
		t.Fatal("administrator exemption rejected", err)
	}
}
