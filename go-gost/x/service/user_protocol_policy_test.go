package service

import (
	"bytes"
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/core/handler"
	"github.com/go-gost/core/metadata"
	"github.com/go-gost/x/logger"
)

func TestTLSClassificationAcrossTCPFragments(t *testing.T) {
	for _, mode := range []string{TLSOnly, TLSDeny} {
		for _, tc := range []struct {
			name    string
			payload []byte
			tls     bool
		}{
			{"TLS13-AnyTLS-REALITY-prefix", []byte{0x16, 3, 1, 0, 64, 1, 0, 0, 60, 3, 3}, true},
			{"TLS12-record", []byte{0x16, 3, 3, 0, 64, 1, 0, 0, 60, 3, 3}, true},
			{"HTTP", []byte("GET / HTTP/1.1\r\n"), false},
			{"SOCKS5", []byte{5, 1, 0}, false},
			{"SS-encrypted", []byte{0xa9, 0xf2, 0x31, 0x52, 0x83}, false},
			{"invalid-TLS-record", []byte{0x16, 3, 3, 0, 0, 1}, false},
			{"not-a-ClientHello", []byte{0x16, 3, 3, 0, 64, 2}, false},
		} {
			t.Run(mode+"/"+tc.name, func(t *testing.T) {
				server, client := net.Pipe()
				defer server.Close()
				defer client.Close()
				server.SetReadDeadline(time.Now().Add(time.Second))
				client.SetWriteDeadline(time.Now().Add(time.Second))
				go func() {
					for _, b := range tc.payload {
						if _, err := client.Write([]byte{b}); err != nil {
							break
						}
					}
					client.Close()
				}()
				filtered := &protocolConn{Conn: server, tlsPolicy: mode}
				got, err := io.ReadAll(filtered)
				allowed := mode == TLSOnly && tc.tls || mode == TLSDeny && !tc.tls
				if allowed && (!bytes.Equal(got, tc.payload) || err != nil) {
					t.Fatalf("payload changed: %x, %v", got, err)
				}
				if !allowed && (len(got) != 0 || err == nil) {
					t.Fatalf("blocked data escaped: %x, %v", got, err)
				}
			})
		}
	}
}

type policyTestHandler struct{ tlsConfig *tls.Config }

func (*policyTestHandler) Init(metadata.Metadata) error { return nil }
func (h *policyTestHandler) Handle(_ context.Context, c net.Conn, _ ...handler.HandleOption) error {
	if h.tlsConfig != nil {
		t := tls.Server(c, h.tlsConfig)
		if err := t.Handshake(); err != nil {
			return err
		}
		c = t
	}
	_, err := io.Copy(c, c)
	return err
}

func policyTestService(t *testing.T, p UserProtocolPolicy, tlsConfig *tls.Config) (*defaultService, string) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := NewService("policy-test", &closeTestListener{ln}, &policyTestHandler{tlsConfig: tlsConfig},
		UserProtocolPolicyOption(&p), LoggerOption(logger.NewLogger(logger.OutputOption(io.Discard)))).(*defaultService)
	go s.Serve()
	t.Cleanup(func() { s.Close() })
	return s, ln.Addr().String()
}

func assertEcho(t *testing.T, c net.Conn, payload []byte) {
	t.Helper()
	c.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := c.Write(payload); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("echo changed: %x", got)
	}
}

func TestRealTLS13HandshakeAndAnyTLSApplicationBytes(t *testing.T) {
	certServer := httptest.NewTLSServer(nil)
	defer certServer.Close()
	_, addr := policyTestService(t, UserProtocolPolicy{TLS: TLSOnly, AllowUDP: true}, certServer.TLS)
	c, err := tls.Dial("tcp", addr, &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS13})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	assertEcho(t, c, []byte{1, 0, 0, 12, 0, 0, 0, 1, 0xaa, 0xbb, 0xcc, 0xdd})
}

