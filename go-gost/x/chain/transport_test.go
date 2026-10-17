package chain

import (
	"context"
	"fmt"
	"sync"
	"testing"

	corechain "github.com/go-gost/core/chain"
	"github.com/go-gost/core/dialer"
	"github.com/go-gost/core/hop"
)

func TestTransportCopyPreservesIndependentRoutingOptions(t *testing.T) {
	original := NewTransport(nil, nil, corechain.AddrTransportOption("mux.example:443"))
	first, second := original.Copy(), original.Copy()
	firstRoute, secondRoute := NewRoute(), NewRoute()
	first.Options().Route = firstRoute
	second.Options().Route = secondRoute
	first.Options().Addr = "another.example:443"
	if original.Options().Route != nil || original.Options().Addr != "mux.example:443" {
		t.Fatal("per-connection route changed the shared transport")
	}
	if first.Options().Route != firstRoute || second.Options().Route != secondRoute || second.Options().Addr != "mux.example:443" {
		t.Fatal("route or address leaked between independent copies")
	}
}

type multiplexTestDialer struct{ dialer.Dialer }

func (multiplexTestDialer) Multiplex() bool { return true }

type routeTestContextKey struct{}
type routeTestHop struct {
	hop.Hop
	selectNode func(context.Context) *corechain.Node
}

func (h routeTestHop) Select(ctx context.Context, _ ...hop.SelectOption) *corechain.Node {
	return h.selectNode(ctx)
}

func TestConcurrentMultiplexRoutesRetainTheirSelectedPrefix(t *testing.T) {
	const count = 64
	prefixes := make([]*corechain.Node, count)
	for i := range prefixes {
		prefixes[i] = corechain.NewNode(fmt.Sprint(i), fmt.Sprintf("prefix-%d:443", i), corechain.TransportNodeOption(NewTransport(nil, nil)))
	}
	sharedTransport := NewTransport(multiplexTestDialer{}, nil)
	multiplexNode := corechain.NewNode("mux", "mux.example:443", corechain.TransportNodeOption(sharedTransport))
	c := NewChain("concurrent-mux")
	c.AddHop(routeTestHop{selectNode: func(ctx context.Context) *corechain.Node {
		return prefixes[ctx.Value(routeTestContextKey{}).(int)]
	}})
	c.AddHop(routeTestHop{selectNode: func(context.Context) *corechain.Node { return multiplexNode }})
	routes := make([]corechain.Route, count)
	var ready sync.WaitGroup
	ready.Add(count)
	for i := range routes {
		go func(i int) {
			defer ready.Done()
			ctx := context.WithValue(context.Background(), routeTestContextKey{}, i)
			routes[i] = c.Route(ctx, "tcp", "target.example:443")
		}(i)
	}
	ready.Wait()
	if sharedTransport.Options().Route != nil {
		t.Fatal("concurrent route selection mutated the shared multiplex transport")
	}
	for i, route := range routes {
		nodes := route.Nodes()
		if len(nodes) != 1 || nodes[0] == multiplexNode {
			t.Fatalf("route %d did not receive its own multiplex node", i)
		}
		prefix := nodes[0].Options().Transport.Options().Route.Nodes()
		if len(prefix) != 1 || prefix[0] != prefixes[i] {
			t.Fatalf("route %d lost its selected prefix", i)
		}
	}
}
