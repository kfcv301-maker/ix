package mux

import (
	"io"
	"net"
	"testing"
)

// Compare the unchanged logical stream I/O path with and without physical
// session tracking. Session creation and teardown are outside the timed region.
func BenchmarkMuxTransfer(b *testing.B) {
	for _, tracked := range []bool{true, false} {
		name := "default_untracked"
		if tracked {
			name = "opt_in_tracked"
		}
		b.Run(name, func(b *testing.B) {
			a, z := net.Pipe()
			cfg := &Config{Version: 2, KeepAliveDisabled: true}
			previous := metricsEnabled
			metricsEnabled = tracked
			defer func() { metricsEnabled = previous }()
			client, err := ClientSession(a, cfg)
			var server *Session
			if err == nil {
				server, err = ServerSession(z, cfg)
			}
			if err != nil {
				a.Close()
				z.Close()
				if client != nil {
					client.Close()
				}
				b.Fatal(err)
			}
			defer client.Close()
			defer server.Close()
			stream, err := client.GetConn()
			if err != nil {
				b.Fatal(err)
			}
			defer stream.Close()
			peer, err := server.Accept()
			if err != nil {
				b.Fatal(err)
			}
			defer peer.Close()
			drained := make(chan error, 1)
			go func() {
				_, err := io.Copy(io.Discard, peer)
				drained <- err
			}()
			payload := make([]byte, 64*1024)
			b.SetBytes(int64(len(payload)))
			b.ReportAllocs()
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				if _, err := stream.Write(payload); err != nil {
					b.Fatal(err)
				}
			}
			b.StopTimer()
			stream.Close()
			if err := <-drained; err != nil {
				b.Fatal(err)
			}
		})
	}
}
