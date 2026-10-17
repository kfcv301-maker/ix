package socket

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func TestForwardProxyProtocolCapabilitiesIsReadOnlyAndCorrelated(t *testing.T) {
	before, beforeErr := os.ReadFile("gost.json")
	upgrader := websocket.Upgrader{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		reporter := &WebSocketReporter{conn: conn, connected: true}
		reporter.routeCommand(CommandMessage{Type: "ForwardProxyProtocolCapabilities", RequestId: "proxy-capability-test"})
	}))
	defer server.Close()
	c, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var response struct {
		Type      string
		Success   bool
		RequestId string
		Data      struct {
			ForwardProxyProtocol  int
			ProxyProtocolVersions []int
		}
	}
	_, payload, err := c.ReadMessage()
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(payload, &response); err != nil {
		t.Fatal(err)
	}
	if !response.Success || response.Type != "ForwardProxyProtocolCapabilitiesResponse" || response.RequestId != "proxy-capability-test" || response.Data.ForwardProxyProtocol != 1 || len(response.Data.ProxyProtocolVersions) != 2 || response.Data.ProxyProtocolVersions[0] != 1 || response.Data.ProxyProtocolVersions[1] != 2 {
		t.Fatalf("invalid capability response %s", payload)
	}
	after, afterErr := os.ReadFile("gost.json")
	if (beforeErr == nil) != (afterErr == nil) || string(before) != string(after) {
		t.Fatal("capability query rewrote gost.json")
	}
	if files, _ := filepath.Glob(".gost-config-*"); len(files) != 0 {
		t.Fatal("capability query created temporary config")
	}
}
