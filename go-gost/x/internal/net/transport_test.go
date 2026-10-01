package net_test

import (
	"bytes"
	"errors"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	corestats "github.com/go-gost/core/observer/stats"
	xnet "github.com/go-gost/x/internal/net"
	xstats "github.com/go-gost/x/observer/stats"
	statswrapper "github.com/go-gost/x/observer/stats/wrapper"
)

type optionalReader struct {
	io.Reader
	maxRead     int
	usedWriteTo bool
}

func (r *optionalReader) Read(b []byte) (int, error) {
	if len(b) > r.maxRead {
		r.maxRead = len(b)
	}
	return r.Reader.Read(b)
}

func (r *optionalReader) WriteTo(io.Writer) (int64, error) {
	r.usedWriteTo = true
	return 0, errors.New("unexpected WriterTo bypass")
}

type optionalWriter struct {
	bytes.Buffer
	usedReadFrom bool
}

func (w *optionalWriter) ReadFrom(io.Reader) (int64, error) {
	w.usedReadFrom = true
	return 0, errors.New("unexpected ReaderFrom bypass")
}

func TestCopyBufferUsesSuppliedBufferWithOptionalInterfaces(t *testing.T) {
	payload := bytes.Repeat([]byte("stream-data"), 16000)
	r := &optionalReader{Reader: bytes.NewReader(payload)}
	w := &optionalWriter{}
	if err := xnet.CopyBuffer(w, r, 16<<10); err != nil {
		t.Fatal(err)
	}
	if r.usedWriteTo || w.usedReadFrom {
		t.Fatal("copy bypassed decorated Read or Write")
	}
	if r.maxRead != 16<<10 {
		t.Fatalf("read buffer = %d, want 16 KiB", r.maxRead)
	}
	if !bytes.Equal(w.Bytes(), payload) {
		t.Fatal("payload changed")
	}
}

type measuredConn struct {
	net.Conn
	readBytes, writeBytes int
	maxRead, maxWrite     int
}

func (c *measuredConn) Read(b []byte) (int, error) {
	if len(b) > c.maxRead {
		c.maxRead = len(b)
	}
	n, err := c.Conn.Read(b)
	c.readBytes += n
	return n, err
}

func (c *measuredConn) Write(b []byte) (int, error) {
	if len(b) > c.maxWrite {
		c.maxWrite = len(b)
	}
	n, err := c.Conn.Write(b)
	c.writeBytes += n
	return n, err
}

func tcpPair(tb testing.TB) (*net.TCPConn, *net.TCPConn) {
	tb.Helper()
	ln, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		tb.Fatal(err)
	}
	defer ln.Close()
	client, err := net.DialTCP("tcp", nil, ln.Addr().(*net.TCPAddr))
	if err != nil {
		tb.Fatal(err)
	}
	server, err := ln.AcceptTCP()
	if err != nil {
		client.Close()
		tb.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	client.SetDeadline(deadline)
	server.SetDeadline(deadline)
	tb.Cleanup(func() { client.Close(); server.Close() })
	return client, server
}

func TestCopyBufferTCPWithDecoratedPeer(t *testing.T) {
	for _, direction := range []string{"read_from_raw_tcp", "write_to_raw_tcp"} {
		t.Run(direction, func(t *testing.T) {
			sender, source := tcpPair(t)
			destination, receiver := tcpPair(t)
			payload := bytes.Repeat([]byte("accounted-forward-data"), 9000)
			sent := make(chan error, 1)
			go func() { _, err := sender.Write(payload); sender.CloseWrite(); sent <- err }()
			var src io.Reader = source
			var dst io.Writer = destination
			wrapped := &measuredConn{}
			counters := xstats.NewStats(false)
			var accounted net.Conn
			if direction == "read_from_raw_tcp" {
				wrapped.Conn = source
				accounted = statswrapper.WrapConn(wrapped, counters)
				src = accounted
			} else {
				wrapped.Conn = destination
				accounted = statswrapper.WrapConn(wrapped, counters)
				dst = accounted
			}
			copied := make(chan error, 1)
			go func() { err := xnet.CopyBuffer(dst, src, 16<<10); destination.CloseWrite(); copied <- err }()
			data, err := io.ReadAll(receiver)
			if err != nil {
				t.Fatal(err)
			}
			if err := <-sent; err != nil {
				t.Fatal(err)
			}
			if err := <-copied; err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(data, payload) {
				t.Fatal("forwarded bytes changed")
			}
			if direction == "read_from_raw_tcp" && (wrapped.readBytes != len(payload) || wrapped.maxRead > 16<<10) {
				t.Fatalf("read accounting/buffer: bytes=%d max=%d", wrapped.readBytes, wrapped.maxRead)
			}
			if direction == "write_to_raw_tcp" && (wrapped.writeBytes != len(payload) || wrapped.maxWrite > 16<<10) {
				t.Fatalf("write accounting/buffer: bytes=%d max=%d", wrapped.writeBytes, wrapped.maxWrite)
			}
			kind := corestats.KindInputBytes
			if direction == "write_to_raw_tcp" {
				kind = corestats.KindOutputBytes
			}
			if got := counters.Get(kind); got != uint64(len(payload)) {
				t.Fatalf("panel traffic counter = %d, want %d", got, len(payload))
			}
			accounted.Close()
			if got := counters.Get(corestats.KindCurrentConns); got != 0 {
				t.Fatalf("panel connection counter after close = %d", got)
			}
		})
	}
}

func TestTransportDuplexAndPeerClose(t *testing.T) {
	client, incoming := tcpPair(t)
	outgoing, remote := tcpPair(t)
	wrapped := &measuredConn{Conn: incoming}
	done := make(chan error, 1)
	go func() { err := xnet.Transport(wrapped, outgoing); incoming.Close(); outgoing.Close(); done <- err }()
	payloads := [][]byte{bytes.Repeat([]byte("request"), 9000), bytes.Repeat([]byte("reply"), 11000)}
	var workers sync.WaitGroup
	workers.Add(2)
	go func() {
		defer workers.Done()
		if _, err := client.Write(payloads[0]); err != nil {
			t.Error(err)
		}
	}()
	go func() {
		defer workers.Done()
		if _, err := remote.Write(payloads[1]); err != nil {
			t.Error(err)
		}
	}()
	got := make([]byte, len(payloads[0]))
	if _, err := io.ReadFull(remote, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payloads[0]) {
		t.Fatal("request changed")
	}
	got = make([]byte, len(payloads[1]))
	if _, err := io.ReadFull(client, got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payloads[1]) {
		t.Fatal("reply changed")
	}
	workers.Wait()
	client.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("transport did not release after peer close")
	}
}
