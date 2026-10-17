package local

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/chain"
	"github.com/go-gost/core/handler"
	"github.com/go-gost/core/hop"
	xmetadata "github.com/go-gost/x/metadata"
	proxyproto "github.com/pires/go-proxyproto"
)

type proxyTestConn struct {
	bytes.Buffer
	src, dst   net.Addr
	writeErr   error
	shortWrite bool
	closed     bool
	reads      int
	deadlines  []time.Time
}

func (c *proxyTestConn) Read(b []byte) (int, error) { c.reads++; return c.Buffer.Read(b) }
func (c *proxyTestConn) Write(b []byte) (int, error) {
	if c.writeErr != nil {
		return 0, c.writeErr
	}
	if c.shortWrite {
		return c.Buffer.Write(b[:len(b)/2])
	}
	return c.Buffer.Write(b)
}
func (c *proxyTestConn) Close() error                    { c.closed = true; return nil }
func (c *proxyTestConn) RemoteAddr() net.Addr            { return c.src }
func (c *proxyTestConn) LocalAddr() net.Addr             { return c.dst }
func (c *proxyTestConn) SetDeadline(time.Time) error     { return nil }
func (c *proxyTestConn) SetReadDeadline(time.Time) error { return nil }
func (c *proxyTestConn) SetWriteDeadline(d time.Time) error {
	c.deadlines = append(c.deadlines, d)
	return nil
}

func proxyTCPClient(ip, dst string) *proxyTestConn {
	return &proxyTestConn{src: &net.TCPAddr{IP: net.ParseIP(ip), Port: 50001}, dst: &net.TCPAddr{IP: net.ParseIP(dst), Port: 30443}}
}

func TestForwardProxyProtocolHeaderAddressesAndVersions(t *testing.T) {
	for _, addresses := range [][2]string{{"198.51.100.27", "192.0.2.80"}, {"2001:db8::27", "2001:db8:1::80"}} {
		for _, version := range []int{1, 2} {
			h := &forwardHandler{md: metadataForProxyTest(version)}
			client, target := proxyTCPClient(addresses[0], addresses[1]), &proxyTestConn{}
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			if err := h.writeProxyProtocol(ctx, "tcp", client, target); err != nil {
				t.Fatal(err)
			}
			cancel()
			header, err := proxyproto.Read(bufio.NewReader(bytes.NewReader(target.Bytes())))
			if err != nil {
				t.Fatal(err)
			}
			if header.Version != byte(version) || header.Command != proxyproto.PROXY ||
				header.SourceAddr.String() != client.RemoteAddr().String() || header.DestinationAddr.String() != client.LocalAddr().String() {
				t.Fatalf("wrong header: %+v", header)
			}
			if len(target.deadlines) != 2 || target.deadlines[0].IsZero() || !target.deadlines[1].IsZero() {
				t.Fatal("write deadline was not bounded and cleared")
			}
		}
	}
}

func metadataForProxyTest(version int) metadata {
	return metadata{proxyProtocol: version, readTimeout: time.Second}
}

func TestForwardProxyProtocolDisabledAndUDPDoNotWrite(t *testing.T) {
	for _, tc := range []struct {
		version int
		network string
	}{{0, "tcp"}, {1, "udp"}, {2, "udp"}, {2, "unix"}} {
		h := &forwardHandler{md: metadataForProxyTest(tc.version)}
		target := &proxyTestConn{}
		if err := h.writeProxyProtocol(context.Background(), tc.network, proxyTCPClient("198.51.100.27", "192.0.2.80"), target); err != nil {
			t.Fatal(err)
		}
		if target.Len() != 0 || len(target.deadlines) != 0 {
			t.Fatal("disabled or non-TCP path changed the stream")
		}
	}
}

type proxyTestRouter struct {
	chain.Router
	target net.Conn
}

func (r proxyTestRouter) Dial(context.Context, string, string) (net.Conn, error) {
	return r.target, nil
}

type proxyTestHop struct{}

func (proxyTestHop) Select(context.Context, ...hop.SelectOption) *chain.Node {
	return &chain.Node{Addr: "192.0.2.80:443"}
}

func TestForwardProxyProtocolFailedHeaderClosesBeforePayload(t *testing.T) {
	for _, short := range []bool{false, true} {
		client := proxyTCPClient("198.51.100.27", "192.0.2.80")
		client.Buffer.WriteString("secret-application-payload")
		target := &proxyTestConn{shortWrite: short}
		if !short {
			target.writeErr = errors.New("header write failed")
		}
		h := NewHandler(handler.RouterOption(proxyTestRouter{target: target})).(*forwardHandler)
		if err := h.Init(xmetadata.NewMetadata(map[string]any{"proxyProtocol": 2})); err != nil {
			t.Fatal(err)
		}
		h.Forward(proxyTestHop{})
		if err := h.Handle(context.Background(), client); err == nil {
			t.Fatal("header error was ignored")
		}
		if !target.closed || !client.closed || client.reads != 0 || bytes.Contains(target.Bytes(), []byte("secret-application-payload")) {
			t.Fatal("failed header leaked payload or retained a connection")
		}
	}
}

func TestForwardProxyProtocolRejectsUnsupportedVersion(t *testing.T) {
	for _, version := range []int{-1, 3, 256} {
		if err := NewHandler().Init(xmetadata.NewMetadata(map[string]any{"proxyProtocol": version})); err == nil {
			t.Fatalf("accepted unsupported version %d", version)
		}
	}
}

func TestForwardProxyProtocolRejectsMissingIngressAddress(t *testing.T) {
	h := &forwardHandler{md: metadataForProxyTest(2)}
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	if err := h.writeProxyProtocol(context.Background(), "tcp", a, &proxyTestConn{}); err == nil {
		t.Fatal("silently sent an addressless LOCAL header")
	}
}
