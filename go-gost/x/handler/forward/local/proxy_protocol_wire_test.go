package local_test

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/x/config"
)

// Deliberately independent from go-proxyproto: assert exact HAProxy wire layout.
func readIndependentProxyHeader(r *bufio.Reader, version int) (string, string, error) {
	if version == 1 {
		line, err := r.ReadString('\n')
		if err != nil {
			return "", "", err
		}
		p := strings.Fields(line)
		if len(p) != 6 || p[0] != "PROXY" || (p[1] != "TCP4" && p[1] != "TCP6") || !strings.HasSuffix(line, "\r\n") {
			return "", "", fmt.Errorf("bad v1 %q", line)
		}
		return net.JoinHostPort(p[2], p[4]), net.JoinHostPort(p[3], p[5]), nil
	}
	hdr := make([]byte, 16)
	if _, err := io.ReadFull(r, hdr); err != nil {
		return "", "", err
	}
	if !bytes.Equal(hdr[:12], []byte("\r\n\r\n\x00\r\nQUIT\n")) || hdr[12] != 0x21 {
		return "", "", fmt.Errorf("bad v2 signature/version %x", hdr)
	}
	size := int(binary.BigEndian.Uint16(hdr[14:]))
	width := 0
	switch hdr[13] {
	case 0x11:
		width = 4
	case 0x21:
		width = 16
	default:
		return "", "", fmt.Errorf("bad family/protocol %x", hdr[13])
	}
	if size != width*2+4 {
		return "", "", fmt.Errorf("unexpected address len %d", size)
	}
	b := make([]byte, size)
	if _, err := io.ReadFull(r, b); err != nil {
		return "", "", err
	}
	src := net.JoinHostPort(net.IP(b[:width]).String(), strconv.Itoa(int(binary.BigEndian.Uint16(b[2*width:]))))
	dst := net.JoinHostPort(net.IP(b[width:2*width]).String(), strconv.Itoa(int(binary.BigEndian.Uint16(b[2*width+2:]))))
	return src, dst, nil
}

func TestForwardProxyProtocolIndependentConcurrentWire(t *testing.T) {
	proxyIntegrationSetup(t)
	for _, transport := range []string{"direct", "tcp", "tls", "mtls", "ws"} {
		for _, version := range []int{1, 2} {
			t.Run(fmt.Sprintf("%s/v%d", transport, version), func(t *testing.T) { checkIndependentProxyConnections(t, transport, version, "127.0.0.1:0", 32) })
		}
	}
	for _, version := range []int{1, 2} {
		t.Run(fmt.Sprintf("ipv6/v%d", version), func(t *testing.T) { checkIndependentProxyConnections(t, "direct", version, "[::1]:0", 8) })
	}
}

func checkIndependentProxyConnections(t *testing.T, transport string, version int, listenAddr string, count int) {
	checkIndependentProxyConnectionsAcrossFamilies(t, transport, version, listenAddr, listenAddr, count)
}

func checkIndependentProxyConnectionsAcrossFamilies(t *testing.T, transport string, version int, targetListenAddr string, ingressListenAddr string, count int) {
	target, err := net.Listen("tcp", targetListenAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	if err := target.(*net.TCPListener).SetDeadline(time.Now().Add(30 * time.Second)); err != nil {
		t.Fatal(err)
	}
	chain := proxyRelayChain(t, transport, target.Addr().String(), false)
	cfg := &config.ServiceConfig{Name: "audit-concurrent", Addr: ingressListenAddr, Listener: &config.ListenerConfig{Type: "tcp"}, Handler: &config.HandlerConfig{Type: "tcp", Chain: chain, Metadata: map[string]any{"proxyProtocol": version}}, Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target.Addr().String()}}}}
	if transport != "direct" {
		cfg.Forwarder = nil
	}
	ingress := startProxyService(t, cfg)
	errs := make(chan error, count*2+1)
	var server sync.WaitGroup
	server.Add(1)
	go func() {
		defer server.Done()
		var streams sync.WaitGroup
		defer streams.Wait()
		for i := 0; i < count; i++ {
			c, e := target.Accept()
			if e != nil {
				errs <- e
				return
			}
			streams.Add(1)
			go func() {
				defer streams.Done()
				defer c.Close()
				c.SetDeadline(time.Now().Add(10 * time.Second))
				r := bufio.NewReader(c)
				src, dst, e := readIndependentProxyHeader(r, version)
				if e != nil {
					errs <- e
					return
				}
				if _, e = c.Write([]byte("READY\n")); e != nil {
					errs <- e
					return
				}
				line, e := r.ReadString('\n')
				if e != nil {
					errs <- e
					return
				}
				want := src + "|" + dst + "\n"
				if line != want {
					errs <- fmt.Errorf("header addresses crossed streams: payload %q header %q", line, want)
					return
				}
				_, e = c.Write([]byte("OK\n"))
				if e != nil {
					errs <- e
				}
			}()
		}
	}()
	var clients sync.WaitGroup
	for i := 0; i < count; i++ {
		clients.Add(1)
		go func() {
			defer clients.Done()
			c, e := net.Dial("tcp", ingress.Addr().String())
			if e != nil {
				errs <- e
				return
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(10 * time.Second))
			r := bufio.NewReader(c)
			line, e := r.ReadString('\n')
			if e != nil || line != "READY\n" {
				errs <- fmt.Errorf("banner %q: %v", line, e)
				return
			}
			_, e = fmt.Fprintf(c, "%s|%s\n", c.LocalAddr(), c.RemoteAddr())
			if e != nil {
				errs <- e
				return
			}
			line, e = r.ReadString('\n')
			if e != nil || line != "OK\n" {
				errs <- fmt.Errorf("ack %q: %v", line, e)
			}
		}()
	}
	clients.Wait()
	server.Wait()
	close(errs)
	for e := range errs {
		t.Error(e)
	}
}

func TestForwardProxyProtocolIndependentAddressFamilies(t *testing.T) {
	proxyIntegrationSetup(t)
	for _, transport := range []string{"tcp", "tls", "mtls", "ws"} {
		for _, version := range []int{1, 2} {
			t.Run(fmt.Sprintf("ipv6-to-ipv6/%s/v%d", transport, version), func(t *testing.T) {
				checkIndependentProxyConnectionsAcrossFamilies(t, transport, version, "[::1]:0", "[::1]:0", 8)
			})
		}
	}
	for _, family := range []struct{ name, target, ingress string }{
		{"ipv4-to-ipv6", "[::1]:0", "127.0.0.1:0"},
		{"ipv6-to-ipv4", "127.0.0.1:0", "[::1]:0"},
	} {
		for _, transport := range []string{"direct", "tcp", "mtls", "ws"} {
			for _, version := range []int{1, 2} {
				t.Run(fmt.Sprintf("%s/%s/v%d", family.name, transport, version), func(t *testing.T) {
					checkIndependentProxyConnectionsAcrossFamilies(t, transport, version, family.target, family.ingress, 8)
				})
			}
		}
	}
}
