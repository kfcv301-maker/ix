package udp

import (
	"errors"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/go-gost/core/common/bufpool"
	"github.com/go-gost/core/logger"
)

type ListenConfig struct {
	Addr           net.Addr
	Backlog        int
	ReadQueueSize  int
	ReadBufferSize int
	TTL            time.Duration
	Keepalive      bool
	Logger         logger.Logger
}
type listener struct {
	conn          net.PacketConn
	cqueue        chan net.Conn
	connPool      *connPool
	closed        chan struct{}
	errChan       chan error
	config        *ListenConfig
	closeOnce     sync.Once
	lastDropLog   time.Time
	dropsSinceLog uint64
}

func NewListener(conn net.PacketConn, cfg *ListenConfig) net.Listener {
	if cfg == nil {
		cfg = &ListenConfig{}
	}
	// Some internal callers omit these optional settings. Normalize a copy so
	// a zero TTL cannot panic in a goroutine or mutate the caller's config.
	effective := *cfg
	if effective.TTL <= 0 {
		effective.TTL = 5 * time.Second
	}
	if effective.Backlog <= 0 {
		effective.Backlog = 128
	}
	if effective.ReadQueueSize <= 0 {
		effective.ReadQueueSize = 128
	}
	if effective.ReadBufferSize <= 0 {
		effective.ReadBufferSize = 8192
	}
	cfg = &effective

	ln := &listener{
		conn:    conn,
		cqueue:  make(chan net.Conn, cfg.Backlog),
		closed:  make(chan struct{}),
		errChan: make(chan error, 1),
		config:  cfg,
	}
	ln.connPool = newConnPool(cfg.TTL, cfg.Logger)
	go ln.listenLoop()

	return ln
}

func (ln *listener) Accept() (conn net.Conn, err error) {
	select {
	case conn = <-ln.cqueue:
		return
	case <-ln.closed:
		return nil, net.ErrClosed
	case err = <-ln.errChan:
		if err == nil {
			err = net.ErrClosed
		}
		return
	}
}

func (ln *listener) listenLoop() {
	defer ln.Close()
	for {
		select {
		case <-ln.closed:
			return
		default:
		}

		b := bufpool.Get(ln.config.ReadBufferSize)

		n, raddr, err := ln.conn.ReadFrom(b)
		if err != nil {
			bufpool.Put(b)
			ln.errChan <- err
			close(ln.errChan)
			return
		}

		c := ln.getConn(raddr)
		if c == nil {
			bufpool.Put(b)
			continue
		}

		// Preserve the no-copy fast path while the consumer is keeping up.
		// Compact small datagrams only when a backlog is forming, so a 1 KiB
		// packet does not retain an 8 KiB array in a congested peer's queue.
		if len(c.rc) >= 8 && n <= cap(b)/4 {
			packet := bufpool.Get(n)
			copy(packet, b[:n])
			bufpool.Put(b)
			b = packet
		}
		if err := c.WriteQueue(b[:n]); err != nil {
			// Ownership transfers only after a successful enqueue.
			bufpool.Put(b)
			if !errors.Is(err, net.ErrClosed) {
				ln.reportDrop(err)
			}
		}
	}
}

func (ln *listener) Addr() net.Addr {
	if ln.config.Addr != nil {
		return ln.config.Addr
	}
	return ln.conn.LocalAddr()
}

func (ln *listener) Close() error {
	ln.closeOnce.Do(func() {
		close(ln.closed)
		ln.conn.Close()
		ln.connPool.Close()
	})

	return nil
}

func (ln *listener) getConn(raddr net.Addr) *conn {
	select {
	case <-ln.closed:
		return nil
	default:
	}
	key := peerKey(raddr)
	c, ok := ln.connPool.Get(key)
	if ok && !c.isClosed() {
		return c
	}

	c = newConn(ln.conn, ln.Addr(), raddr, ln.config.ReadQueueSize, ln.config.Keepalive)
	select {
	case ln.cqueue <- c:
		if !ln.connPool.Set(key, c) {
			return nil
		}
		return c
	default:
		c.Close()
		sharedQueueBudget.dropped.Add(1)
		ln.reportDrop(errors.New("connection queue is full"))
		return nil
	}
}

