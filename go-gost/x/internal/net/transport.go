package net

import (
	"io"

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
	buf := bufpool.Get(bufSize)
	defer bufpool.Put(buf)

	_, err := io.CopyBuffer(dst, src, buf)
	return err
}
