package socket

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/go-gost/x/config"
	_ "github.com/go-gost/x/connector/forward"
	_ "github.com/go-gost/x/dialer/tcp"
	"github.com/go-gost/x/registry"
	"github.com/gorilla/websocket"
)

func proxyCommandSocket(t *testing.T) *websocket.Conn {
	t.Helper()
	isolatedServiceConfig(t)
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Chdir(t.TempDir()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chdir(cwd) })
	stopped := make(chan struct{})
	upgrader := websocket.Upgrader{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(stopped)
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer conn.Close()
		reporter := &WebSocketReporter{conn: conn, connected: true}
		for {
			kind, payload, err := conn.ReadMessage()
			if err != nil {
				return
			}
			reporter.handleReceivedMessage(kind, payload)
		}
	}))
	conn, _, err := websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(server.URL, "http"), nil)
	if err != nil {
		server.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		conn.Close()
		server.Close()
		select {
		case <-stopped:
		case <-time.After(3 * time.Second):
			t.Error("control socket did not stop")
		}
	})
	return conn
}

func sendProxyServiceCommand(t *testing.T, conn *websocket.Conn, operation string, cfg config.ServiceConfig, wantSuccess bool) {
	t.Helper()
	requestID := operation + "-" + cfg.Name
	if err := conn.WriteJSON(CommandMessage{Type: operation, RequestId: requestID, Data: []config.ServiceConfig{cfg}}); err != nil {
		t.Fatal(err)
	}
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	var response struct {
		Success   bool   `json:"success"`
		RequestID string `json:"requestId"`
		Message   string `json:"message"`
	}
	if err := conn.ReadJSON(&response); err != nil {
		t.Fatal(err)
	}
	if response.RequestID != requestID || response.Success != wantSuccess {
		t.Fatalf("unexpected %s ACK: %+v", operation, response)
	}
	if !wantSuccess && !strings.Contains(response.Message, "proxyProtocol") {
		t.Fatalf("invalid version was not explicitly rejected: %+v", response)
	}
}

func expectedCommandProxyHeader(version int, client net.Conn) []byte {
	src, dst := client.LocalAddr().(*net.TCPAddr), client.RemoteAddr().(*net.TCPAddr)
	if version == 1 {
		return []byte(fmt.Sprintf("PROXY TCP4 %s %s %d %d\r\n", src.IP, dst.IP, src.Port, dst.Port))
	}
	if version == 2 {
		b := append([]byte("\r\n\r\n\x00\r\nQUIT\n"), 0x21, 0x11, 0, 12)
		b = append(b, src.IP.To4()...)
		b = append(b, dst.IP.To4()...)
		b = binary.BigEndian.AppendUint16(b, uint16(src.Port))
		return binary.BigEndian.AppendUint16(b, uint16(dst.Port))
	}
	return nil
}

func checkCommandProxyWire(t *testing.T, target net.Listener, name string, version int) {
	t.Helper()
	service := registry.ServiceRegistry().Get(name)
	if service == nil {
		t.Fatal("command did not create a live service")
	}
	client, err := net.DialTimeout("tcp", service.Addr().String(), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	client.SetDeadline(time.Now().Add(3 * time.Second))
	accepted, err := target.Accept()
	if err != nil {
		t.Fatal(err)
	}
	defer accepted.Close()
	accepted.SetDeadline(time.Now().Add(2 * time.Second))
	expected := expectedCommandProxyHeader(version, client)
	if len(expected) > 0 {
		actual := make([]byte, len(expected))
		if _, err := io.ReadFull(accepted, actual); err != nil || !bytes.Equal(actual, expected) {
			t.Fatalf("JSON command ACKed but header missing/wrong before application data: got %x want %x: %v", actual, expected, err)
		}
	}
	if _, err := accepted.Write([]byte("READY")); err != nil {
		t.Fatal(err)
	}
	var banner [5]byte
	if _, err := io.ReadFull(client, banner[:]); err != nil || string(banner[:]) != "READY" {
		t.Fatalf("destination-first banner failed: %q, %v", banner, err)
	}
	client.Write([]byte("APP"))
	var payload [3]byte
	if _, err := io.ReadFull(accepted, payload[:]); err != nil || string(payload[:]) != "APP" {
		t.Fatalf("header repeated, leaked while off, or payload lost: %q, %v", payload, err)
	}
}

func TestProxyProtocolWebSocketAddAndUpdateCarryHeaders(t *testing.T) {
	conn := proxyCommandSocket(t)
	target, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	target.(*net.TCPListener).SetDeadline(time.Now().Add(20 * time.Second))
	cfg := serviceTestConfig("proxy-json-command", "127.0.0.1:0", "tcp")
	cfg.Metadata = map[string]any{"enableStats": false}
	cfg.Forwarder = &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target.Addr().String()}}}
	cfg.Handler.Metadata = map[string]any{"proxyProtocol": 0}
	sendProxyServiceCommand(t, conn, "AddService", cfg, true)
	checkCommandProxyWire(t, target, cfg.Name, 0)
	for _, version := range []int{2, 1, 0} {
		cfg.Handler.Metadata = map[string]any{"proxyProtocol": version}
		sendProxyServiceCommand(t, conn, "UpdateService", cfg, true)
		checkCommandProxyWire(t, target, cfg.Name, version)
		if version == 2 {
			cfg.Handler.Metadata = map[string]any{"proxyProtocol": 1.5}
			sendProxyServiceCommand(t, conn, "UpdateService", cfg, false)
			// A malformed replacement must restore the previous version instead
			// of acknowledging success and leaving a headerless listener.
			checkCommandProxyWire(t, target, cfg.Name, 2)
		}
	}
	for _, version := range []int{1, 2} {
		cfg.Name = fmt.Sprintf("proxy-json-add-v%d", version)
		cfg.Handler.Metadata = map[string]any{"proxyProtocol": version}
		sendProxyServiceCommand(t, conn, "AddService", cfg, true)
		checkCommandProxyWire(t, target, cfg.Name, version)
	}
	for i, invalid := range []any{1.5, -1, 3, true, "invalid", nil} {
		cfg.Name = fmt.Sprintf("proxy-json-invalid-%d", i)
		cfg.Handler.Metadata = map[string]any{"proxyProtocol": invalid}
		sendProxyServiceCommand(t, conn, "AddService", cfg, false)
		if registry.ServiceRegistry().IsRegistered(cfg.Name) {
			t.Fatal("invalid JSON version installed a listener")
		}
	}
	// Check saved state as well as actual wire bytes, without relying on an
	// in-memory int that bypasses encoding/json's map[string]any conversion.
	persisted, err := os.ReadFile("gost.json")
	if err != nil || !json.Valid(persisted) {
		t.Fatalf("command persistence failed: %v", err)
	}
}
