package udp

import (
	"os"
	"strconv"
	"sync/atomic"
)

// This budget covers queued packet backing arrays across all UDP listeners in
// the Agent, not kernel socket buffers or the rest of the Go heap. Keep normal
// forwarding nonblocking; only discard packets when a queue is already full
// or the shared memory budget is exhausted.
const defaultQueueByteLimit = 64 << 20

type queueBudget struct {
	limit   int64
	bytes   atomic.Int64
	dropped atomic.Uint64
}

var sharedQueueBudget = &queueBudget{limit: queueByteLimit()}

func queueByteLimit() int64 {
	if limit, err := strconv.ParseInt(os.Getenv("LUNARIS_UDP_QUEUE_BYTES"), 10, 64); err == nil && limit > 0 {
		return limit
	}
	return defaultQueueByteLimit
}

func (b *queueBudget) acquire(size int) bool {
	for {
		used := b.bytes.Load()
		if int64(size) > b.limit-used {
			return false
		}
		if b.bytes.CompareAndSwap(used, used+int64(size)) {
			return true
		}
	}
}

func (b *queueBudget) release(size int) {
	b.bytes.Add(-int64(size))
}

// QueueStats is cheap enough to collect with the Agent's existing detailed
// metrics. Dropped is cumulative for this process lifetime.
func QueueStats() (queuedBytes, byteLimit, dropped uint64) {
	return uint64(sharedQueueBudget.bytes.Load()), uint64(sharedQueueBudget.limit), sharedQueueBudget.dropped.Load()
}
