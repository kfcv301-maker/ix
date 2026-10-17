package udp

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"io"
	"math/big"
	"net"
	"testing"
	"time"

	"github.com/quic-go/quic-go"
)

// Exercise actual encrypted QUIC streams through the native UDP association
// path. HY2 uses QUIC over UDP, so packet boundaries, bidirectional activity,
// and queue cleanup must survive more than a synthetic single-packet echo.
func TestQUICStreamsThroughUDPListener(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certTemplate := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, certTemplate, certTemplate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	tlsConfig := &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, NextProtos: []string{"lunaris-resource-test"}}
	server, err := quic.ListenAddr("127.0.0.1:0", tlsConfig, &quic.Config{MaxIdleTimeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { server.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	serverDone := make(chan error, 1)
	go func() {
		conn, err := server.Accept(ctx)
		if err != nil {
			serverDone <- err
			return
		}
		defer conn.CloseWithError(0, "test complete")
		for i := 0; i < 3; i++ {
			stream, err := conn.AcceptStream(ctx)
			if err != nil {
				serverDone <- err
				return
			}
			if _, err := io.Copy(stream, stream); err != nil {
				serverDone <- err
				return
			}
			if err := stream.Close(); err != nil {
				serverDone <- err
				return
			}
		}
		serverDone <- nil
		// A stream FIN is queued asynchronously. Keep the QUIC connection alive
		// until the client has received every echoed byte and ends the test.
		<-ctx.Done()
	}()

	ln, _ := udpTestListener(t, 8192)
	upstream, err := net.DialUDP("udp4", nil, server.Addr().(*net.UDPAddr))
	if err != nil {
		t.Fatal(err)
	}
	relayDone := make(chan struct{})
	go func() {
		defer close(relayDone)
		peer, err := ln.Accept()
		if err != nil {
			return
		}
		defer peer.Close()
		copied := make(chan struct{}, 1)
		go func() { _, _ = io.Copy(upstream, peer); upstream.Close(); copied <- struct{}{} }()
		_, _ = io.Copy(peer, upstream)
		peer.Close()
		<-copied
	}()
	t.Cleanup(func() {
		ln.Close()
		upstream.Close()
		select {
		case <-relayDone:
		case <-time.After(3 * time.Second):
			t.Error("QUIC relay retained a copy task after closing")
		}
	})
	client, err := quic.DialAddr(ctx, ln.Addr().String(), &tls.Config{InsecureSkipVerify: true, NextProtos: tlsConfig.NextProtos}, &quic.Config{MaxIdleTimeout: 10 * time.Second})
	if err != nil {
		t.Fatal(err)
	}
	defer client.CloseWithError(0, "test complete")
	for i := 0; i < 3; i++ {
		stream, err := client.OpenStreamSync(ctx)
		if err != nil {
			t.Fatal(err)
		}
		payload := bytes.Repeat([]byte{byte(i + 1)}, 1<<20)
		received := make(chan []byte, 1)
		readErr := make(chan error, 1)
		go func() { data, err := io.ReadAll(stream); received <- data; readErr <- err }()
		if _, err := stream.Write(payload); err != nil {
			t.Fatal(err)
		}
		if err := stream.Close(); err != nil {
			t.Fatal(err)
		}
		select {
		case result := <-received:
			if err := <-readErr; err != nil || !bytes.Equal(result, payload) {
				t.Fatalf("QUIC stream %d changed: len=%d err=%v", i, len(result), err)
			}
		case <-ctx.Done():
			t.Fatal("QUIC stream stalled")
		}
	}
	if err := <-serverDone; err != nil {
		t.Fatal(err)
	}
}
