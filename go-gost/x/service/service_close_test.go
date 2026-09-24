package service

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/handler"
	"github.com/go-gost/core/metadata"
	"github.com/go-gost/x/logger"
)

type closeTestListener struct{ net.Listener }

func (*closeTestListener) Init(metadata.Metadata) error { return nil }

type closeTestHandler struct{ accepted chan struct{} }

func (*closeTestHandler) Init(metadata.Metadata) error { return nil }

func (h *closeTestHandler) Handle(_ context.Context, conn net.Conn, _ ...handler.HandleOption) error {
	close(h.accepted)
	_, err := io.Copy(io.Discard, conn)
	return err
}

func TestCloseDisconnectsEstablishedConnections(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	h := &closeTestHandler{accepted: make(chan struct{})}
	svc := NewService("close-test", &closeTestListener{listener}, h,
		LoggerOption(logger.NewLogger(logger.OutputOption(io.Discard))))
	serveDone := make(chan struct{})
	go func() {
		defer close(serveDone)
		_ = svc.Serve()
	}()
	client, err := net.Dial("tcp", listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	select {
	case <-h.accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("service did not accept the connection")
	}
	if err := svc.Close(); err != nil {
		t.Fatal(err)
	}
	if err := client.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatal(err)
	}
	var one [1]byte
	if _, err := client.Read(one[:]); err == nil {
		t.Fatal("established connection remained usable after service closed")
	}
	select {
	case <-serveDone:
	case <-time.After(2 * time.Second):
		t.Fatal("service did not stop after closing its connections")
	}
}
