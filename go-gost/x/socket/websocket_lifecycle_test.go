package socket

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

type delayedReadFailure struct {
	net.Conn
	armed                    atomic.Bool
	started, failed, release chan struct{}
	startedOnce, failureOnce sync.Once
}

func (c *delayedReadFailure) Read(p []byte) (int, error) {
	armed := c.armed.Load()
	if armed {
		c.startedOnce.Do(func() { close(c.started) })
	}
	n, err := c.Conn.Read(p)
	if armed && err != nil {
		c.failureOnce.Do(func() { close(c.failed) })
		<-c.release
	}
	return n, err
}

func waitLifecycle(t *testing.T, signal <-chan struct{}, message string) {
	t.Helper()
	select {
	case <-signal:
	case <-time.After(2 * time.Second):
		t.Fatal(message)
	}
}

func lifecyclePanel(t *testing.T) (*httptest.Server, <-chan *websocket.Conn) {
	t.Helper()
	connections := make(chan *websocket.Conn, 4)
	finished := make(chan struct{})
	upgrader := websocket.Upgrader{}
	panel := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		defer c.Close()
		connections <- c
		<-finished
	}))
	t.Cleanup(func() { close(finished); panel.Close() })
	return panel, connections
}

func TestOldWebSocketReadFailureCannotDisconnectReplacement(t *testing.T) {
	panel, peers := lifecyclePanel(t)
	var delayed *delayedReadFailure
	dialer := websocket.Dialer{NetDialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
		c, err := (&net.Dialer{}).DialContext(ctx, network, addr)
		if err != nil {
			return nil, err
		}
		delayed = &delayedReadFailure{Conn: c, started: make(chan struct{}), failed: make(chan struct{}), release: make(chan struct{})}
		return delayed, nil
	}}
	url := "ws" + strings.TrimPrefix(panel.URL, "http")
	old, _, err := dialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { old.Close() })
	<-peers
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(delayed.release) }) }
	t.Cleanup(unblock)
	reporter := NewWebSocketReporter(panel.URL, "lifecycle-fixture")
	reporter.conn, reporter.connected = old, true
	t.Cleanup(reporter.Stop)
	delayed.armed.Store(true)
	done := make(chan struct{})
	go func() { defer close(done); reporter.receiveMessages(old) }()
	waitLifecycle(t, delayed.started, "old receiver did not start")
	old.Close()
	waitLifecycle(t, delayed.failed, "old read did not fail")
	replacement, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}
	peer := <-peers
	reporter.connMutex.Lock()
	reporter.conn, reporter.connected = replacement, true
	reporter.connMutex.Unlock()
	unblock()
	waitLifecycle(t, done, "old receiver did not exit")
	if err := reporter.sendSystemInfo(SystemInfo{}); err != nil {
		t.Fatalf("late failure disabled the replacement heartbeat: %v", err)
	}
	peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err := peer.ReadMessage(); err != nil {
		t.Fatalf("replacement connection cannot report: %v", err)
	}
}

func TestOldWebSocketCloseHandlerCannotDisconnectReplacement(t *testing.T) {
	panel, peers := lifecyclePanel(t)
	reporter := NewWebSocketReporter(panel.URL, "lifecycle-fixture")
	reporter.addr, reporter.secret = panel.URL, "lifecycle-fixture"
	t.Cleanup(reporter.Stop)
	if err := reporter.connect(); err != nil {
		t.Fatal(err)
	}
	<-peers
	old := reporter.conn
	t.Cleanup(func() { old.Close() })
	reporter.connMutex.Lock()
	reporter.connected = false
	reporter.connMutex.Unlock()
	if err := reporter.connect(); err != nil {
		t.Fatal(err)
	}
	peer := <-peers
	if err := old.CloseHandler()(websocket.CloseNormalClosure, ""); err != nil {
		t.Fatal(err)
	}
	if err := reporter.sendSystemInfo(SystemInfo{}); err != nil {
		t.Fatalf("old close callback disabled the replacement: %v", err)
	}
	peer.SetReadDeadline(time.Now().Add(time.Second))
	if _, _, err := peer.ReadMessage(); err != nil {
		t.Fatal(err)
	}
}

func TestReporterStopCancelsInflightHandshake(t *testing.T) {
	arrived, release := make(chan struct{}), make(chan struct{})
	upgrader := websocket.Upgrader{}
	panel := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		close(arrived)
		select {
		case <-r.Context().Done():
			return
		case <-release:
		}
		if c, err := upgrader.Upgrade(w, r, nil); err == nil {
			c.Close()
		}
	}))
	defer panel.Close()
	defer close(release)
	reporter := NewWebSocketReporter(panel.URL, "lifecycle-fixture")
	reporter.addr, reporter.secret = panel.URL, "lifecycle-fixture"
	t.Cleanup(reporter.Stop)
	connected := make(chan error, 1)
	go func() { connected <- reporter.connect() }()
	waitLifecycle(t, arrived, "handshake did not reach panel")
	stopped := make(chan struct{})
	go func() { defer close(stopped); reporter.Stop() }()
	waitLifecycle(t, stopped, "Stop blocked on a stalled handshake")
	select {
	case err := <-connected:
		if err == nil {
			t.Fatal("cancelled handshake was published as a live connection")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Stop left the authenticated handshake running")
	}
	reporter.connMutex.Lock()
	defer reporter.connMutex.Unlock()
	if reporter.connected || reporter.conn != nil {
		t.Fatal("stopped reporter retained a live connection")
	}
}
