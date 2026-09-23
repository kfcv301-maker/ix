package limiter

import (
	"context"
	"fmt"
	"testing"
	"time"

	coreLimiter "github.com/go-gost/core/limiter"
	"github.com/go-gost/core/limiter/traffic"
	"github.com/go-gost/x/internal/util/cache"
)

type testTrafficLimiter struct {
	result traffic.Limiter
}

func (l *testTrafficLimiter) In(context.Context, string, ...coreLimiter.Option) traffic.Limiter {
	return l.result
}

func (l *testTrafficLimiter) Out(context.Context, string, ...coreLimiter.Option) traffic.Limiter {
	return l.result
}

type testLimit int

func (l testLimit) Wait(_ context.Context, n int) int { return n }
func (l testLimit) Limit() int                        { return int(l) }
func (l testLimit) Set(int)                           {}

func TestMissingLimitsAreNotCached(t *testing.T) {
	lim := NewCachedTrafficLimiter(&testTrafficLimiter{}).(*cachedTrafficLimiter)
	for i := 0; i < 1000; i++ {
		key := fmt.Sprintf("192.0.2.1:%d", i)
		if got := lim.In(context.Background(), key); got != nil {
			t.Fatalf("unexpected inbound limit for %s", key)
		}
		if got := lim.Out(context.Background(), key); got != nil {
			t.Fatalf("unexpected outbound limit for %s", key)
		}
	}
	if got := lim.inLimits.Len(); got != 0 {
		t.Fatalf("cached %d missing inbound limits", got)
	}
	if got := lim.outLimits.Len(); got != 0 {
		t.Fatalf("cached %d missing outbound limits", got)
	}
}

func TestExistingLimitSurvivesTemporaryMissingRefresh(t *testing.T) {
	source := &testTrafficLimiter{result: testLimit(100)}
	lim := &cachedTrafficLimiter{
		inLimits:  cache.NewCache(time.Second),
		outLimits: cache.NewCache(time.Second),
		limiter:   source,
		options:   options{refreshInterval: 5 * time.Millisecond},
	}
	if got := lim.In(context.Background(), "client"); got == nil || got.Limit() != 100 {
		t.Fatalf("initial limit = %v", got)
	}
	source.result = nil
	time.Sleep(10 * time.Millisecond)
	if got := lim.In(context.Background(), "client"); got == nil || got.Limit() != 100 {
		t.Fatalf("limit was lost after refresh: %v", got)
	}
}
