package socket

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/go-gost/x/config"
	parser "github.com/go-gost/x/config/parsing/service"
	"github.com/go-gost/x/service"
)

// Exercise command ACKs and real forwarding, including both failed tightening
// and failed relaxation, without inspecting private implementation flags.
func TestProtocolSwitchPersistsBeforeChangingTraffic(t *testing.T) {
	control := proxyCommandSocket(t)
	service.SetProtocolBlock(0, 0, 0)
	t.Cleanup(func() { service.SetProtocolBlock(0, 0, 0) })
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { target.Close() })
	var received atomic.Int64
	go func() {
		for {
			c, err := target.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				buf := make([]byte, 1024)
				for {
					n, err := c.Read(buf)
					if n > 0 {
						received.Add(int64(n))
						c.Write(buf[:n])
					}
					if err != nil {
						return
					}
				}
			}()
		}
	}()
	cfg := serviceTestConfig("protocol-switch-test", "127.0.0.1:0", "tcp")
	cfg.Forwarder = &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "echo", Addr: target.Addr().String()}}}
	svc, err := parser.ParseService(&cfg)
	if err != nil {
		t.Fatal(err)
	}
	go svc.Serve()
	t.Cleanup(func() { svc.Close() })
	check := func(allowed bool) {
		t.Helper()
		for _, payload := range [][]byte{[]byte("GET / HTTP/1.1\r\n"), {0x16, 3, 1, 0, 64, 1, 0}, {5, 1, 0}} {
			before := received.Load()
			c, err := net.Dial("tcp", svc.Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			c.SetDeadline(time.Now().Add(time.Second))
			_, writeErr := c.Write(payload)
			got := make([]byte, len(payload))
			_, readErr := io.ReadFull(c, got)
			c.Close()
			if allowed && (writeErr != nil || readErr != nil || !bytes.Equal(got, payload)) {
				t.Fatalf("failed command changed allowed traffic: %v, %v", writeErr, readErr)
			}
			if !allowed && (readErr == nil || received.Load() != before) {
				t.Fatal("failed command relaxed blocking or blocked bytes reached target")
			}
		}
	}
	send := func(value int, success bool) {
		t.Helper()
		err := control.WriteJSON(CommandMessage{Type: "SetProtocol", RequestId: "protocol-persistence", Data: map[string]int{"http": value, "tls": value, "socks": value}})
		if err != nil {
			t.Fatal(err)
		}
		control.SetReadDeadline(time.Now().Add(2 * time.Second))
		var response CommandResponse
		if err := control.ReadJSON(&response); err != nil {
			t.Fatal(err)
		}
		if response.Success != success || response.RequestId != "protocol-persistence" || response.Type != "SetProtocolResponse" {
			t.Fatalf("incorrect protocol ACK: %+v", response)
		}
	}
	check(true)
	// Missing private configuration must reject the command without changing traffic.
	send(1, false)
	check(true)
	initial := []byte(`{"secret":"test-only","http":0,"tls":0,"socks":0,"runtime":{"revision":"keep"},"extension":{"keep":true}}`)
	if err := os.WriteFile("config.json", initial, 0600); err != nil {
		t.Fatal(err)
	}
	// This command owns config.json only; an unrelated service graph write must
	// not turn a successfully applied setting into an error response.
	if err := os.Mkdir("gost.json", 0700); err != nil {
		t.Fatal(err)
	}
	send(1, true)
	check(false)
	saved, err := os.ReadFile("config.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove("config.json"); err != nil {
		t.Fatal(err)
	}
	send(0, false)
	check(false)
	if err := os.WriteFile("config.json", saved, 0600); err != nil {
		t.Fatal(err)
	}
	send(0, true)
	check(true)
	data, err := os.ReadFile("config.json")
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if json.Unmarshal(data, &doc) != nil || doc["secret"] != "test-only" || doc["runtime"] == nil || doc["extension"] == nil || doc["http"] != float64(0) || doc["tls"] != float64(0) || doc["socks"] != float64(0) {
		t.Fatal("private fields lost or switches not persisted")
	}
}
