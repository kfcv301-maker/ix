package cache

import (
	"sync"
	"time"
)

type Item struct {
	v          interface{}
	expiration int64
}

func NewItem(v interface{}, d time.Duration) *Item {
	var expiration int64
	if d > 0 {
		expiration = time.Now().Add(d).UnixNano()
	}

	return &Item{
		v:          v,
		expiration: expiration,
	}
}

func (p *Item) Expired() bool {
	if p == nil {
		return true
	}
	return p.expiration > 0 && time.Now().UnixNano() > p.expiration
}

func (p *Item) Value() interface{} {
	if p == nil {
		return nil
	}
	return p.v
}

type Cache struct {
	items           map[string]*Item
	cleanupInterval time.Duration
	timer           *time.Timer
	mu              sync.RWMutex
}

func NewCache(cleanupInterval time.Duration) *Cache {
	if cleanupInterval <= 0 {
		cleanupInterval = time.Minute
	}
	return &Cache{
		cleanupInterval: cleanupInterval,
		items:           make(map[string]*Item),
	}
}

func (c *Cache) Set(key string, item *Item) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if item == nil {
		delete(c.items, key)
		return
	}
	c.items[key] = item
	if item.expiration > 0 && c.timer == nil {
		c.timer = time.AfterFunc(c.cleanupInterval, c.cleanupExpired)
	}
}

func (c *Cache) Get(key string) *Item {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return c.items[key]
}

func (c *Cache) Len() int {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return len(c.items)
}

func (c *Cache) cleanupExpired() {
	c.mu.Lock()
	defer c.mu.Unlock()

	now := time.Now().UnixNano()
	hasExpiringItems := false
	for key, item := range c.items {
		if item == nil || (item.expiration > 0 && now >= item.expiration) {
			delete(c.items, key)
			continue
		}
		if item.expiration > 0 {
			hasExpiringItems = true
		}
	}

	if hasExpiringItems {
		c.timer = time.AfterFunc(c.cleanupInterval, c.cleanupExpired)
	} else {
		c.timer = nil
	}
}
