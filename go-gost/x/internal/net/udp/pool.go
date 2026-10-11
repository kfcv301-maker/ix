package udp

import (
	"sync"
	"time"

	"github.com/go-gost/core/logger"
)

type connPool struct {
	m         sync.Map
	ttl       time.Duration
	closed    chan struct{}
	logger    logger.Logger
	mu        sync.RWMutex
	closeOnce sync.Once
}

func newConnPool(ttl time.Duration, loggers ...logger.Logger) *connPool {
	p := &connPool{
		ttl:    ttl,
		closed: make(chan struct{}),
	}
	if len(loggers) > 0 {
		p.logger = loggers[0]
	}
	go p.idleCheck()
	return p
}

func (p *connPool) Get(key any) (c *conn, ok bool) {
	if p == nil {
		return
	}

	v, ok := p.m.Load(key)
	if ok {
		c, ok = v.(*conn)
	}
	return
}

func (p *connPool) Set(key any, c *conn) bool {
	if p == nil {
		c.Close()
		return false
	}
	p.mu.RLock()
	defer p.mu.RUnlock()
	select {
	case <-p.closed:
		c.Close()
		return false
	default:
	}
	p.m.Store(key, c)
	return true
}

func (p *connPool) Delete(key any) {
	if p == nil {
		return
	}
	p.m.Delete(key)
}

func (p *connPool) Close() {
	if p == nil {
		return
	}

	p.closeOnce.Do(func() {
		p.mu.Lock()
		defer p.mu.Unlock()
		close(p.closed)
		p.m.Range(func(k, v any) bool {
			p.m.Delete(k)
			if c, ok := v.(*conn); ok && c != nil {
				c.Close()
			}
			return true
		})
	})
}

func (p *connPool) idleCheck() {
	ticker := time.NewTicker(p.ttl)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			size := 0
			idles := 0
			p.m.Range(func(key, value any) bool {
				c, ok := value.(*conn)
				if !ok || c == nil {
					p.Delete(key)
					return true
				}
				size++

				if c.IsIdle() {
					idles++
					if p.m.CompareAndDelete(key, c) {
						c.Close()
					}
					return true
				}

				c.SetIdle(true)

				return true
			})

			if idles > 0 && p.logger != nil {
				p.logger.Debugf("connection pool: size=%d, idle=%d", size, idles)
			}
		case <-p.closed:
			return
		}
	}
}
