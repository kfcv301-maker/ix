package socket

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/go-gost/x/registry"
	"github.com/go-gost/x/service"
	"github.com/gorilla/websocket"
)

// This fixture reproduces the enhanced panel contracts at 4be159a (Agent
// 1.2.8-enhanced) and 61208de (1.4.13): WebSocketInterceptor bearer handshake,
// WebSocketServer requestId/type/data envelopes and call heartbeat ACKs,
// GostUtil service JSON, and AESCrypto SHA-256 + AES-GCM(12-byte IV, 16-byte tag).
// It intentionally exposes no runtime-config endpoint or new capability RPC.
func TestEnhancedLegacyPanelWireContract(t *testing.T) {
	isolatedServiceConfig(t)
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(t.TempDir()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chdir(cwd); service.SetProtocolBlock(0, 0, 0) })
	if err := os.WriteFile("config.json", []byte(`{"http":0,"tls":0,"socks":0}`), 0600); err != nil {
		t.Fatal(err)
	}
	const secret = "legacy-panel-fixture-secret"
	panelConnections := make(chan *websocket.Conn, 1)
	finished := make(chan struct{})
	upgrader := websocket.Upgrader{}
	panel := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/system-info" || r.URL.Query().Get("type") != "1" ||
			r.URL.Query().Get("version") != "1.4.16-enhanced" ||
			r.Header.Get("Authorization") != "Bearer "+secret || r.URL.Query().Has("secret") {
			http.Error(w, "invalid enhanced-panel handshake", http.StatusUnauthorized)
			return
		}
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		panelConnections <- conn
		<-finished
	}))
	reporter := NewWebSocketReporter(panel.URL, secret)
	reporter.addr, reporter.secret, reporter.version = panel.URL, secret, "1.4.16-enhanced"
	t.Cleanup(func() { reporter.Stop(); close(finished); panel.Close() })
	if err := reporter.connect(); err != nil {
		t.Fatal(err)
	}
	var panelConn *websocket.Conn
	select {
	case panelConn = <-panelConnections:
	case <-time.After(3 * time.Second):
		t.Fatal("legacy panel did not accept registration")
	}

	// Independent encryption implementation follows the old Java AESCrypto wire
	// layout, instead of using the Agent's helper on both ends of the fixture.
	key := sha256.Sum256([]byte(secret))
	block, err := aes.NewCipher(key[:])
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	nonceCounter := byte(0)
	send := func(body string) {
		t.Helper()
		nonce := make([]byte, 12)
		nonceCounter++
		nonce[0] = nonceCounter
		wire := append(nonce, gcm.Seal(nil, nonce, []byte(body), nil)...)
		panelConn.SetWriteDeadline(time.Now().Add(3 * time.Second))
		if err := panelConn.WriteJSON(map[string]any{"encrypted": true, "data": base64.StdEncoding.EncodeToString(wire), "timestamp": time.Now().UnixMilli()}); err != nil {
			t.Fatal(err)
		}
		reporter.conn.SetReadDeadline(time.Now().Add(3 * time.Second))
		kind, payload, err := reporter.conn.ReadMessage()
		if err != nil {
			t.Fatal(err)
		}
		reporter.handleReceivedMessage(kind, payload)
	}
	read := func() map[string]any {
		t.Helper()
		panelConn.SetReadDeadline(time.Now().Add(3 * time.Second))
		var wrapper struct {
			Encrypted bool   `json:"encrypted"`
			Data      string `json:"data"`
		}
		if err := panelConn.ReadJSON(&wrapper); err != nil {
			t.Fatal(err)
		}
		if !wrapper.Encrypted {
			t.Fatal("Agent reply lost legacy AES wrapping")
		}
		payload, err := base64.StdEncoding.DecodeString(wrapper.Data)
		if err != nil || len(payload) < 28 {
			t.Fatal("invalid legacy AES frame")
		}
		plain, err := gcm.Open(nil, payload[:12], payload[12:], nil)
		if err != nil {
			t.Fatal(err)
		}
		var message map[string]any
		if err := json.Unmarshal(plain, &message); err != nil {
			t.Fatal(err)
		}
		return message
	}
	ack := func(operation, requestID string) {
		t.Helper()
		response := read()
		if response["type"] != operation+"Response" || response["requestId"] != requestID || response["message"] != "OK" || response["success"] != true {
			t.Fatalf("legacy panel could not match acknowledgement: %+v", response)
		}
	}
	if err := reporter.sendSystemInfo(SystemInfo{}); err != nil {
		t.Fatal(err)
	}
	if _, ok := read()["memory_usage"]; !ok {
		t.Fatal("heartbeat lacks old panel recognition field")
	}
	send(`{"type":"call"}`)
	// A call is an acknowledgement only: the next frame must belong to this RPC.
	send(`{"type":"SetProtocol","requestId":"old-switches","data":{"http":0,"tls":0,"socks":0}}`)
	ack("SetProtocol", "old-switches")

	target, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { target.Close() })
	send(fmt.Sprintf(`{"type":"AddService","requestId":"old-create","data":[{"name":"legacy_fixture_tcp","addr":"127.0.0.1:0","handler":{"type":"tcp"},"listener":{"type":"tcp"},"forwarder":{"nodes":[{"name":"node_1","addr":%q}],"selector":{"strategy":"fifo","maxFails":1,"failTimeout":"600s"}}}]}`, target.Addr().String()))
	ack("AddService", "old-create")
	forward := registry.ServiceRegistry().Get("legacy_fixture_tcp")
	if forward == nil {
		t.Fatal("old AddService was acknowledged without a listener")
	}
	client, err := net.DialTimeout("tcp", forward.Addr().String(), 3*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	client.SetDeadline(time.Now().Add(3 * time.Second))
	target.(*net.TCPListener).SetDeadline(time.Now().Add(3 * time.Second))
	upstream, err := target.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer upstream.Close()
	upstream.SetDeadline(time.Now().Add(3 * time.Second))
	if _, err := client.Write([]byte("OLD")); err != nil {
		t.Fatal(err)
	}
	var data [3]byte
	if _, err := io.ReadFull(upstream, data[:]); err != nil || string(data[:]) != "OLD" {
		t.Fatalf("old service TCP request failed: %q %v", data, err)
	}
	if _, err := upstream.Write([]byte("NEW")); err != nil {
		t.Fatal(err)
	}
	if _, err := io.ReadFull(client, data[:]); err != nil || string(data[:]) != "NEW" {
		t.Fatalf("old service TCP response failed: %q %v", data, err)
	}
	send(`{"type":"DeleteService","requestId":"old-delete","data":{"services":["legacy_fixture_tcp"]}}`)
	ack("DeleteService", "old-delete")
	if registry.ServiceRegistry().Get("legacy_fixture_tcp") != nil {
		t.Fatal("old DeleteService left its listener registered")
	}
}
