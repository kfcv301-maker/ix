package udp

import (
	"bytes"
	"errors"
	"io"
	"net"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/go-gost/core/common/bufpool"
)

func testConn(budget *queueBudget, size int) *conn {
	c := newConn(nil, &net.UDPAddr{}, &net.UDPAddr{}, size, true)
	c.budget = budget
	return c
}

func TestQueueBudgetAccountsBackingArraysAcrossPeers(t *testing.T) {
	budget := &queueBudget{limit: 2 * 8192}
	a, b := testConn(budget, 8), testConn(budget, 8)
	t.Cleanup(func() { a.Close(); b.Close() })
	for _, c := range []*conn{a, b} {
		packet := bufpool.Get(8192)
		packet[0] = 42
		if err := c.WriteQueue(packet[:1]); err != nil {
			bufpool.Put(packet)
			t.Fatal(err)
		}
	}
	rejected := bufpool.Get(8192)
	if err := a.WriteQueue(rejected[:1]); err == nil {
		t.Fatal("budget allowed another 8 KiB backing array for a one-byte packet")
	}
	bufpool.Put(rejected)
	var received [1]byte
	if _, err := a.Read(received[:]); err != nil || received[0] != 42 {
		t.Fatalf("packet changed: %v, %v", received, err)
	}
	if budget.bytes.Load() != 8192 || budget.dropped.Load() != 1 {
		t.Fatalf("incorrect accounting: bytes=%d dropped=%d", budget.bytes.Load(), budget.dropped.Load())
	}
	b.Close()
	if budget.bytes.Load() != 0 {
		t.Fatal("closing a peer retained queued backing arrays")
	}
}

func TestCloseDrainsQueueAndRejectsFurtherPackets(t *testing.T) {
	budget := &queueBudget{limit: 1 << 20}
	c := testConn(budget, 32)
	for i := 0; i < 32; i++ {
		packet := bufpool.Get(8192)
		if err := c.WriteQueue(packet[:100]); err != nil {
			bufpool.Put(packet)
			t.Fatal(err)
		}
	}
	c.Close()
	c.Close()
	if len(c.rc) != 0 || budget.bytes.Load() != 0 {
		t.Fatal("closed peer retained queue memory")
	}
	packet := bufpool.Get(8192)
	defer bufpool.Put(packet)
	if err := c.WriteQueue(packet); !errors.Is(err, net.ErrClosed) {
		t.Fatalf("enqueue after close returned %v", err)
	}
}

func TestConcurrentReadEnqueueAndCloseReleaseAllQueueMemory(t *testing.T) {
	for iteration := 0; iteration < 50; iteration++ {
		budget := &queueBudget{limit: 16 * 8192}
		c := testConn(budget, 16)
		var wg sync.WaitGroup
		wg.Add(3)
		go func() {
			defer wg.Done()
			for i := 0; i < 1000; i++ {
				packet := bufpool.Get(8192)
				if err := c.WriteQueue(packet[:100]); err != nil {
					bufpool.Put(packet)
				}
			}
		}()
		go func() {
			defer wg.Done()
			var received [100]byte
			for {
				if _, err := c.Read(received[:]); err != nil {
					return
				}
			}
		}()
		go func() { defer wg.Done(); c.Close() }()
		wg.Wait()
		c.Close()
		if budget.bytes.Load() != 0 || len(c.rc) != 0 {
			t.Fatalf("iteration %d retained %d bytes", iteration, budget.bytes.Load())
		}
	}
}

