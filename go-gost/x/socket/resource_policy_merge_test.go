package socket

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/go-gost/x/api"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
	xservice "github.com/go-gost/x/service"
)

func TestFailedPairReplacementRetainsScopedPolicy(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	policy := xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: false, Revision: 9}
	original := []config.ServiceConfig{serviceTestConfig("41_7_2_tcp", addr, "tcp"), serviceTestConfig("41_7_2_udp", addr, "udp")}
	for i := range original {
		original[i].Metadata = map[string]any{xservice.UserPolicyMetadataKey: policy}
	}
	if err := createServices(createServicesRequest{Data: original}); err != nil {
		t.Fatal(err)
	}
	blocker := blockUDPOnFreeTCPPort(t)
	// A legacy update omits policy metadata. It must inherit the restriction,
	// and a later listener failure must restore both original configurations.
	replacements := []config.ServiceConfig{serviceTestConfig(original[0].Name, blocker.LocalAddr().String(), "tcp"), serviceTestConfig(original[1].Name, blocker.LocalAddr().String(), "udp")}
	if err := updateServices(updateServicesRequest{Data: replacements}); err == nil {
		t.Fatal("expected blocked UDP listener to reject replacement")
	} else if !strings.Contains(err.Error(), "update service "+original[1].Name+" failed:") {
		t.Fatalf("replacement did not reach its blocked UDP member: %v", err)
	}
	for _, cfg := range config.Global().Services {
		got, err := xservice.ParseUserProtocolPolicy(cfg.Metadata)
		if err != nil || got == nil || *got != policy || cfg.Addr != addr || registry.ServiceRegistry().Get(cfg.Name) == nil {
			t.Fatalf("rollback lost listener or policy for %s: %v, %v", cfg.Name, got, err)
		}
	}
	assertTCPPortFree(t, blocker.LocalAddr().String())
}

func TestConcurrentRESTLegacyReplacementAndWebSocketPolicyStayRestricted(t *testing.T) {
	isolatedServiceConfig(t)
	policy := xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: false, Revision: 20}
	cfg := serviceTestConfig("65_7_2_tcp", "127.0.0.1:0", "tcp")
	cfg.Metadata = map[string]any{xservice.UserPolicyMetadataKey: policy}
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{cfg}}); err != nil {
		t.Fatal(err)
	}
	router := gin.New()
	api.Register(router, nil)
	for i := 0; i < 3; i++ {
		policy.Revision++
		if i%2 == 0 {
			policy.TLS = xservice.TLSDeny
		} else {
			policy.TLS = xservice.TLSOnly
		}
		incoming := serviceTestConfig(cfg.Name, "127.0.0.1:0", "tcp")
		body, err := json.Marshal([]config.ServiceConfig{incoming})
		if err != nil {
			t.Fatal(err)
		}
		req := httptest.NewRequest(http.MethodPut, "/config/services/batch", bytes.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.SetBasicAuth("test", "test")
		response := httptest.NewRecorder()
		var policyErr error
		var wg sync.WaitGroup
		start := make(chan struct{})
		wg.Add(2)
		go func() { defer wg.Done(); <-start; router.ServeHTTP(response, req) }()
		go func() {
			defer wg.Done()
			<-start
			policyErr = (&WebSocketReporter{}).handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{cfg.Name}, Policy: policy})
		}()
		close(start)
		wg.Wait()
		if policyErr != nil || response.Code != http.StatusOK {
			t.Fatalf("concurrent commands failed: WS=%v REST=%d %s", policyErr, response.Code, response.Body.String())
		}
		stored := config.Global().Services[0]
		got, err := xservice.ParseUserProtocolPolicy(stored.Metadata)
		if err != nil || got == nil || *got != policy || registry.ServiceRegistry().Get(cfg.Name) == nil {
			t.Fatalf("REST replacement raced away the latest restriction: %+v %v", got, err)
		}
	}
}

func TestStaleReplacementDoesNotCloseRestrictedListener(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	policy := xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: true, Revision: 12}
	cfg := serviceTestConfig("42_7_2_tcp", addr, "tcp")
	cfg.Metadata = map[string]any{xservice.UserPolicyMetadataKey: policy}
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{cfg}}); err != nil {
		t.Fatal(err)
	}
	old := registry.ServiceRegistry().Get(cfg.Name)
	stale := policy
	stale.Revision--
	stale.TLS = xservice.TLSUnrestricted
	incoming := serviceTestConfig(cfg.Name, unusedTCPAddress(t), "tcp")
	incoming.Metadata = map[string]any{xservice.UserPolicyMetadataKey: stale}
	if err := updateServices(updateServicesRequest{Data: []config.ServiceConfig{incoming}}); err == nil {
		t.Fatal("accepted stale policy while replacing service")
	}
	if registry.ServiceRegistry().Get(cfg.Name) != old {
		t.Fatal("stale policy update replaced the active listener")
	}
}

func TestDeletePartiallyPresentPairIsIdempotentAndScoped(t *testing.T) {
	isolatedServiceConfig(t)
	addr := unusedTCPAddress(t)
	active := serviceTestConfig("51_7_2_tcp", addr, "tcp")
	other := serviceTestConfig("52_8_2_tcp", unusedTCPAddress(t), "tcp")
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{active, other}}); err != nil {
		t.Fatal(err)
	}
	untouched := registry.ServiceRegistry().Get(other.Name)
	// A failed UDP creation can leave config behind without a registry entry.
	stale := serviceTestConfig("51_7_2_udp", addr, "udp")
	config.OnUpdate(func(c *config.Config) error {
		c.Services = append(c.Services, &stale)
		return nil
	})
	request := deleteServicesRequest{Services: []string{active.Name, stale.Name, active.Name}}
	for i := 0; i < 2; i++ {
		if err := deleteServices(request); err != nil {
			t.Fatal(err)
		}
		if registry.ServiceRegistry().Get(active.Name) != nil || registry.ServiceRegistry().Get(other.Name) != untouched {
			t.Fatal("delete missed the extant member or affected another owner")
		}
		if services := config.Global().Services; len(services) != 1 || services[0].Name != other.Name {
			t.Fatal("delete retained stale config or deleted unrelated config")
		}
	}
	assertTCPPortFree(t, addr)
}

func TestDeleteValidatesAllNamesBeforeClosing(t *testing.T) {
	isolatedServiceConfig(t)
	cfg := serviceTestConfig("53_7_2_tcp", unusedTCPAddress(t), "tcp")
	if err := createServices(createServicesRequest{Data: []config.ServiceConfig{cfg}}); err != nil {
		t.Fatal(err)
	}
	active := registry.ServiceRegistry().Get(cfg.Name)
	if err := deleteServices(deleteServicesRequest{Services: []string{cfg.Name, " "}}); err == nil {
		t.Fatal("accepted an invalid member")
	}
	if registry.ServiceRegistry().Get(cfg.Name) != active || len(config.Global().Services) != 1 {
		t.Fatal("invalid batch changed a valid listener")
	}
}
