package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/parsing/parser"
	"github.com/go-gost/x/registry"
)

func TestControlConnectionStartsAfterSavedServicesLoad(t *testing.T) {
	previous := config.Global()
	t.Cleanup(func() { config.Set(previous) })
	filename := filepath.Join(t.TempDir(), "gost.json")
	data := `{"services":[{"name":"startup-order-test","addr":"127.0.0.1:0","handler":{"type":"tcp"},"listener":{"type":"tcp"},"forwarder":{"nodes":[{"name":"target","addr":"127.0.0.1:9"}]}}]}`
	if err := os.WriteFile(filename, []byte(data), 0600); err != nil {
		t.Fatal(err)
	}
	parser.Init(parser.Args{CfgFile: filename})
	started, stopped := false, false
	program := &program{startReporter: func() func() {
		started = true
		saved := config.Global()
		if len(saved.Services) != 1 || saved.Services[0].Name != "startup-order-test" {
			t.Error("control connected before the saved inventory was loaded")
		}
		if !registry.ServiceRegistry().IsRegistered("startup-order-test") {
			t.Error("control connected before saved services were registered")
		}
		return func() { stopped = true }
	}}
	if err := program.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { registry.ServiceRegistry().Unregister("startup-order-test") })
	if !started {
		t.Fatal("control connection was never started")
	}
	if err := program.Stop(); err != nil {
		t.Fatal(err)
	}
	if !stopped {
		t.Fatal("control connection was not stopped")
	}
}
