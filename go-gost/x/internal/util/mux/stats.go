package mux

import (
	"net"
	"os"
	"sync"
)

// Stats describes this process's live smux sessions and streams. StreamsCurrent
// includes streams awaiting Accept; a stream has an endpoint on each peer, so
// metrics from two nodes must not be summed as unique end-to-end connections.
type Stats struct {
	Enabled         bool   `json:"enabled"`
	SessionsCurrent uint64 `json:"sessionsCurrent"`
	StreamsCurrent  uint64 `json:"streamsCurrent"`
	SessionsCreated uint64 `json:"sessionsCreated"`
	SessionsClosed  uint64 `json:"sessionsClosed"`
}

// Choose once when the process starts. Disabled sessions use their original
// transport directly, so the default packet path has no instrumentation wrapper.
var metricsEnabled = os.Getenv("LUNARIS_MUX_METRICS") == "1"

var sessionStats = struct {
	sync.Mutex
	sessions map[*Session]struct{}
	created  uint64
	closed   uint64
}{sessions: make(map[*Session]struct{})}

// StatsSnapshot samples smux's stream counts. Tracking changes only at session
// creation and physical connection close, with no counters on the packet path
// and no per-session monitoring goroutine.
func StatsSnapshot() Stats {
	if !metricsEnabled {
		return Stats{}
	}
	sessionStats.Lock()
	stats := Stats{
		Enabled:         true,
		SessionsCreated: sessionStats.created,
		SessionsClosed:  sessionStats.closed,
	}
	sessions := make([]*Session, 0, len(sessionStats.sessions))
	for session := range sessionStats.sessions {
		sessions = append(sessions, session)
	}
	sessionStats.Unlock()

	// Do not hold the registry lock while acquiring smux's stream lock.
	for _, session := range sessions {
		if !session.IsClosed() {
			stats.SessionsCurrent++
			stats.StreamsCurrent += uint64(session.NumStreams())
		}
	}
	return stats
}

// sessionConn observes smux's own Close calls as well as Session.Close. This
// also removes the registry reference when a keepalive timeout closes smux.
// attach handles a transport that closes during smux construction.
type sessionConn struct {
	net.Conn
	mu      sync.Mutex
	session *Session
	closed  bool
}

type bufferWriter interface {
	WriteBuffers([][]byte) (int, error)
}

type bufferedSessionConn struct {
	*sessionConn
	writer bufferWriter
}

func (c *bufferedSessionConn) WriteBuffers(buffers [][]byte) (int, error) {
	return c.writer.WriteBuffers(buffers)
}

func newSessionConn(conn net.Conn) (*sessionConn, net.Conn) {
	tracked := &sessionConn{Conn: conn}
	// smux detects this optional interface once to enable scatter/gather writes.
	// Preserve it instead of silently selecting a larger copied frame buffer.
	if writer, ok := conn.(bufferWriter); ok {
		return tracked, &bufferedSessionConn{sessionConn: tracked, writer: writer}
	}
	return tracked, tracked
}

func (c *sessionConn) attach(session *Session) {
	c.mu.Lock()
	defer c.mu.Unlock()

	sessionStats.Lock()
	defer sessionStats.Unlock()
	if c.closed {
		sessionStats.created++
		sessionStats.closed++
		return
	}
	c.session = session
	sessionStats.created++
	sessionStats.sessions[session] = struct{}{}
}

func (c *sessionConn) Close() error {
	err := c.Conn.Close()
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return err
	}
	c.closed = true
	if c.session != nil {
		sessionStats.Lock()
		delete(sessionStats.sessions, c.session)
		sessionStats.closed++
		sessionStats.Unlock()
		c.session = nil
	}
	return err
}