func TestPoolCloseIsConcurrentSafeAndRejectsNewPeers(t *testing.T) {
	p := newConnPool(time.Hour)
	var wg sync.WaitGroup
	wg.Add(6)
	for worker := 0; worker < 4; worker++ {
		go func(worker int) {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				p.Set(worker*100+i, testConn(&queueBudget{limit: 8192}, 1))
			}
		}(worker)
	}
	for i := 0; i < 2; i++ {
		go func() { defer wg.Done(); p.Close() }()
	}
	wg.Wait()
	p.m.Range(func(_, _ any) bool { t.Error("closed pool retained a peer"); return true })
	c := testConn(&queueBudget{limit: 8192}, 1)
	if p.Set("late", c) || !c.isClosed() {
		t.Fatal("closed pool accepted a new peer")
	}
}

func udpTestListener(t testing.TB, bufferSize int) (*listener, *net.UDPConn) {
	t.Helper()
	server, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	ln := NewListener(server, &ListenConfig{Backlog: 128, ReadQueueSize: 128, ReadBufferSize: bufferSize, Keepalive: true, TTL: time.Hour}).(*listener)
	client, err := net.DialUDP("udp4", nil, server.LocalAddr().(*net.UDPAddr))
	if err != nil {
		ln.Close()
		t.Fatal(err)
	}
	_ = client.SetReadBuffer(4 << 20)
	_ = server.SetReadBuffer(4 << 20)
	_ = client.SetDeadline(time.Now().Add(10 * time.Second))
	t.Cleanup(func() { client.Close(); ln.Close() })
	return ln, client
}

func acceptTestPeer(t testing.TB, ln *listener) *conn {
	t.Helper()
	accepted := make(chan net.Conn, 1)
	go func() { c, _ := ln.Accept(); accepted <- c }()
	select {
	case c := <-accepted:
		if c == nil {
			t.Fatal("listener did not accept UDP peer")
		}
		return c.(*conn)
	case <-time.After(3 * time.Second):
		t.Fatal("UDP accept timed out")
		return nil
	}
}

func TestUDPDatagramsPreserveBoundariesAndContents(t *testing.T) {
	ln, client := udpTestListener(t, 65535)
	for i, size := range []int{32, 1450, 8192, 32768, 65507} {
		payload := bytes.Repeat([]byte{byte(i + 1)}, size)
		if _, err := client.Write(payload); err != nil {
			t.Fatal(err)
		}
		var c *conn
		if i == 0 {
			c = acceptTestPeer(t, ln)
		} else {
			c, _ = ln.connPool.Get(client.LocalAddr().String())
		}
		buffer := make([]byte, 65535)
		n, addr, err := c.ReadFrom(buffer)
		if err != nil || addr.String() != client.LocalAddr().String() || !bytes.Equal(buffer[:n], payload) {
			t.Fatalf("datagram of %d bytes changed: n=%d err=%v", size, n, err)
		}
		if _, err := c.Write(buffer[:n]); err != nil {
			t.Fatal(err)
		}
		n, err = client.Read(buffer)
		if err != nil || !bytes.Equal(buffer[:n], payload) {
			t.Fatalf("UDP reply changed: %v", err)
		}
	}
}

func TestDownstreamUDPActivityKeepsPeerAlive(t *testing.T) {
	ln, client := udpTestListener(t, 8192)
	payload := []byte("downstream-only response")
	if _, err := client.Write(payload); err != nil {
		t.Fatal(err)
	}
	c := acceptTestPeer(t, ln)
	buffer := make([]byte, 8192)
	if _, err := c.Read(buffer); err != nil {
		t.Fatal(err)
	}
	// The idle checker has observed no further client-to-target reads. A
	// successful target-to-client write must still count as session activity.
	c.SetIdle(true)
	if _, err := c.Write(payload); err != nil {
		t.Fatal(err)
	}
	if c.IsIdle() {
		t.Fatal("downstream traffic did not reset UDP idle state")
	}
	n, err := client.Read(buffer)
	if err != nil || !bytes.Equal(buffer[:n], payload) {
		t.Fatalf("downstream packet changed: %v", err)
	}
}

