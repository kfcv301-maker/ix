package local

import (
	"context"
	"fmt"
	"net"
	"time"

	proxyproto "github.com/pires/go-proxyproto"
)

// writeProxyProtocol writes to the fully established destination stream, not to
// a tunnel's outer socket. A relay chain therefore carries this header as its
// first application bytes, preserving the addresses seen at the public ingress.
// No client-supplied PROXY header is consumed or trusted.
func (h *forwardHandler) writeProxyProtocol(ctx context.Context, network string, client, target net.Conn) error {
	if h.md.proxyProtocol == 0 || (network != "tcp" && network != "tcp4" && network != "tcp6") {
		return nil
	}
	src, srcOK := client.RemoteAddr().(*net.TCPAddr)
	dst, dstOK := client.LocalAddr().(*net.TCPAddr)
	if !srcOK || !dstOK || src.IP.To16() == nil || dst.IP.To16() == nil ||
		(src.IP.To4() == nil) != (dst.IP.To4() == nil) {
		return fmt.Errorf("send PROXY protocol header: ingress TCP addresses are unavailable or incompatible")
	}
	header := proxyproto.HeaderProxyFromAddrs(byte(h.md.proxyProtocol), src, dst)
	deadline := time.Now().Add(h.md.readTimeout)
	if contextDeadline, ok := ctx.Deadline(); ok && contextDeadline.Before(deadline) {
		deadline = contextDeadline
	}
	if err := target.SetWriteDeadline(deadline); err != nil {
		return fmt.Errorf("send PROXY protocol header: set deadline: %w", err)
	}
	if _, err := header.WriteTo(target); err != nil {
		return fmt.Errorf("send PROXY protocol header: %w", err)
	}
	if err := target.SetWriteDeadline(time.Time{}); err != nil {
		return fmt.Errorf("send PROXY protocol header: clear deadline: %w", err)
	}
	return nil
}
