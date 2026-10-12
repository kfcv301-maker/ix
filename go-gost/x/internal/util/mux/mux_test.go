package mux

import (
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	smux "github.com/xtaci/smux"
)

func enableMetrics(t *testing.T) {
	setMetrics(t, true)
}

func setMetrics(t *testing.T, enabled bool) {
	t.Helper()
	previous := metricsEnabled
	metricsEnabled = enabled
	t.Cleanup(func() { metricsEnabled = previous })
}

func sessionPair(t *testing.T, versions ...int) (*Session, *Session) {
	t.Helper()
	a, b := net.Pipe()
	cfg := &Config{KeepAliveDisabled: true}
	if len(versions) > 0 {
		cfg.Version = versions[0]
	}
	client, err := ClientSession(a, cfg)
	if err != nil {
		t.Fatal(err)
	}
	server, err := ServerSession(b, cfg)
	if err != nil {
		client.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		client.Close()
		server.Close()
	})
	return client, server
}

func streamPair(t *testing.T, client, server *Session) (net.Conn, net.Conn) {
	t.Helper()
	c, err := client.GetConn()
	if err != nil {
		t.Fatal(err)
	}
	s, err := server.Accept()
	if err != nil {
		c.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		c.Close()
		s.Close()
	})
	return c, s
}

func assertStreamTransfer(t *testing.T, writer, reader net.Conn) {
	t.Helper()
	const payload = "unrelated live stream"
	errCh := make(chan error, 1)
	go func() {
		_, err := io.WriteString(writer, payload)
		errCh <- err
	}()
	if err := reader.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, len(payload))
	_, err := io.ReadFull(reader, buf)
	reader.SetReadDeadline(time.Time{})
	if err != nil {
		t.Fatalf("unrelated stream interrupted: %v", err)
	}
	if string(buf) != payload {
		t.Fatalf("unexpected payload: %q", buf)
	}
	if err := <-errCh; err != nil {
		t.Fatal(err)
	}
}

func TestStreamDeadlineDoesNotAffectOtherStreams(t *testing.T) {
	setMetrics(t, false)
	for _, version := range []int{1, 2} {
		for _, mode := range []string{"read", "write", "both"} {
			t.Run(fmt.Sprintf("v%d/%s", version, mode), func(t *testing.T) {
				client, server := sessionPair(t, version)
				timed, _ := streamPair(t, client, server)
				other, peer := streamPair(t, client, server)
				deadline := time.Now().Add(-time.Second)
				var err error
				switch mode {
				case "read":
					err = timed.SetReadDeadline(deadline)
				case "write":
					err = timed.SetWriteDeadline(deadline)
				case "both":
					err = timed.SetDeadline(deadline)
				}
				if err != nil {
					t.Fatal(err)
				}
				if mode != "write" {
					_, err = timed.Read(make([]byte, 1))
				} else {
					_, err = timed.Write([]byte("expired"))
				}
				var timeout net.Error
				if !errors.As(err, &timeout) || !timeout.Timeout() {
					t.Fatalf("deadline should time out this stream, got %v", err)
				}
				assertStreamTransfer(t, peer, other)
				assertStreamTransfer(t, other, peer)
				if client.IsClosed() || server.IsClosed() {
					t.Fatal("logical stream deadline closed its physical session")
				}
			})
		}
	}
}

type vectorConn struct {
	net.Conn
	writes atomic.Uint64
}

func (c *vectorConn) WriteBuffers(buffers [][]byte) (int, error) {
	c.writes.Add(1)
	var size int
	for _, buffer := range buffers {
		size += len(buffer)
	}
	joined := make([]byte, 0, size)
	for _, buffer := range buffers {
		joined = append(joined, buffer...)
	}
	return c.Conn.Write(joined)
}

func TestTrackingPreservesScatterGatherTransport(t *testing.T) {
	enableMetrics(t)
	a, b := net.Pipe()
	transport := &vectorConn{Conn: a}
	client, err := ClientSession(transport, &Config{Version: 2, KeepAliveDisabled: true})
	if err != nil {
		t.Fatal(err)
	}
	server, err := ServerSession(b, &Config{Version: 2, KeepAliveDisabled: true})
	if err != nil {
		client.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close(); server.Close() })
	stream, peer := streamPair(t, client, server)
	assertStreamTransfer(t, stream, peer)
	if transport.writes.Load() == 0 {
		t.Fatal("tracking hid the transport's scatter/gather capability")
	}
}

