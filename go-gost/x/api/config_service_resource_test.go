package api

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	corelistener "github.com/go-gost/core/listener"
	corelogger "github.com/go-gost/core/logger"
	"github.com/go-gost/core/metadata"
	"github.com/go-gost/x/config"
	_ "github.com/go-gost/x/handler/forward/local"
	tcplistener "github.com/go-gost/x/listener/tcp"
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

// Keep the real listener and observe its lifecycle. A UDP-assigned ephemeral
// port says nothing about whether the corresponding TCP port is available;
// using that port for the first service can fail before rollback is exercised.
type apiTrackedTCPListener struct {
	corelistener.Listener
	initialized bool
	closed      bool
	closeErr    error
}

func (l *apiTrackedTCPListener) Init(md metadata.Metadata) error {
	err := l.Listener.Init(md)
	l.initialized = err == nil
	return err
}

func (l *apiTrackedTCPListener) Close() error {
	l.closed = true
	l.closeErr = l.Listener.Close()
	return l.closeErr
}

func TestHTTPBatchCreateReleasesEarlierListenerOnBindFailure(t *testing.T) {
	isolatedAPIResourceConfig(t)
	blocker, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer blocker.Close()
	var tracked *apiTrackedTCPListener
	listenerName := "test_http_cleanup_tcp"
	if err := registry.ListenerRegistry().Register(listenerName, func(opts ...corelistener.Option) corelistener.Listener {
		tracked = &apiTrackedTCPListener{Listener: tcplistener.NewListener(opts...)}
		return tracked
	}); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		registry.ListenerRegistry().Unregister(listenerName)
		if tracked != nil && tracked.initialized {
			_ = tracked.Listener.Close()
		}
	})
	first := apiResourceConfig("http_cleanup_tcp", "127.0.0.1:0", "tcp")
	first.Listener.Type = listenerName
	configs := []config.ServiceConfig{first, apiResourceConfig("http_cleanup_udp", blocker.LocalAddr().String(), "udp")}
	for i := 0; i < 5; i++ {
		result := apiResourceRequest(t, configs)
		if result.Code != http.StatusInternalServerError {
			t.Fatalf("expected bind failure, got %d: %s", result.Code, result.Body.String())
		}
		if tracked == nil || !tracked.initialized || tracked.Addr().(*net.TCPAddr).Port == 0 {
			t.Fatal("first TCP listener was not successfully bound; rollback was not exercised")
		}
		if !strings.Contains(result.Body.String(), "http_cleanup_udp") {
			t.Fatalf("expected second UDP service to fail: %s", result.Body.String())
		}
		if !tracked.closed || tracked.closeErr != nil {
			t.Fatalf("failed HTTP batch did not close its TCP listener: closed=%v err=%v", tracked.closed, tracked.closeErr)
		}
		// Inspect the actual socket instead of rebinding its now-free port, which
		// another concurrent package may legitimately claim after the close.
		acceptResult := make(chan error, 1)
		go func(listener corelistener.Listener) {
			conn, err := listener.Accept()
			if conn != nil {
				conn.Close()
			}
			acceptResult <- err
		}(tracked)
		select {
		case err := <-acceptResult:
			if !errors.Is(err, net.ErrClosed) {
				t.Fatalf("rolled-back listener still accepts: %v", err)
			}
		case <-time.After(time.Second):
			t.Fatal("rolled-back TCP socket remained open")
		}
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
