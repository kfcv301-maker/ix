package cache

import (
	"fmt"
	"testing"
	"time"
)

func TestExpiredItemsAreRemoved(t *testing.T) {
	c := NewCache(10 * time.Millisecond)
	for i := 0; i < 1000; i++ {
		c.Set(fmt.Sprint(i), NewItem(i, 20*time.Millisecond))
	}
	c.Set("permanent", NewItem("keep", 0))

	deadline := time.Now().Add(time.Second)
	for c.Len() != 1 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := c.Len(); got != 1 {
		t.Fatalf("expected only the permanent item, got %d items", got)
	}
	if got := c.Get("permanent"); got == nil || got.Value() != "keep" {
		t.Fatalf("permanent item was removed: %v", got)
	}
}

func TestCleanupRestartsAfterIdle(t *testing.T) {
	c := NewCache(5 * time.Millisecond)
	c.Set("first", NewItem(1, 5*time.Millisecond))
	deadline := time.Now().Add(time.Second)
	for c.Len() != 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if got := c.Len(); got != 0 {
		t.Fatalf("first item was not removed: %d", got)
	}

	c.Set("second", NewItem(2, 5*time.Millisecond))
	deadline = time.Now().Add(time.Second)
	for c.Len() != 0 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if got := c.Len(); got != 0 {
		t.Fatalf("second item was not removed: %d", got)
	}
}
