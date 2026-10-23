package socket

import (
	"bytes"
	"crypto/tls"
	"io"
	"net"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	coreservice "github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/parsing"
	parser "github.com/go-gost/x/config/parsing/service"
	"github.com/go-gost/x/registry"
	"github.com/go-gost/x/service"
)

func TestHotPolicyWriteRemovesEveryHistoricalAlias(t *testing.T) {
	isolatedServiceConfig(t)
	old := service.UserProtocolPolicy{TLS: service.TLSUnrestricted, AllowUDP: true, Revision: 1}
	newer := service.UserProtocolPolicy{TLS: service.TLSOnly, AllowUDP: false, Revision: 2}
	md := map[string]any{"userprotocolpolicy": old, "USERPROTOCOLPOLICY": newer, "interface": "eth0"}
	cfg := &config.ServiceConfig{Name: "901_7_3_tcp", Listener: &config.ListenerConfig{Type: "tcp"}, Metadata: md}
	config.Set(&config.Config{Services: []*config.ServiceConfig{cfg}})
	w := &WebSocketReporter{}
	stale := userProtocolPolicyRequest{Services: []string{cfg.Name}, Policy: old}
	if err := w.handleSetUserProtocolPolicy(stale); err == nil {
		t.Fatal("lowercase metadata allowed a stale policy update")
	}
	want := service.UserProtocolPolicy{TLS: service.TLSDeny, AllowUDP: false, Revision: 3}
	if err := w.handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: []string{cfg.Name}, Policy: want}); err != nil {
		t.Fatal(err)
	}
	current := config.Global().Services[0]
	assertCanonicalPolicy(t, current, want)
	if len(md) != 3 || md["userprotocolpolicy"] != old || current.Metadata["interface"] != "eth0" {
		t.Fatal("changed a previous snapshot or unrelated metadata")
	}
}

func assertCanonicalPolicy(t *testing.T, cfg *config.ServiceConfig, want service.UserProtocolPolicy) {
	t.Helper()
	got, err := service.ParseUserProtocolPolicy(cfg.Metadata)
	if err != nil || got == nil || *got != want {
		t.Fatalf("wrong saved policy: got %+v, %v; want %+v", got, err, want)
	}
	count := 0
	for key := range cfg.Metadata {
		if strings.EqualFold(key, service.UserPolicyMetadataKey) {
			count++
			if key != service.UserPolicyMetadataKey {
				t.Fatalf("historical alias survived: %s", key)
			}
		}
	}
	if count != 1 {
		t.Fatalf("saved %d policy aliases", count)
	}
}