// UDP sockets already supply a typed address. Avoid formatting and allocating
// a string on every packet; normalize mapped IPv4 as UDPAddr.String did.
func peerKey(addr net.Addr) any {
	if udpAddr, ok := addr.(*net.UDPAddr); ok {
		endpoint := udpAddr.AddrPort()
		ip := endpoint.Addr().Unmap()
		if uint(udpAddr.Port) <= 65535 && ip.IsValid() && (udpAddr.Zone == "" || ip.Is6()) {
			return netip.AddrPortFrom(ip, endpoint.Port())
		}
	}
	return addr.String()
}

// Log overload at most once a second rather than writing one line per packet.
// The cumulative drop counter remains available even after a burst stops.
func (ln *listener) reportDrop(err error) {
	ln.dropsSinceLog++
	now := time.Now()
	if now.Sub(ln.lastDropLog) >= time.Second {
		if ln.config.Logger != nil {
			ln.config.Logger.Warnf("UDP data discarded: %v (%d packets since last warning)", err, ln.dropsSinceLog)
		}
		ln.lastDropLog = now
		ln.dropsSinceLog = 0
	}
}

// conn is a server side connection for UDP client peer, it implements net.Conn and net.PacketConn.
type conn struct {
	net.PacketConn
	localAddr  net.Addr
	remoteAddr net.Addr
	rc         chan []byte // data receive queue
	idle       int32       // indicate the connection is idle
	closed     chan struct{}
	closeMutex sync.Mutex
	keepalive  bool
	budget     *queueBudget
}

func newConn(c net.PacketConn, laddr, remoteAddr net.Addr, queueSize int, keepalive bool) *conn {
	return &conn{
		PacketConn: c,
		localAddr:  laddr,
		remoteAddr: remoteAddr,
		rc:         make(chan []byte, queueSize),
		closed:     make(chan struct{}),
		keepalive:  keepalive,
		budget:     sharedQueueBudget,
	}
}

func (c *conn) ReadFrom(b []byte) (n int, addr net.Addr, err error) {
	select {
	case bb := <-c.rc:
		n = copy(b, bb)
		c.SetIdle(false)
		c.budget.release(cap(bb))
		bufpool.Put(bb)

	case <-c.closed:
		err = net.ErrClosed
		return
	}

	addr = c.remoteAddr

	return
}

func (c *conn) Read(b []byte) (n int, err error) {
	n, _, err = c.ReadFrom(b)
	return
}

func (c *conn) WriteTo(b []byte, addr net.Addr) (n int, err error) {
	if !c.keepalive {
		defer c.Close()
	}
	n, err = c.PacketConn.WriteTo(b, addr)
	if n > 0 {
		c.SetIdle(false)
	}
	return
}

func (c *conn) Write(b []byte) (n int, err error) {
	return c.WriteTo(b, c.remoteAddr)
}

func (c *conn) Close() error {
	c.closeMutex.Lock()
	defer c.closeMutex.Unlock()

	select {
	case <-c.closed:
	default:
		close(c.closed)
	}
	c.drainQueue()
	return nil
}

// Each receive takes exclusive ownership of that packet. Draining can safely
// overlap a reader or the final enqueue that started just before Close.
func (c *conn) drainQueue() {
	for {
		select {
		case b := <-c.rc:
			c.budget.release(cap(b))
			bufpool.Put(b)
		default:
			return
		}
	}
}

func (c *conn) isClosed() bool {
	select {
	case <-c.closed:
		return true
	default:
		return false
	}
}

func (c *conn) LocalAddr() net.Addr {
	return c.localAddr
}

func (c *conn) RemoteAddr() net.Addr {
	return c.remoteAddr
}

func (c *conn) IsIdle() bool {
	return atomic.LoadInt32(&c.idle) > 0
}

func (c *conn) SetIdle(idle bool) {
	v := int32(0)
	if idle {
		v = 1
	}
	atomic.StoreInt32(&c.idle, v)
}

func (c *conn) WriteQueue(b []byte) error {
	if c.isClosed() {
		return net.ErrClosed
	}
	if !c.budget.acquire(cap(b)) {
		c.budget.dropped.Add(1)
		return errors.New("shared UDP queue memory budget exhausted")
	}
	select {
	case c.rc <- b:
		// Avoid a mutex on every packet. Close drains before returning; if an
		// in-flight enqueue races that drain, the producer drains again after
		// transferring ownership. The data channel itself is never closed.
		if c.isClosed() {
			c.drainQueue()
		}
		return nil
	case <-c.closed:
		c.budget.release(cap(b))
		return net.ErrClosed

	default:
		c.budget.release(cap(b))
		c.budget.dropped.Add(1)
		return errors.New("recv queue is full")
	}
}
