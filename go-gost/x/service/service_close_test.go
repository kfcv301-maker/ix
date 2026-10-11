package service

import (
	"context"
	"io"
	"net"
	"sync"
	"sync/atomic"
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

type countedCloseHandler struct {
	closeTestHandler
	closed atomic.Int32
}

func (h *countedCloseHandler) Close() error { h.closed.Add(1); return nil }

func TestCloseRunsHandlerCleanupOnceAcrossConcurrentCallers(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	h := &countedCloseHandler{}
	svc := NewService("close-once", &closeTestListener{ln}, h, LoggerOption(logger.NewLogger(logger.OutputOption(io.Discard))))
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _ = svc.Close() }()
	}
	wg.Wait()
	if h.closed.Load() != 1 {
		t.Fatalf("handler cleanup ran %d times", h.closed.Load())
	}
}

func TestTerminalListenerErrorReleasesEstablishedConnections(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	h := &closeTestHandler{accepted: make(chan struct{})}
	svc := NewService("listener-failure", &closeTestListener{ln}, h, LoggerOption(logger.NewLogger(logger.OutputOption(io.Discard))))
	t.Cleanup(func() { svc.Close() })
	done := make(chan struct{})
	go func() { defer close(done); _ = svc.Serve() }()
	client, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	select {
	case <-h.accepted:
	case <-time.After(2 * time.Second):
		t.Fatal("connection not accepted")
	}
	// Simulate a terminal socket failure without an explicit service stop.
	ln.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("terminal listener error left a connection handler blocked")
	}
	_ = client.SetReadDeadline(time.Now().Add(time.Second))
	var data [1]byte
	if _, err := client.Read(data[:]); err == nil {
		t.Fatal("connection survived terminal listener failure")
	}
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
