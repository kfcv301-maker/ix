package net

import (
	"io"
	stdnet "net"

	"github.com/go-gost/core/common/bufpool"
)

const (
	// Each active TCP forward uses one buffer in each direction. A 16 KiB
	// buffer keeps idle high-connection workloads bounded without changing
	// the streaming semantics; larger buffers multiplied into hundreds of
	// megabytes on small Agent hosts.
	bufferSize = 16 * 1024
)

func Transport(rw1, rw2 io.ReadWriter) error {
	errc := make(chan error, 1)
	go func() {
		errc <- CopyBuffer(rw1, rw2, bufferSize)
	}()

	go func() {
		errc <- CopyBuffer(rw2, rw1, bufferSize)
	}()

	if err := <-errc; err != nil && err != io.EOF {
		return err
	}

	return nil
}

func CopyBuffer(dst io.Writer, src io.Reader, bufSize int) error {
	// Two unwrapped TCP sockets can use the kernel's zero-copy path without
	// a userspace buffer. Do not unwrap decorated connections: their Read
	// and Write methods may account traffic or enforce rate limits.
	if _, ok := src.(*stdnet.TCPConn); ok {
		if _, ok := dst.(*stdnet.TCPConn); ok {
			_, err := io.Copy(dst, src)
			return err
		}
	}

	buf := bufpool.Get(bufSize)
	defer bufpool.Put(buf)

	// CopyBuffer ignores buf when src has WriteTo or dst has ReadFrom.
	// A raw TCP socket opposite a decorated socket takes those methods'
	// fallback path, allocating another 32 KiB buffer for the entire copy.
	// Hide both optional interfaces so the pooled buffer is actually used.
	_, err := io.CopyBuffer(struct{ io.Writer }{dst}, struct{ io.Reader }{src}, buf)
	return err
}
