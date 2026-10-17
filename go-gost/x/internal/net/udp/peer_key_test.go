package udp

import (
	"net"
	"sync"
	"testing"
)

func TestUDPAssociationReusesMappedIPv4Endpoint(t *testing.T) {
	ln, _ := udpTestListener(t, 8192)
	ip := net.ParseIP("127.0.0.1")
	first := ln.getConn(&net.UDPAddr{IP: ip.To4(), Port: 4242})
	mapped := ln.getConn(&net.UDPAddr{IP: ip.To16(), Port: 4242})
	if first == nil || mapped != first {
		t.Fatal("mapped IPv4 created a second association for the same endpoint")
	}
	if other := ln.getConn(&net.UDPAddr{IP: ip.To4(), Port: 4243}); other == nil || other == first {
		t.Fatal("different UDP ports shared an association")
	}
}

func TestUDPListenerCloseDrainsAcceptBacklog(t *testing.T) {
	ln, _ := udpTestListener(t, 8192)
	for i := 0; i < 32; i++ {
		if ln.getConn(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 4242 + i}) == nil {
			t.Fatal("listener rejected peer before close")
		}
	}
	ln.Close()
	if len(ln.cqueue) != 0 {
		t.Fatal("closed listener retained unaccepted peers")
	}
}

func TestUDPListenerCloseRacesLateAcceptEnqueue(t *testing.T) {
	for iteration := 0; iteration < 30; iteration++ {
		ln, _ := udpTestListener(t, 8192)
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			for i := 0; i < 64; i++ {
				ln.getConn(&net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 4242 + i})
			}
		}()
		go func() { defer wg.Done(); ln.Close() }()
		wg.Wait()
		if len(ln.cqueue) != 0 {
			t.Fatal("late enqueue retained a peer after listener close")
		}
	}
}

func TestUDPAssociationKeepsIPv6ZonesDistinct(t *testing.T) {
	ln, _ := udpTestListener(t, 8192)
	ip := net.ParseIP("fe80::1")
	first := ln.getConn(&net.UDPAddr{IP: ip, Port: 4242, Zone: "first"})
	if same := ln.getConn(&net.UDPAddr{IP: ip, Port: 4242, Zone: "first"}); first == nil || same != first {
		t.Fatal("IPv6 endpoint did not reuse its association")
	}
	if other := ln.getConn(&net.UDPAddr{IP: ip, Port: 4242, Zone: "second"}); other == nil || other == first {
		t.Fatal("IPv6 endpoints on different interfaces shared an association")
	}
}