func TestPolicyHotChangeDoesNotInterruptAnotherUserOrAdministrator(t *testing.T) {
	SetProtocolBlock(1, 1, 1)
	defer SetProtocolBlock(0, 0, 0)
	restricted, a := policyTestService(t, UserProtocolPolicy{TLS: TLSUnrestricted, AllowUDP: true}, nil)
	_, b := policyTestService(t, UserProtocolPolicy{TLS: TLSUnrestricted, AllowUDP: true}, nil)
	_, admin := policyTestService(t, UserProtocolPolicy{TLS: TLSUnrestricted, AllowUDP: true}, nil)
	first, _ := net.Dial("tcp", a)
	defer first.Close()
	assertEcho(t, first, []byte("hello"))
	other, _ := net.Dial("tcp", b)
	defer other.Close()
	assertEcho(t, other, []byte("GET / HTTP/1.1\r\n"))
	root, _ := net.Dial("tcp", admin)
	defer root.Close()
	assertEcho(t, root, []byte{0x16, 3, 1, 0, 1, 1})
	restricted.SetUserProtocolPolicy(UserProtocolPolicy{TLS: TLSOnly, AllowUDP: true})
	var one [1]byte
	first.SetReadDeadline(time.Now().Add(time.Second))
	if _, err := first.Read(one[:]); err == nil {
		t.Fatal("old restricted connection survived")
	}
	assertEcho(t, other, []byte("other user still works"))
	assertEcho(t, root, []byte("admin still works"))
	newConn, _ := net.Dial("tcp", a)
	defer newConn.Close()
	newConn.SetDeadline(time.Now().Add(time.Second))
	newConn.Write([]byte("plain"))
	if _, err := newConn.Read(one[:]); err == nil {
		t.Fatal("plain traffic bypassed TLS_ONLY")
	}
}

type udpPolicyConn struct{ net.Conn }

func (udpPolicyConn) RemoteAddr() net.Addr {
	return &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 12345}
}

func TestUDPPolicyIsIndependentAndUnrestrictedNeedsNoWrapper(t *testing.T) {
	for _, allow := range []bool{false, true} {
		p := UserProtocolPolicy{TLS: TLSOnly, AllowUDP: allow}
		s := &defaultService{}
		s.userPolicy.Store(&p)
		a, b := net.Pipe()
		defer a.Close()
		defer b.Close()
		c := udpPolicyConn{a}
		got, allowed := s.policyConnection(c)
		if allowed != allow || got != c {
			t.Fatalf("UDP policy: %v", allowed)
		}
	}
	s := &defaultService{}
	p := UserProtocolPolicy{TLS: TLSUnrestricted, AllowUDP: false}
	s.userPolicy.Store(&p)
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	if got, allowed := s.policyConnection(a); got != a || !allowed {
		t.Fatal("UDP ban affected TCP")
	}
}

func TestRepeatedPolicySyncPreservesConnections(t *testing.T) {
	p := UserProtocolPolicy{TLS: TLSDeny, AllowUDP: true}
	s, addr := policyTestService(t, p, nil)
	c, _ := net.Dial("tcp", addr)
	defer c.Close()
	assertEcho(t, c, []byte("hello"))
	s.SetUserProtocolPolicy(p)
	assertEcho(t, c, []byte("still alive"))
	p.AllowUDP = false
	s.SetUserProtocolPolicy(p)
	assertEcho(t, c, []byte("UDP ban does not interrupt TCP"))
}

func TestRelaxingPolicyAllowsAnIdleExistingConnection(t *testing.T) {
	p := UserProtocolPolicy{TLS: TLSOnly, AllowUDP: true, Revision: 1}
	s := &defaultService{}
	s.userPolicy.Store(&p)
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	signal := &policyReadSignalConn{Conn: a, started: make(chan struct{})}
	c, allowed := s.policyConnection(signal)
	if !allowed {
		t.Fatal("rejected TCP before handshake")
	}
	done := make(chan error, 1)
	go func() {
		buf := make([]byte, 5)
		_, err := io.ReadFull(c, buf)
		if err == nil && string(buf) != "plain" {
			err = io.ErrUnexpectedEOF
		}
		done <- err
	}()
	select {
	case <-signal.started:
	case <-time.After(time.Second):
		t.Fatal("first read did not start")
	}
	relaxed := UserProtocolPolicy{TLS: TLSUnrestricted, AllowUDP: true, Revision: 2}
	s.userPolicy.Store(&relaxed)
	b.SetWriteDeadline(time.Now().Add(time.Second))
	if _, err := b.Write([]byte("plain")); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatal("relaxed connection still blocked", err)
		}
	case <-time.After(time.Second):
		t.Fatal("relaxation did not take effect")
	}
}

type policyReadSignalConn struct {
	net.Conn
	started chan struct{}
	once    sync.Once
}

func (c *policyReadSignalConn) Read(b []byte) (int, error) {
	c.once.Do(func() { close(c.started) })
	return c.Conn.Read(b)
}
