package dialer_test

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/core/dialer"
	"github.com/go-gost/x/dialer/mtcp"
	"github.com/go-gost/x/dialer/mtls"
	"github.com/go-gost/x/dialer/mws"
	"github.com/go-gost/x/internal/util/mux"
	wsutil "github.com/go-gost/x/internal/util/ws"
	"github.com/go-gost/x/logger"
	"github.com/go-gost/x/metadata"
	"github.com/gorilla/websocket"
)

type pipeDialer struct {
	mu    sync.Mutex
	conns []net.Conn
}

type testNetDialer struct{ net.Dialer }

func (d *testNetDialer) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	return d.DialContext(ctx, network, addr)
}

func (d *pipeDialer) Dial(_ context.Context, _, _ string) (net.Conn, error) {
	a, b := net.Pipe()
	d.mu.Lock()
	d.conns = append(d.conns, a, b)
	d.mu.Unlock()
	return a, nil
}

func TestMuxDialReusesPendingPhysicalConnection(t *testing.T) {
	for _, tc := range []struct {
		name string
		new  func(...dialer.Option) dialer.Dialer
	}{
		{"mtcp", mtcp.NewDialer},
		{"mtls", mtls.NewDialer},
		{"mws", mws.NewDialer},
		{"mwss", mws.NewTLSDialer},
	} {
		t.Run(tc.name, func(t *testing.T) {
			physical := &pipeDialer{}
			defer func() {
				for _, conn := range physical.conns {
					conn.Close()
				}
			}()
			d := tc.new()
			if err := d.Init(nil); err != nil {
				t.Fatal(err)
			}
			first, err := d.Dial(context.Background(), "pending", dialer.NetDialerDialOption(physical))
			if err != nil {
				t.Fatal(err)
			}
			// The interval between Dial and Handshake is visible to other callers.
			second, err := d.Dial(context.Background(), "pending", dialer.NetDialerDialOption(physical))
			if err != nil {
				t.Fatal(err)
			}
			if first != second || len(physical.conns) != 2 {
				t.Fatalf("pending session was replaced, leaving an unowned physical connection: dials=%d", len(physical.conns)/2)
			}
		})
	}
}

func muxEchoServer(t *testing.T, transport string) (string, *tls.Config) {
	t.Helper()
	var mu sync.Mutex
	var sessions []*mux.Session
	serve := func(conn net.Conn) {
		defer conn.Close()
		session, err := mux.ServerSession(conn, &mux.Config{Version: 2, KeepAliveDisabled: true})
		if err != nil {
			return
		}
		mu.Lock()
		sessions = append(sessions, session)
		mu.Unlock()
		defer session.Close()
		for {
			stream, err := session.Accept()
			if err != nil {
				return
			}
			go func() {
				defer stream.Close()
				io.Copy(stream, stream)
			}()
		}
	}
	closeSessions := func() {
		mu.Lock()
		defer mu.Unlock()
		for _, session := range sessions {
			session.Close()
		}
	}
	if transport == "mws" || transport == "mwss" {
		upgrader := websocket.Upgrader{}
		handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			conn, err := upgrader.Upgrade(w, r, nil)
			if err == nil {
				serve(wsutil.Conn(conn))
			}
		})
		server := httptest.NewUnstartedServer(handler)
		if transport == "mwss" {
			server.StartTLS()
		} else {
			server.Start()
		}
		t.Cleanup(func() { closeSessions(); server.Close() })
		var config *tls.Config
		if transport == "mwss" {
			config = server.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
		}
		return strings.TrimPrefix(strings.TrimPrefix(server.URL, "https://"), "http://"), config
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var config *tls.Config
	if transport == "mtls" {
		// Use the standard library's in-memory test certificate and trust store.
		certServer := httptest.NewTLSServer(http.NotFoundHandler())
		config = certServer.Client().Transport.(*http.Transport).TLSClientConfig.Clone()
		config.ServerName = "example.com"
		serverConfig := certServer.TLS.Clone()
		certServer.Close()
		listener = tls.NewListener(listener, serverConfig)
	}
	t.Cleanup(func() { listener.Close(); closeSessions() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go serve(conn)
		}
	}()
	return listener.Addr().String(), config
}

func TestConcurrentMuxHandshakesKeepSharedConnectionAlive(t *testing.T) {
	for _, tc := range []struct {
		name string
		new  func(...dialer.Option) dialer.Dialer
	}{
		{"mtcp", mtcp.NewDialer},
		{"mtls", mtls.NewDialer},
		{"mws", mws.NewDialer},
		{"mwss", mws.NewTLSDialer},
	} {
		t.Run(tc.name, func(t *testing.T) {
			addr, tlsConfig := muxEchoServer(t, tc.name)
			d := tc.new(dialer.LoggerOption(logger.Nop()), dialer.TLSConfigOption(tlsConfig))
			if err := d.Init(metadata.NewMetadata(map[string]any{
				"mux.version":           2,
				"mux.keepaliveDisabled": true,
				"handshakeTimeout":      time.Second,
			})); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			netDialer := &testNetDialer{}
			first, err := d.Dial(ctx, addr, dialer.NetDialerDialOption(netDialer))
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() {
				first.Close()
				// The public dialer has no Close hook. A failed stream open releases
				// its cached smux session after physical transport shutdown.
				d.(dialer.Handshaker).Handshake(context.Background(), first, dialer.AddrHandshakeOption(addr))
			})
			second, err := d.Dial(ctx, addr, dialer.NetDialerDialOption(netDialer))
			if err != nil {
				t.Fatal(err)
			}
			if first != second {
				second.Close()
				t.Fatal("overlapping Dial calls must use one pending physical connection")
			}
			var wg sync.WaitGroup
			errs := make(chan error, 2)
			for _, raw := range []net.Conn{first, second} {
				wg.Add(1)
				go func(raw net.Conn) {
					defer wg.Done()
					stream, err := d.(dialer.Handshaker).Handshake(ctx, raw, dialer.AddrHandshakeOption(addr))
					if err != nil {
						errs <- err
						return
					}
					defer stream.Close()
					stream.SetDeadline(time.Now().Add(time.Second))
					const payload = "shared session remains usable"
					if _, err := io.WriteString(stream, payload); err != nil {
						errs <- fmt.Errorf("stream write: %w", err)
						return
					}
					buf := make([]byte, len(payload))
					_, err = io.ReadFull(stream, buf)
					if err != nil {
						err = fmt.Errorf("stream read: %w", err)
					}
					if err == nil && string(buf) != payload {
						t.Errorf("echo mismatch: %q", buf)
					}
					errs <- err
				}(raw)
			}
			wg.Wait()
			close(errs)
			for err := range errs {
				if err != nil {
					t.Fatalf("concurrent handshake interrupted a shared connection: %v", err)
				}
			}
			// A later Dial must still return the same raw identity after TLS/WS
			// wrapped the transport during the first handshake.
			reused, err := d.Dial(ctx, addr, dialer.NetDialerDialOption(netDialer))
			if err != nil || reused != first {
				t.Fatalf("established session lost identity: %v", err)
			}
		})
	}
}
