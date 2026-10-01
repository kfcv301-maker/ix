package net_test

import (
	"io"
	"net"
	"runtime"
	"testing"
	"time"

	"github.com/go-gost/core/common/bufpool"
	xnet "github.com/go-gost/x/internal/net"
)

// Keep the old copy path here as a benchmark baseline for the discovered
// extra allocation when a TCP socket is paired with a decorated connection.
func referenceCopy(dst io.Writer, src io.Reader) error {
	buf := bufpool.Get(16 << 10)
	defer bufpool.Put(buf)
	_, err := io.CopyBuffer(dst, src, buf)
	return err
}

func fixedCopy(dst io.Writer, src io.Reader) error {
	return xnet.CopyBuffer(dst, src, 16<<10)
}

type plainConn struct{ net.Conn }

func BenchmarkIdleForwardMemory(b *testing.B) {
	for _, variant := range []struct {
		name string
		copy func(io.Writer, io.Reader) error
	}{{"reference", referenceCopy}, {"fixed", fixedCopy}} {
		b.Run(variant.name, func(b *testing.B) {
			const sessions = 64
			for iteration := 0; iteration < b.N; iteration++ {
				runtime.GC()
				runtime.GC()
				var before, after runtime.MemStats
				runtime.ReadMemStats(&before)
				clients := make([]*net.TCPConn, 0, sessions)
				remotes := make([]*net.TCPConn, 0, sessions)
				complete := make(chan struct{}, sessions)
				for i := 0; i < sessions; i++ {
					client, incoming := tcpPair(b)
					outgoing, remote := tcpPair(b)
					clients = append(clients, client)
					remotes = append(remotes, remote)
					wrapped := plainConn{incoming}
					go func() {
						done := make(chan error, 2)
						go func() { done <- variant.copy(outgoing, wrapped) }()
						go func() { done <- variant.copy(wrapped, outgoing) }()
						<-done
						incoming.Close()
						outgoing.Close()
						<-done
						complete <- struct{}{}
					}()
					client.Write([]byte{1})
					remote.Write([]byte{2})
					var data [1]byte
					if _, err := io.ReadFull(remote, data[:]); err != nil {
						b.Fatal(err)
					}
					if _, err := io.ReadFull(client, data[:]); err != nil {
						b.Fatal(err)
					}
				}
				runtime.GC()
				runtime.GC()
				runtime.ReadMemStats(&after)
				b.ReportMetric(float64(after.HeapAlloc-before.HeapAlloc)/sessions, "B/session")
				for i := range clients {
					clients[i].Close()
					remotes[i].Close()
				}
				for i := 0; i < sessions; i++ {
					select {
					case <-complete:
					case <-time.After(2 * time.Second):
						b.Fatal("copy did not exit")
					}
				}
			}
		})
	}
}

func BenchmarkDecoratedTCPThroughput(b *testing.B) {
	for _, variant := range []struct {
		name string
		copy func(io.Writer, io.Reader) error
	}{{"reference", referenceCopy}, {"fixed", fixedCopy}} {
		b.Run(variant.name, func(b *testing.B) {
			const transferSize = 32 << 20
			payload := make([]byte, 64<<10)
			b.SetBytes(transferSize)
			b.ResetTimer()
			for iteration := 0; iteration < b.N; iteration++ {
				sender, source := tcpPair(b)
				destination, receiver := tcpPair(b)
				sent := make(chan error, 1)
				go func() {
					for written := 0; written < transferSize; written += len(payload) {
						if _, err := sender.Write(payload); err != nil {
							sent <- err
							return
						}
					}
					sender.CloseWrite()
					sent <- nil
				}()
				copied := make(chan error, 1)
				go func() { err := variant.copy(destination, plainConn{source}); destination.CloseWrite(); copied <- err }()
				count, err := io.Copy(io.Discard, receiver)
				if err != nil || count != transferSize {
					b.Fatalf("received %d bytes: %v", count, err)
				}
				if err := <-sent; err != nil {
					b.Fatal(err)
				}
				if err := <-copied; err != nil {
					b.Fatal(err)
				}
				sender.Close()
				source.Close()
				destination.Close()
				receiver.Close()
			}
		})
	}
}
