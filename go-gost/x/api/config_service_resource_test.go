package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	corelogger "github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/listener/tcp"
	_ "github.com/go-gost/x/listener/udp"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
)

func init() { corelogger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard))) }

func apiResourceConfig(name, addr, network string) config.ServiceConfig {
	return config.ServiceConfig{Name: name, Addr: addr, Listener: &config.ListenerConfig{Type: network}, Handler: &config.HandlerConfig{Type: network}}
}

func isolatedAPIResourceConfig(t *testing.T) {
	t.Helper()
	previous := config.Global()
	config.Set(&config.Config{})
	t.Cleanup(func() {
		for _, cfg := range config.Global().Services {
			if svc := registry.ServiceRegistry().Get(cfg.Name); svc != nil {
				registry.ServiceRegistry().Unregister(cfg.Name)
				svc.Close()
			}
		}
		config.Set(previous)
	})
}

func apiResourceRequest(t *testing.T, configs []config.ServiceConfig) *httptest.ResponseRecorder {
	t.Helper()
	data, err := json.Marshal(configs)
	if err != nil {
		t.Fatal(err)
	}
	result := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(result)
	ctx.Request = httptest.NewRequest(http.MethodPost, "/config/services/batch", bytes.NewReader(data))
	ctx.Request.Header.Set("Content-Type", "application/json")
	createServices(ctx)
	return result
}

func apiResourceTCPPortFree(t *testing.T, addr string) {
	t.Helper()
	probe, err := net.Listen("tcp4", addr)
	if err != nil {
		t.Fatalf("failed HTTP batch retained %s: %v", addr, err)
	}
	probe.Close()
}

func TestHTTPBatchCreateReleasesEarlierListenerOnBindFailure(t *testing.T) {
	isolatedAPIResourceConfig(t)
	blocker, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Close()
	addr := blocker.LocalAddr().String()
	configs := []config.ServiceConfig{apiResourceConfig("http_cleanup_tcp", addr, "tcp"), apiResourceConfig("http_cleanup_udp", addr, "udp")}
	for i := 0; i < 5; i++ {
		result := apiResourceRequest(t, configs)
		if result.Code != http.StatusInternalServerError {
			t.Fatalf("expected bind failure, got %d: %s", result.Code, result.Body.String())
		}
		apiResourceTCPPortFree(t, addr)
		if len(config.Global().Services) != 0 || registry.ServiceRegistry().IsRegistered(configs[0].Name) {
			t.Fatal("failed HTTP batch committed a partial configuration")
		}
	}
}

func TestHTTPBatchPreflightRejectsDuplicatesBeforeBinding(t *testing.T) {
	isolatedAPIResourceConfig(t)
	blocker, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Close()
	addr := blocker.Addr().String()
	result := apiResourceRequest(t, []config.ServiceConfig{apiResourceConfig("http_duplicate", addr, "tcp"), apiResourceConfig("http_duplicate", addr, "udp")})
	if result.Code != http.StatusBadRequest {
		t.Fatalf("attempted a bind before duplicate validation: %d: %s", result.Code, result.Body.String())
	}
}

func TestHTTPBatchSuccessStoresBothServiceConfigurations(t *testing.T) {
	isolatedAPIResourceConfig(t)
	configs := []config.ServiceConfig{apiResourceConfig("http_success_tcp", "127.0.0.1:0", "tcp"), apiResourceConfig("http_success_udp", "127.0.0.1:0", "udp")}
	result := apiResourceRequest(t, configs)
	if result.Code != http.StatusOK {
		t.Fatalf("create failed: %d: %s", result.Code, result.Body.String())
	}
	stored := config.Global().Services
	if len(stored) != 2 || stored[0].Name != configs[0].Name || stored[1].Name != configs[1].Name || stored[0] == stored[1] {
		t.Fatal("HTTP batch did not retain distinct TCP and UDP configuration entries")
	}
	for _, cfg := range stored {
		if registry.ServiceRegistry().Get(cfg.Name) == nil {
			t.Fatalf("created service %s is missing from the registry", cfg.Name)
		}
	}
}
