package socket_test

import (
	"crypto/tls"
	"io"
	"net"
	"sync/atomic"
	"testing"
	"time"

	"github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/parsing"
	parser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/connector/forward"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/dialer/udp"
	_ "github.com/go-gost/x/handler/forward/local"
	_ "github.com/go-gost/x/listener/udp"
	xlogger "github.com/go-gost/x/logger"
	xservice "github.com/go-gost/x/service"
)

func TestNativeUDPUserPolicyWithRealForwarders(t *testing.T) {
	oldLogger := logger.Default()
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	oldTLS := parsing.DefaultTLSConfig()
	parsing.SetDefaultTLSConfig(&tls.Config{})
	t.Cleanup(func() {
		if oldLogger != nil {
			logger.SetDefault(oldLogger)
		}
		parsing.SetDefaultTLSConfig(oldTLS)
	})
	target, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	var packets atomic.Int64
	go func() {
		buf := make([]byte, 2048)
		for {
			n, addr, err := target.ReadFrom(buf)
			if err != nil {
				return
			}
			packets.Add(1)
			target.WriteTo(buf[:n], addr)
		}
	}()
	newForward := func(name string, p xservice.UserProtocolPolicy) (net.Conn, interface {
		SetUserProtocolPolicy(xservice.UserProtocolPolicy) error
	}) {
		s, err := parser.ParseService(&config.ServiceConfig{Name: name, Addr: "127.0.0.1:0",
			Listener:  &config.ListenerConfig{Type: "udp", Metadata: map[string]any{"keepAlive": true}},
			Handler:   &config.HandlerConfig{Type: "udp"},
			Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target.LocalAddr().String()}}},
			Metadata:  map[string]any{xservice.UserPolicyMetadataKey: p, "enableStats": false}})
		if err != nil {
			t.Fatal(err)
		}
		go s.Serve()
		t.Cleanup(func() { s.Close() })
		c, err := net.Dial("udp", s.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { c.Close() })
		return c, s.(interface {
			SetUserProtocolPolicy(xservice.UserProtocolPolicy) error
		})
	}
	check := func(c net.Conn, allowed bool) {
		t.Helper()
		c.SetDeadline(time.Now().Add(400 * time.Millisecond))
		if _, err := c.Write([]byte("UDP-policy-test")); err != nil {
			t.Fatal(err)
		}
		var buf [64]byte
		n, err := c.Read(buf[:])
		if allowed && (err != nil || string(buf[:n]) != "UDP-policy-test") {
			t.Fatalf("allowed UDP failed: %s %v", buf[:n], err)
		}
		if !allowed && err == nil {
			t.Fatal("forbidden native UDP reached target")
		}
	}
	blocked, policy := newForward("1_7_3_udp", xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: false, Revision: 1})
	other, _ := newForward("2_8_4_udp", xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: true})
	admin, _ := newForward("3_1_0_udp", xservice.UserProtocolPolicy{TLS: xservice.TLSUnrestricted, AllowUDP: true, Administrator: true})
	check(blocked, false)
	check(other, true)
	check(admin, true)
	if packets.Load() != 2 {
		t.Fatalf("unexpected forwarded packets: %d", packets.Load())
	}
	if err := policy.SetUserProtocolPolicy(xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: true, Revision: 2}); err != nil {
		t.Fatal(err)
	}
	check(blocked, true)
	if err := policy.SetUserProtocolPolicy(xservice.UserProtocolPolicy{TLS: xservice.TLSOnly, AllowUDP: false, Revision: 3}); err != nil {
		t.Fatal(err)
	}
	check(blocked, false)
	check(other, true)
	check(admin, true)
}
