// Package servicecontrol coordinates mutations from the panel WebSocket and
// optional REST API. Forwarding and traffic accounting never take this lock.
package servicecontrol

import (
	"fmt"
	"sync"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/service"
)

// MutationMu must be taken before config.OnUpdate and service lifecycle locks.
var MutationMu sync.Mutex

// PreservePolicy makes legacy updates retain an existing user restriction and
// rejects an explicitly older policy before the caller closes a listener.
func PreservePolicy(incoming, current *config.ServiceConfig) error {
	p, err := service.ParseUserProtocolPolicy(incoming.Metadata)
	if err != nil {
		return err
	}
	if current == nil {
		return nil
	}
	old, err := service.ParseUserProtocolPolicy(current.Metadata)
	if err != nil || old == nil {
		return err
	}
	if p == nil {
		md := make(map[string]any, len(incoming.Metadata)+1)
		for k, v := range incoming.Metadata {
			md[k] = v
		}
		md[service.UserPolicyMetadataKey] = *old
		incoming.Metadata = md
		return nil
	}
	if !p.Administrator && p.Revision < old.Revision {
		return fmt.Errorf("stale user protocol policy revision for %s", incoming.Name)
	}
	return nil
}
