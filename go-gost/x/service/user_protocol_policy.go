package service

import (
	"errors"
	"fmt"
	"io"
	"net"
	"time"

	"github.com/go-gost/x/config"
)

const UserPolicyMetadataKey = config.UserPolicyMetadataKey
const (
	TLSUnrestricted        = config.TLSUnrestricted
	TLSOnly                = config.TLSOnly
	TLSDeny                = config.TLSDeny
	blockHTTP       uint32 = 1 << iota
	blockTLS
	blockSOCKS
)

// A non-nil policy belongs to one panel-managed ingress service and replaces
// legacy node-wide protocol switches. Administrators get an unrestricted policy.
type UserProtocolPolicy = config.UserProtocolPolicy

func ParseUserProtocolPolicy(md map[string]any) (*UserProtocolPolicy, error) {
	return config.ParseUserProtocolPolicy(md)
}

func UserProtocolPolicyOption(p *UserProtocolPolicy) Option {
	return func(opts *options) { opts.userPolicy = p }
}

// Hot changes only touch this service. Repeated reconciliation is idempotent.
// Close its old sessions when tightening the policy so existing flows cannot
// indefinitely evade a newly applied restriction. Other users stay connected.
func (s *defaultService) SetUserProtocolPolicy(p UserProtocolPolicy) error {
	if err := p.Validate(); err != nil {
		return err
	}
	s.connMu.Lock()
	old := s.userPolicy.Load()
	if old != nil && !p.Administrator && p.Revision < old.Revision {
		s.connMu.Unlock()
		return fmt.Errorf("stale user protocol policy revision")
	}
	s.userPolicy.Store(&p)
	tlsTightened := (old == nil || old.TLS != p.TLS) && p.TLS != TLSUnrestricted
	udpTightened := (old == nil || old.AllowUDP) && !p.AllowUDP
	var active []net.Conn
	if tlsTightened || udpTightened {
		for c := range s.conns {
			if isUDPConn(c) && udpTightened || !isUDPConn(c) && tlsTightened {
				active = append(active, c)
			}
		}
	}
	s.connMu.Unlock()
	for _, c := range active {
		c.Close()
	}
	return nil
}

func isUDPConn(c net.Conn) bool {
	return c.RemoteAddr() != nil && (c.RemoteAddr().Network() == "udp" ||
		c.RemoteAddr().Network() == "udp4" || c.RemoteAddr().Network() == "udp6")
}

func (s *defaultService) policyConnection(c net.Conn) (net.Conn, bool) {
	p := s.userPolicy.Load()
	if isUDPConn(c) {
		if p != nil && !p.AllowUDP {
			c.Close()
			return c, false
		}
		return c, true
	}
	if p != nil {
		if p.TLS == TLSUnrestricted {
			return c, true
		}
		return &protocolConn{Conn: c, tlsPolicy: p.TLS, loadPolicy: s.userPolicy.Load}, true
	}
	if flags := protocolBlocks.Load(); flags != 0 {
		return &protocolConn{Conn: c, flags: flags, loadPolicy: s.userPolicy.Load}, true
	}
	return c, true
}

// Inspect a small prefix on the first read, retaining every byte for the
// handler. io.ReadFull handles TCP fragmentation independently of copy buffers.
// No TLS termination, certificate checks, or buffering of application payloads.
type protocolConn struct {
	net.Conn
	tlsPolicy  string
	flags      uint32
	detected   bool
	prefix     []byte
	err        error
	loadPolicy func() *UserProtocolPolicy
}

func (c *protocolConn) refreshPolicy() {
	if c.loadPolicy == nil {
		return
	}
	if p := c.loadPolicy(); p != nil {
		c.tlsPolicy = p.TLS
		c.flags = 0
	} else {
		c.tlsPolicy = ""
		c.flags = protocolBlocks.Load()
	}
}

func (c *protocolConn) Read(b []byte) (int, error) {
	if len(b) == 0 {
		return 0, nil
	}
	if !c.detected {
		c.detected = true
		c.refreshPolicy()
		if c.tlsPolicy != TLSOnly && c.tlsPolicy != TLSDeny && c.flags == 0 {
			return c.Conn.Read(b)
		}
		if c.tlsPolicy == TLSOnly {
			c.Conn.SetReadDeadline(time.Now().Add(10 * time.Second))
			defer c.Conn.SetReadDeadline(time.Time{})
		}
		var header [257]byte // includes the maximum SOCKS5 method list
		n, err := io.ReadFull(c.Conn, header[:1])
		if n == 1 {
			want := 1
			switch {
			case header[0] == 0x16:
				want = 6 // record header plus ClientHello type
			case c.flags&blockSOCKS != 0 && header[0] == 4:
				want = 7
			case c.flags&blockSOCKS != 0 && header[0] == 5:
				want = 2
			case c.flags&blockHTTP != 0 && (header[0] == 'G' || header[0] == 'P' || header[0] == 'D' || header[0] == 'H' || header[0] == 'O' || header[0] == 'C'):
				want = 7
			}
			if want > 1 {
				more, e := io.ReadFull(c.Conn, header[1:want])
				n += more
				err = e
			}
			if c.flags&blockSOCKS != 0 && header[0] == 5 && n == 2 {
				more, e := io.ReadFull(c.Conn, header[2:2+int(header[1])])
				n += more
				err = e
			}
		}
		isTLS := n >= 6 && detectTLS(header[:n]) && (header[3] != 0 || header[4] != 0) && header[5] == 1
		// A relaxation can arrive while an idle connection waits for its first
		// bytes. Evaluate the current policy before rejecting the retained data.
		c.refreshPolicy()
		blocked := c.tlsPolicy == TLSOnly && !isTLS || c.tlsPolicy == TLSDeny && isTLS ||
			c.flags&blockTLS != 0 && isTLS || c.flags&blockHTTP != 0 && detectHTTP(header[:n]) ||
			c.flags&blockSOCKS != 0 && detectSOCKS(header[:n])
		if blocked {
			c.Conn.Close()
			c.err = errors.New("connection blocked by protocol policy")
		} else {
			c.prefix = append([]byte(nil), header[:n]...)
			c.err = err
		}
	}
	if len(c.prefix) > 0 {
		n := copy(b, c.prefix)
		c.prefix = c.prefix[n:]
		if len(c.prefix) == 0 {
			c.prefix = nil
		}
		return n, nil
	}
	if c.err != nil {
		return 0, c.err
	}
	return c.Conn.Read(b)
}
