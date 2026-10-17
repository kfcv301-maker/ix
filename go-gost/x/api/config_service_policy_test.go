package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
	xservice "github.com/go-gost/x/service"
)

func apiPolicyRequest(t *testing.T, body any, name string, handler func(*gin.Context)) *httptest.ResponseRecorder {
	t.Helper()
	data, err := json.Marshal(body)
	if err != nil {
		t.Fatal(err)
	}
	result := httptest.NewRecorder()
	ctx, _ := gin.CreateTestContext(result)
	ctx.Params = gin.Params{{Key: "service", Value: name}}
	ctx.Request = httptest.NewRequest(http.MethodPut, "/config/services/"+name, bytes.NewReader(data))
	ctx.Request.Header.Set("Content-Type", "application/json")
	handler(ctx)
	return result
}

func TestHTTPLegacyUpdatesRetainUserPolicy(t *testing.T) {
	for _, batch := range []bool{false, true} {
		name := "single"
		if batch {
			name = "batch"
		}
		t.Run(name, func(t *testing.T) {
			isolatedAPIResourceConfig(t)
			policy := xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: false, Revision: 7}
			original := apiResourceConfig("61_7_2_tcp", "127.0.0.1:0", "tcp")
			original.Metadata = map[string]any{xservice.UserPolicyMetadataKey: policy}
			if result := apiResourceRequest(t, []config.ServiceConfig{original}); result.Code != http.StatusOK {
				t.Fatal(result.Body.String())
			}
			incoming := apiResourceConfig(original.Name, "127.0.0.1:0", "tcp")
			incoming.Metadata = map[string]any{"custom": "preserved"}
			var result *httptest.ResponseRecorder
			if batch {
				result = apiPolicyRequest(t, []config.ServiceConfig{incoming}, original.Name, updateServices)
			} else {
				result = apiPolicyRequest(t, incoming, original.Name, updateService)
			}
			if result.Code != http.StatusOK {
				t.Fatal(result.Body.String())
			}
			stored := config.Global().Services[0]
			got, err := xservice.ParseUserProtocolPolicy(stored.Metadata)
			if err != nil || got == nil || *got != policy || stored.Metadata["custom"] != "preserved" || registry.ServiceRegistry().Get(original.Name) == nil {
				t.Fatalf("legacy HTTP update lost user restriction or service: policy=%+v error=%v", got, err)
			}
		})
	}
}

func TestHTTPStaleBatchDoesNotCloseAnyRestrictedService(t *testing.T) {
	isolatedAPIResourceConfig(t)
	policy := xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: false, Revision: 11}
	originals := []config.ServiceConfig{apiResourceConfig("62_7_2_tcp", "127.0.0.1:0", "tcp"), apiResourceConfig("63_8_2_tcp", "127.0.0.1:0", "tcp")}
	for i := range originals {
		originals[i].Metadata = map[string]any{xservice.UserPolicyMetadataKey: policy}
	}
	if result := apiResourceRequest(t, originals); result.Code != http.StatusOK {
		t.Fatal(result.Body.String())
	}
	first, second := registry.ServiceRegistry().Get(originals[0].Name), registry.ServiceRegistry().Get(originals[1].Name)
	incoming := []config.ServiceConfig{apiResourceConfig(originals[0].Name, "127.0.0.1:0", "tcp"), apiResourceConfig(originals[1].Name, "127.0.0.1:0", "tcp")}
	stale := policy
	stale.Revision--
	stale.TLS = xservice.TLSUnrestricted
	incoming[1].Metadata = map[string]any{xservice.UserPolicyMetadataKey: stale}
	result := apiPolicyRequest(t, incoming, "batch", updateServices)
	if result.Code != http.StatusBadRequest {
		t.Fatal(result.Body.String())
	}
	if registry.ServiceRegistry().Get(originals[0].Name) != first || registry.ServiceRegistry().Get(originals[1].Name) != second {
		t.Fatal("a late stale policy validation closed an earlier listener")
	}
	for _, cfg := range config.Global().Services {
		got, err := xservice.ParseUserProtocolPolicy(cfg.Metadata)
		if err != nil || got == nil || *got != policy {
			t.Fatalf("stale batch modified policy: %+v, %v", got, err)
		}
	}
}

func TestHTTPMalformedBodiesCannotMutateServices(t *testing.T) {
	isolatedAPIResourceConfig(t)
	cfg := apiResourceConfig("64_7_2_tcp", "127.0.0.1:0", "tcp")
	if result := apiResourceRequest(t, []config.ServiceConfig{cfg}); result.Code != http.StatusOK {
		t.Fatal(result.Body.String())
	}
	active := registry.ServiceRegistry().Get(cfg.Name)
	for _, tc := range []struct {
		name    string
		handler func(*gin.Context)
	}{
		{"create", createService}, {"createBatch", createServices},
		{"update", updateService}, {"updateBatch", updateServices},
		{"deleteBatch", deleteServices}, {"pauseBatch", pauseServices}, {"resumeBatch", resumeServices},
	} {
		t.Run(tc.name, func(t *testing.T) {
			result := httptest.NewRecorder()
			ctx, _ := gin.CreateTestContext(result)
			ctx.Params = gin.Params{{Key: "service", Value: cfg.Name}}
			ctx.Request = httptest.NewRequest(http.MethodPut, "/config/services/"+cfg.Name, bytes.NewBufferString("{"))
			ctx.Request.Header.Set("Content-Type", "application/json")
			tc.handler(ctx)
			if result.Code != http.StatusBadRequest {
				t.Fatalf("malformed JSON accepted: %d %s", result.Code, result.Body.String())
			}
			if registry.ServiceRegistry().Get(cfg.Name) != active || len(config.Global().Services) != 1 {
				t.Fatal("malformed body mutated a live service")
			}
		})
	}
}
