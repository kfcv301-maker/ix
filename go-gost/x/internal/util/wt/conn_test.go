package wt

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"io"
	"math/big"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/quic-go/quic-go/http3"
	webtransport "github.com/quic-go/webtransport-go"
)

func TestConnRoundTripOverWebTransport(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	certificate := &x509.Certificate{SerialNumber: big.NewInt(1), NotBefore: time.Now().Add(-time.Hour),
		NotAfter: time.Now().Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}}
	der, err := x509.CreateCertificate(rand.Reader, certificate, certificate, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	packet, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer packet.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	result := make(chan error, 1)
	clientDone := make(chan struct{})
	server := &webtransport.Server{H3: &http3.Server{TLSConfig: &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}},
		MinVersion:   tls.VersionTLS13, NextProtos: []string{http3.NextProtoH3}}},
		CheckOrigin: func(r *http.Request) bool { return true }}
	server.H3.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session, err := server.Upgrade(w, r)
		if err != nil {
			result <- err
			return
		}
		defer session.CloseWithError(0, "")
		stream, err := session.AcceptStream(ctx)
		if err != nil {
			result <- err
			return
		}
		connection := Conn(session, stream)
		defer connection.Close()
		connection.SetDeadline(time.Now().Add(5 * time.Second))
		data := make([]byte, 4)
		if _, err = io.ReadFull(connection, data); err == nil {
			_, err = connection.Write(data)
		}
		result <- err
		<-clientDone
	})
	webtransport.ConfigureHTTP3Server(server.H3)
	defer server.Close()
	defer close(clientDone)
	go server.Serve(packet)
	dialer := &webtransport.Dialer{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}
	defer dialer.Close()
	_, session, err := dialer.Dial(ctx, "https://"+packet.LocalAddr().String()+"/wt", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.CloseWithError(0, "")
	stream, err := session.OpenStreamSync(ctx)
	if err != nil {
		t.Fatal(err)
	}
	connection := Conn(session, stream)
	defer connection.Close()
	connection.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err = connection.Write([]byte("ping")); err != nil {
		t.Fatal(err)
	}
	data := make([]byte, 4)
	if _, err = io.ReadFull(connection, data); err != nil {
		t.Fatal(err)
	}
	if string(data) != "ping" {
		t.Fatalf("unexpected forwarded data: %q", data)
	}
	if err := <-result; err != nil {
		t.Fatal(err)
	}
}
