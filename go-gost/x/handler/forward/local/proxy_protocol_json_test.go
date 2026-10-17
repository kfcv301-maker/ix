package local_test

import (
	"fmt"
	"testing"
)

func TestForwardProxyProtocolJSONConfiguration(t *testing.T) {
	proxyIntegrationSetup(t)
	for _, transport := range []string{"direct", "tcp", "tls", "mtls", "ws"} {
		for _, version := range []int{1, 2} {
			t.Run(fmt.Sprintf("%s/v%d", transport, version), func(t *testing.T) {
				// startProxyService serializes and decodes the service exactly as
				// config files and control commands do, producing float64 metadata.
				checkProxyTCP(t, transport, version, false, "127.0.0.1:0")
			})
		}
	}
}
