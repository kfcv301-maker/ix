package local_test

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/go-gost/core/logger"
	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/parsing"
	chainparser "github.com/go-gost/x/config/parsing/chain"
	serviceparser "github.com/go-gost/x/config/parsing/service"
	_ "github.com/go-gost/x/connector/forward"
	_ "github.com/go-gost/x/connector/http"
	_ "github.com/go-gost/x/connector/relay"
	_ "github.com/go-gost/x/dialer/mtls"
	_ "github.com/go-gost/x/dialer/tcp"
	_ "github.com/go-gost/x/dialer/tls"
	_ "github.com/go-gost/x/dialer/udp"
	_ "github.com/go-gost/x/dialer/ws"
	_ "github.com/go-gost/x/handler/relay"
	_ "github.com/go-gost/x/listener/mtls"
	_ "github.com/go-gost/x/listener/tcp"
	_ "github.com/go-gost/x/listener/tls"
	_ "github.com/go-gost/x/listener/udp"
	_ "github.com/go-gost/x/listener/ws"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
	proxyproto "github.com/pires/go-proxyproto"
)

func proxyIntegrationSetup(t *testing.T) {
	t.Helper()
	oldLogger, oldTLS := logger.Default(), parsing.DefaultTLSConfig()
	logger.SetDefault(xlogger.NewLogger(xlogger.OutputOption(io.Discard)))
	certificate := httptest.NewTLSServer(nil)
	parsing.SetDefaultTLSConfig(&tls.Config{Certificates: certificate.TLS.Certificates})
	certificate.Close()
	t.Cleanup(func() {
		if oldLogger != nil {
			logger.SetDefault(oldLogger)
		}
		parsing.SetDefaultTLSConfig(oldTLS)
	})
}

func startProxyService(t *testing.T, cfg *config.ServiceConfig) service.Service {
	t.Helper()
	cfg.Metadata = map[string]any{"enableStats": false}
	s, err := serviceparser.ParseService(cfg)
	if err != nil {
		t.Fatal(err)
	}
	go s.Serve()
	t.Cleanup(func() { s.Close() })
	return s
}

func proxyRelayChain(t *testing.T, transport, target string, noDelay bool) string {
	t.Helper()
	if transport == "direct" {
		return ""
	}
	exit := startProxyService(t, &config.ServiceConfig{Name: "proxy-exit", Addr: "127.0.0.1:0",
		Listener:  &config.ListenerConfig{Type: transport},
		Handler:   &config.HandlerConfig{Type: "relay", Metadata: map[string]any{"nodelay": noDelay}},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target}}}})
	name := "proxy-integration-chain"
	cfg := &config.ChainConfig{Name: name, Hops: []*config.HopConfig{{Name: "exit", Nodes: []*config.NodeConfig{{Name: "exit", Addr: exit.Addr().String(),
		Connector: &config.ConnectorConfig{Type: "relay", Metadata: map[string]any{"nodelay": noDelay}}, Dialer: &config.DialerConfig{Type: transport}}}}}}
	c, err := chainparser.ParseChain(cfg, logger.Default())
	if err != nil {
		t.Fatal(err)
	}
	if err := registry.ChainRegistry().Register(name, c); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { registry.ChainRegistry().Unregister(name) })
	return name
}

func TestForwardProxyProtocolRealTCPAndRelayChains(t *testing.T) {
	proxyIntegrationSetup(t)
	for _, transport := range []string{"direct", "tcp", "tls", "mtls", "ws"} {
		for _, version := range []int{0, 1, 2} {
			t.Run(fmt.Sprintf("%s/v%d", transport, version), func(t *testing.T) { checkProxyTCP(t, transport, version, false, "127.0.0.1:0") })
		}
	}
	t.Run("relay-no-delay", func(t *testing.T) { checkProxyTCP(t, "tcp", 2, true, "127.0.0.1:0") })
	for _, version := range []int{1, 2} {
		t.Run(fmt.Sprintf("ipv6/v%d", version), func(t *testing.T) { checkProxyTCP(t, "direct", version, false, "[::1]:0") })
	}
}