func TestZeroLengthQueuedDatagramPreservesNextPacket(t *testing.T) {
	budget := &queueBudget{limit: 8192}
	c := testConn(budget, 2)
	defer c.Close()
	for _, size := range []int{0, 32} {
		packet := bufpool.Get(size)
		if err := c.WriteQueue(packet); err != nil {
			bufpool.Put(packet)
			t.Fatal(err)
		}
	}
	var buffer [32]byte
	if n, err := c.Read(buffer[:]); n != 0 || err != nil {
		t.Fatalf("empty datagram changed: n=%d err=%v", n, err)
	}
	if n, err := c.Read(buffer[:]); n != 32 || err != nil {
		t.Fatalf("next datagram changed: n=%d err=%v", n, err)
	}
	if budget.bytes.Load() != 0 {
		t.Fatal("empty datagram retained queue memory")
	}
}

func TestCongestedQueueCompactsPacketsAndCloseReleasesBudget(t *testing.T) {
	ln, client := udpTestListener(t, 8192)
	payload := bytes.Repeat([]byte{7}, 1450)
	if _, err := client.Write(payload); err != nil {
		t.Fatal(err)
	}
	c := acceptTestPeer(t, ln)
	for i := 1; i <= 64; i++ {
		if i > 1 {
			if _, err := client.Write(payload); err != nil {
				t.Fatal(err)
			}
		}
		deadline := time.Now().Add(3 * time.Second)
		for len(c.rc) != i {
			if time.Now().After(deadline) {
				t.Fatalf("queue stalled at %d of %d packets", len(c.rc), i)
			}
			time.Sleep(time.Millisecond)
		}
	}
	var total int
	for i := 0; i < 64; i++ {
		packet := <-c.rc
		total += cap(packet)
		if !bytes.Equal(packet, payload) {
			t.Fatal("compaction changed packet contents")
		}
		c.budget.release(cap(packet))
		bufpool.Put(packet)
	}
	if total != 8*8192+56*2048 {
		t.Fatalf("unexpected backing-array memory: %d", total)
	}
	ln.Close()
}

// This is the same end-to-end benchmark on the original and repaired sources.
// The listener and datagram sizes match native UDP/HY2 forwarding. Pipelining
// tests queueing as well as a consumer that keeps up with incoming traffic.
func BenchmarkUDPListenerThroughput(b *testing.B) {
	for _, size := range []int{64, 1450, 8192} {
		for _, window := range []int{1, 32} {
			b.Run(fmtName(size, window), func(b *testing.B) {
				ln, client := udpTestListener(b, 8192)
				payload := bytes.Repeat([]byte{11}, size)
				if _, err := client.Write(payload); err != nil {
					b.Fatal(err)
				}
				peer := acceptTestPeer(b, ln)
				done := make(chan error, 1)
				go func() {
					buffer := make([]byte, 8192)
					for {
						n, err := peer.Read(buffer)
						if err != nil {
							done <- err
							return
						}
						if _, err := peer.Write(buffer[:n]); err != nil {
							done <- err
							return
						}
					}
				}()
				buffer := make([]byte, 8192)
				if _, err := client.Read(buffer); err != nil {
					b.Fatal(err)
				}
				_ = client.SetDeadline(time.Now().Add(2 * time.Minute))
				b.SetBytes(int64(size * window))
				b.ReportAllocs()
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					for j := 0; j < window; j++ {
						if _, err := client.Write(payload); err != nil {
							b.Fatal(err)
						}
					}
					for j := 0; j < window; j++ {
						n, err := client.Read(buffer)
						if err != nil || n != size {
							b.Fatalf("packet lost/truncated: n=%d err=%v", n, err)
						}
					}
				}
				b.StopTimer()
				ln.Close()
				if err := <-done; !errors.Is(err, net.ErrClosed) && !errors.Is(err, io.EOF) {
					b.Fatal(err)
				}
			})
		}
	}
}

func fmtName(size, window int) string {
	return strconv.Itoa(size) + "B/window" + strconv.Itoa(window)
}
