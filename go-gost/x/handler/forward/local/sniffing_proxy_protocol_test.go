package local_test

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/parsing"
)

func TestForwardProxyProtocolWithSniffing(t *testing.T) {
	proxyIntegrationSetup(t)
	for _, protocol := range []string{"http", "tls"} {
		for _, version := range []int{0, 1, 2} {
			t.Run(fmt.Sprintf("%s/v%d", protocol, version), func(t *testing.T) {
				checkSniffingProxyProtocol(t, protocol, version)
			})
		}
	}
}

func checkSniffingProxyProtocol(t *testing.T, protocol string, version int) {
	t.Helper()
	target, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer target.Close()
	ingress := startProxyService(t, &config.ServiceConfig{
		Name: "proxy-sniffing", Addr: "127.0.0.1:0",
		Listener: &config.ListenerConfig{Type: "tcp"},
		Handler: &config.HandlerConfig{Type: "tcp", Metadata: map[string]any{
			"proxyProtocol": version, "sniffing": true, "sniffing.timeout": "1s",
		}},
		Forwarder: &config.ForwarderConfig{Nodes: []*config.ForwardNodeConfig{{Name: "target", Addr: target.Addr().String()}}},
	})
	c, err := net.Dial("tcp", ingress.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(5 * time.Second))
	expectedHeader := expectedSniffingProxyHeader(version, c.LocalAddr().(*net.TCPAddr), c.RemoteAddr().(*net.TCPAddr))
	result := make(chan error, 1)
	go func() {
		cc, err := target.Accept()
		if err != nil {
			result <- err
			return
		}
		defer cc.Close()
		cc.SetDeadline(time.Now().Add(5 * time.Second))
		// Assert raw bytes independently of the encoder library. This also
		// proves the header precedes HTTP bytes or the TLS ClientHello.
		header := make([]byte, len(expectedHeader))
		if _, err = io.ReadFull(cc, header); err != nil || !bytes.Equal(header, expectedHeader) {
			result <- fmt.Errorf("header mismatch: %x, expected %x: %v", header, expectedHeader, err)
			return
		}
		if protocol == "tls" {
			cc = tls.Server(cc, parsing.DefaultTLSConfig().Clone())
		}
		req, err := http.ReadRequest(bufio.NewReader(cc))
		if err != nil {
			result <- err
			return
		}
		req.Body.Close()
		if req.URL.Path != "/sniff-test" || req.Header.Get("X-Forwarded-For") != "1.1.1.1" {
			result <- fmt.Errorf("request changed: %s, %v", req.URL, req.Header)
			return
		}
		_, err = io.WriteString(cc, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
		result <- err
	}()
	if protocol == "tls" {
		c = tls.Client(c, &tls.Config{InsecureSkipVerify: true, ServerName: "sniff.test"})
	}
	if _, err := io.WriteString(c, "GET /sniff-test HTTP/1.1\r\nHost: sniff.test\r\nX-Forwarded-For: 1.1.1.1\r\nConnection: close\r\n\r\n"); err != nil {
		t.Fatal(err)
	}
	resp, err := http.ReadResponse(bufio.NewReader(c), nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil || resp.StatusCode != http.StatusOK || string(body) != "OK" {
		t.Fatalf("sniffed exchange failed: response %v, body %q, error %v", resp.Status, body, err)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}

func expectedSniffingProxyHeader(version int, src, dst *net.TCPAddr) []byte {
	switch version {
	case 1:
		return []byte(fmt.Sprintf("PROXY TCP4 %s %s %d %d\r\n", src.IP, dst.IP, src.Port, dst.Port))
	case 2:
		b := append([]byte("\r\n\r\n\x00\r\nQUIT\n"), 0x21, 0x11, 0, 12)
		b = append(b, src.IP.To4()...)
		b = append(b, dst.IP.To4()...)
		b = binary.BigEndian.AppendUint16(b, uint16(src.Port))
		return binary.BigEndian.AppendUint16(b, uint16(dst.Port))
	default:
		return nil
	}
}