func checkProxyTCP(t *testing.T, transport string, version int, noDelay bool, listenAddr string) {
	t.Helper()
	target, err := net.Listen("tcp", listenAddr)
	if err != nil {
		if listenAddr == "[::1]:0" {
			t.Skipf("IPv6 unavailable: %v", err)
		}
		t.Fatal(err)
	}
	defer target.Close()
	chain := proxyRelayChain(t, transport, target.Addr().String(), noDelay)
	ingressConfig := &config.ServiceConfig{Name: "proxy-ingress", Addr: listenAddr,
		Listener:  &config.ListenerConfig{Type: "tcp"},
		Handler:   &config.HandlerConfig{Type: "tcp", Chain: chain, Metadata: map[string]any{"proxyProtocol": version}},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target.Addr().String()}}}}
	if transport != "direct" {
		// Match GostUtil: tunnel ingress selects only its chain; the exit's
		// forwarder chooses the destination, without an ingress forwarder.
		ingressConfig.Forwarder = nil
	}
	ingress := startProxyService(t, ingressConfig)
	type received struct {
		header  *proxyproto.Header
		payload []byte
		err     error
	}
	result := make(chan received, 1)
	payload := append([]byte("PROXY TCP4 1.1.1.1 2.2.2.2 1 2\r\n"), bytes.Repeat([]byte{0, 255, 17, 42}, 4096)...)
	go func() {
		cc, err := target.Accept()
		if err != nil {
			result <- received{err: err}
			return
		}
		defer cc.Close()
		cc.SetDeadline(time.Now().Add(5 * time.Second))
		br := bufio.NewReader(cc)
		var header *proxyproto.Header
		if version != 0 {
			header, err = proxyproto.Read(br)
			if err != nil {
				result <- received{err: err}
				return
			}
		}
		// With PROXY enabled, the destination may wait for the header before its
		// banner. The ingress must not wait for client application bytes first.
		if _, err = cc.Write([]byte("READY")); err != nil {
			result <- received{err: err}
			return
		}
		got := make([]byte, len(payload))
		_, err = io.ReadFull(br, got)
		if err == nil {
			_, err = cc.Write([]byte("DONE"))
		}
		result <- received{header: header, payload: got, err: err}
	}()
	c, err := net.Dial("tcp", ingress.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	// Disabled mode retains the relay connector's existing lazy handshake.
	if version == 0 {
		if _, err := c.Write(payload); err != nil {
			t.Fatal(err)
		}
	}
	var banner [5]byte
	if _, err := io.ReadFull(c, banner[:]); err != nil {
		t.Fatal("destination-first banner failed:", err)
	}
	if string(banner[:]) != "READY" {
		t.Fatalf("unexpected banner %q", banner)
	}
	if version != 0 {
		if _, err := c.Write(payload); err != nil {
			t.Fatal(err)
		}
	}
	var ack [4]byte
	if _, err := io.ReadFull(c, ack[:]); err != nil {
		t.Fatal(err)
	}
	got := <-result
	if got.err != nil || !bytes.Equal(got.payload, payload) || string(ack[:]) != "DONE" {
		t.Fatalf("payload reordered, prefixed twice, or lost: %v", got.err)
	}
	if version != 0 && (got.header.Version != byte(version) || got.header.SourceAddr.String() != c.LocalAddr().String() || got.header.DestinationAddr.String() != c.RemoteAddr().String()) {
		t.Fatalf("lost original ingress addresses: %+v, client %s -> %s", got.header, c.LocalAddr(), c.RemoteAddr())
	}
}

func TestForwardProxyProtocolDoesNotAlterRealUDP(t *testing.T) {
	proxyIntegrationSetup(t)
	for _, transport := range []string{"direct", "tcp", "tls", "mtls", "ws"} {
		for _, version := range []int{0, 1, 2} {
			t.Run(fmt.Sprintf("%s/v%d", transport, version), func(t *testing.T) {
				target, err := net.ListenPacket("udp", "127.0.0.1:0")
				if err != nil {
					t.Fatal(err)
				}
				defer target.Close()
				go func() {
					b := make([]byte, 65535)
					for {
						n, a, e := target.ReadFrom(b)
						if e != nil {
							return
						}
						target.WriteTo(b[:n], a)
					}
				}()
				chain := proxyRelayChain(t, transport, target.LocalAddr().String(), false)
				ingressConfig := &config.ServiceConfig{Name: "proxy-udp", Addr: "127.0.0.1:0", Listener: &config.ListenerConfig{Type: "udp"},
					Handler:   &config.HandlerConfig{Type: "udp", Chain: chain, Metadata: map[string]any{"proxyProtocol": version}},
					Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target.LocalAddr().String()}}}}
				if transport != "direct" {
					ingressConfig.Forwarder = nil
				}
				ingress := startProxyService(t, ingressConfig)
				c, err := net.Dial("udp", ingress.Addr().String())
				if err != nil {
					t.Fatal(err)
				}
				defer c.Close()
				c.SetDeadline(time.Now().Add(5 * time.Second))
				for _, payload := range [][]byte{[]byte("PROXY UDP stays unchanged\r\n"), {0, 255, 1, 2, 0}} {
					if _, err := c.Write(payload); err != nil {
						t.Fatal(err)
					}
					b := make([]byte, 1024)
					n, err := c.Read(b)
					if err != nil || !bytes.Equal(b[:n], payload) {
						t.Fatalf("UDP payload changed: %x, %v", b[:n], err)
					}
				}
			})
		}
	}
}