func TestStatsFollowSessionAndStreamLifecycle(t *testing.T) {
	enableMetrics(t)
	before := StatsSnapshot()
	client, server := sessionPair(t)
	stream, peer := streamPair(t, client, server)
	stats := StatsSnapshot()
	if stats.SessionsCurrent != before.SessionsCurrent+2 || stats.StreamsCurrent != before.StreamsCurrent+2 {
		t.Fatalf("expected two session and stream endpoints: before=%+v after=%+v", before, stats)
	}
	if stats.SessionsCreated != before.SessionsCreated+2 {
		t.Fatalf("unexpected created count: %+v", stats)
	}
	stream.Close()
	peer.Close()
	if current := StatsSnapshot().StreamsCurrent; current != before.StreamsCurrent {
		t.Fatalf("closed streams remain counted: %d", current)
	}
	client.Close()
	server.Close()
	client.Close()
	server.Close()
	stats = StatsSnapshot()
	if stats.SessionsCurrent != before.SessionsCurrent || stats.SessionsClosed != before.SessionsClosed+2 {
		t.Fatalf("session close must count once: before=%+v after=%+v", before, stats)
	}
}

func TestStatsReleaseInternallyClosedSession(t *testing.T) {
	enableMetrics(t)
	before := StatsSnapshot()
	a, b := net.Pipe()
	client, err := ClientSession(a, &Config{KeepAliveInterval: time.Millisecond, KeepAliveTimeout: 5 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close(); b.Close() })
	// Drain frames but never reply, allowing smux's keepalive to close itself.
	go io.Copy(io.Discard, b)
	deadline := time.Now().Add(time.Second)
	for time.Now().Before(deadline) {
		stats := StatsSnapshot()
		if stats.SessionsClosed == before.SessionsClosed+1 && stats.SessionsCurrent == before.SessionsCurrent {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatalf("internally closed session retained in registry: %+v", StatsSnapshot())
}

func TestConcurrentStatsAndSessionClose(t *testing.T) {
	enableMetrics(t)
	before := StatsSnapshot()
	for i := 0; i < 20; i++ {
		client, server := sessionPair(t)
		streamPair(t, client, server)
		var wg sync.WaitGroup
		for _, fn := range []func(){
			func() { client.Close() },
			func() { server.Close() },
			func() { client.Close() },
			func() {
				for j := 0; j < 20; j++ {
					StatsSnapshot()
				}
			},
		} {
			wg.Add(1)
			go func(fn func()) { defer wg.Done(); fn() }(fn)
		}
		wg.Wait()
	}
	stats := StatsSnapshot()
	if stats.SessionsCurrent != before.SessionsCurrent || stats.SessionsCreated-before.SessionsCreated != 40 || stats.SessionsClosed-before.SessionsClosed != 40 {
		t.Fatalf("unbalanced concurrent lifecycle: before=%+v after=%+v", before, stats)
	}
}

func TestStatsRegistrationRacingWithTransportClose(t *testing.T) {
	enableMetrics(t)
	before := StatsSnapshot()
	for i := 0; i < 50; i++ {
		a, b := net.Pipe()
		tracked, transport := newSessionConn(a)
		raw, err := smux.Client(transport, convertConfig(&Config{KeepAliveDisabled: true}))
		if err != nil {
			a.Close()
			b.Close()
			t.Fatal(err)
		}
		session := &Session{conn: a, session: raw}
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); tracked.attach(session) }()
		go func() { defer wg.Done(); session.Close() }()
		wg.Wait()
		b.Close()
	}
	stats := StatsSnapshot()
	if stats.SessionsCurrent != before.SessionsCurrent || stats.SessionsCreated-before.SessionsCreated != 50 || stats.SessionsClosed-before.SessionsClosed != 50 {
		t.Fatalf("construction/close race retained a session: before=%+v after=%+v", before, stats)
	}
}

func TestInvalidSessionConfigDoesNotRegister(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		t.Run(fmt.Sprintf("enabled=%t", enabled), func(t *testing.T) {
			setMetrics(t, enabled)
			before := StatsSnapshot()
			a, b := net.Pipe()
			defer a.Close()
			defer b.Close()
			if _, err := ClientSession(a, &Config{Version: 3}); err == nil {
				t.Fatal("invalid smux version accepted")
			}
			if _, err := ServerSession(b, &Config{Version: 3}); err == nil {
				t.Fatal("invalid server smux version accepted")
			}
			if after := StatsSnapshot(); after != before {
				t.Fatalf("failed creation affected stats: before=%+v after=%+v", before, after)
			}
		})
	}
}

func TestDisabledMetricsDoNotTrackSessions(t *testing.T) {
	enableMetrics(t)
	before := StatsSnapshot()
	metricsEnabled = false
	client, server := sessionPair(t)
	stream, peer := streamPair(t, client, server)
	assertStreamTransfer(t, stream, peer)
	if stats := StatsSnapshot(); stats != (Stats{}) {
		t.Fatalf("disabled metrics should report unavailable counters: %+v", stats)
	}
	client.Close()
	server.Close()
	metricsEnabled = true
	if after := StatsSnapshot(); after != before {
		t.Fatalf("disabled session was instrumented: before=%+v after=%+v", before, after)
	}
}