// Exercise saveConfig -> Config.ReadFile -> ParseService, not JSON unmarshalling
// alone. No panel pushes occur after each simulated offline agent restart.
func TestHotPoliciesSurviveOfflineRestartWithRealTCPAndUDP(t *testing.T) {
	isolatedServiceConfig(t)
	t.Chdir(t.TempDir())
	oldTLS := parsing.DefaultTLSConfig()
	parsing.SetDefaultTLSConfig(&tls.Config{})
	t.Cleanup(func() { parsing.SetDefaultTLSConfig(oldTLS) })
	tcpTarget, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { tcpTarget.Close() })
	var tcpBytes atomic.Int64
	go func() {
		for {
			conn, err := tcpTarget.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				buf := make([]byte, 2048)
				for {
					n, err := conn.Read(buf)
					if n > 0 {
						tcpBytes.Add(int64(n))
						conn.Write(buf[:n])
					}
					if err != nil {
						return
					}
				}
			}()
		}
	}()
	udpTarget, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { udpTarget.Close() })
	var udpPackets atomic.Int64
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := udpTarget.ReadFrom(buf)
			if err != nil {
				return
			}
			udpPackets.Add(1)
			udpTarget.WriteTo(buf[:n], addr)
		}
	}()
	names := []string{"902_7_3_tcp", "902_7_3_udp"}
	initial := service.UserProtocolPolicy{TLS: service.TLSUnrestricted, AllowUDP: true, Revision: 1}
	for i, protocol := range []string{"tcp", "udp"} {
		target := tcpTarget.Addr().String()
		if protocol == "udp" {
			target = udpTarget.LocalAddr().String()
		}
		cfg := serviceTestConfig(names[i], "127.0.0.1:0", protocol)
		cfg.Metadata = map[string]any{"userprotocolpolicy": initial, "enableStats": false}
		cfg.Listener.Metadata = map[string]any{"keepAlive": true}
		cfg.Forwarder = &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "echo", Addr: target}}}
		config.OnUpdate(func(c *config.Config) error { c.Services = append(c.Services, &cfg); return nil })
	}
	var live []coreservice.Service
	start := func() {
		t.Helper()
		live = nil
		for _, cfg := range config.Global().Services {
			svc, err := parser.ParseService(cfg)
			if err != nil {
				t.Fatal(err)
			}
			if err := registry.ServiceRegistry().Register(cfg.Name, svc); err != nil {
				svc.Close()
				t.Fatal(err)
			}
			live = append(live, svc)
			go svc.Serve()
		}
	}
	stop := func() {
		for i, svc := range live {
			registry.ServiceRegistry().Unregister(names[i])
			svc.Close()
		}
		live = nil
	}
	t.Cleanup(stop)
	check := func(p service.UserProtocolPolicy) {
		t.Helper()
		for _, tc := range []struct {
			payload []byte
			allowed bool
		}{
			{[]byte("GET / HTTP/1.1\r\n"), p.TLS != service.TLSOnly},
			{[]byte{0x16, 3, 1, 0, 64, 1, 0, 0, 60, 3, 3}, p.TLS != service.TLSDeny},
		} {
			before := tcpBytes.Load()
			conn, err := net.Dial("tcp", live[0].Addr().String())
			if err != nil {
				t.Fatal(err)
			}
			conn.SetDeadline(time.Now().Add(time.Second))
			_, writeErr := conn.Write(tc.payload)
			got := make([]byte, len(tc.payload))
			_, readErr := io.ReadFull(conn, got)
			conn.Close()
			if tc.allowed && (writeErr != nil || readErr != nil || !bytes.Equal(got, tc.payload)) {
				t.Fatalf("allowed TCP failed: %v, %v", writeErr, readErr)
			}
			if !tc.allowed && (readErr == nil || tcpBytes.Load() != before) {
				t.Fatalf("blocked TCP reached target under %s", p.TLS)
			}
		}
		before := udpPackets.Load()
		conn, err := net.Dial("udp", live[1].Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(400 * time.Millisecond))
		payload := []byte("saved-policy-UDP")
		if _, err := conn.Write(payload); err != nil {
			t.Fatal(err)
		}
		buf := make([]byte, 64)
		n, err := conn.Read(buf)
		if p.AllowUDP && (err != nil || !bytes.Equal(buf[:n], payload)) {
			t.Fatalf("allowed UDP failed: %v", err)
		}
		if !p.AllowUDP && (err == nil || udpPackets.Load() != before) {
			t.Fatal("blocked UDP reached target after reload")
		}
	}
	start()
	for _, p := range []service.UserProtocolPolicy{
		{TLS: service.TLSOnly, AllowUDP: false, Revision: 2},
		{TLS: service.TLSDeny, AllowUDP: false, Revision: 3},
		{TLS: service.TLSUnrestricted, AllowUDP: true, Revision: 4},
		{TLS: service.TLSOnly, AllowUDP: false, Revision: 5},
		{TLS: service.TLSUnrestricted, AllowUDP: true, Administrator: true},
		{TLS: service.TLSDeny, AllowUDP: false, Revision: 6},
	} {
		if err := (&WebSocketReporter{}).handleSetUserProtocolPolicy(userProtocolPolicyRequest{Services: names, Policy: p}); err != nil {
			t.Fatal(err)
		}
		check(p)
		for restart := 0; restart < 2; restart++ {
			if err := saveConfig(); err != nil {
				t.Fatal(err)
			}
			stop()
			var restored config.Config
			if err := restored.ReadFile("gost.json"); err != nil {
				t.Fatal(err)
			}
			for _, cfg := range restored.Services {
				assertCanonicalPolicy(t, cfg, p)
			}
			config.Set(&restored)
			start()
			check(p)
		}
	}
}
