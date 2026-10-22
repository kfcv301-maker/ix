package socket

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/registry"
	"github.com/go-gost/x/service"
)

type userProtocolPolicyRequest struct {
	Services []string                   `json:"services"`
	Policy   service.UserProtocolPolicy `json:"policy"`
}

func (w *WebSocketReporter) handleSetUserProtocolPolicy(data any) error {
	b, err := json.Marshal(data)
	if err != nil {
		return err
	}
	var req userProtocolPolicyRequest
	if err := json.Unmarshal(b, &req); err != nil {
		return err
	}
	if err := req.Policy.Validate(); err != nil {
		return err
	}
	if len(req.Services) == 0 {
		return fmt.Errorf("services list cannot be empty")
	}
	wanted := make(map[string]bool)
	for _, name := range req.Services {
		if !strings.HasSuffix(name, "_tcp") && !strings.HasSuffix(name, "_udp") {
			return fmt.Errorf("user policy requires an ingress service: %s", name)
		}
		wanted[name] = true
	}
	// Use the same lock order as service creation/update/deletion, so a hot
	// policy change cannot race a staged replacement and restore stale policy.
	serviceMutationMu.Lock()
	defer serviceMutationMu.Unlock()
	return config.OnUpdate(func(c *config.Config) error {
		found := make(map[string]*config.ServiceConfig)
		for name := range wanted {
			parts := strings.Split(name, "_")
			prefix := ""
			if len(parts) == 4 {
				if _, e := strconv.ParseUint(parts[0], 10, 64); e == nil {
					if _, e := strconv.ParseUint(parts[1], 10, 64); e == nil {
						prefix = parts[0] + "_" + parts[1] + "_"
					}
				}
			}
			matched := false
			for _, cfg := range c.Services {
				if cfg == nil {
					continue
				}
				// Historic grant IDs are aliases of the same forward and owner.
				// Apply to every alias so it cannot bypass a changed restriction.
				alias := prefix != "" && strings.HasPrefix(cfg.Name, prefix) &&
					len(strings.Split(cfg.Name, "_")) == 4 && strings.HasSuffix(cfg.Name, "_"+parts[3])
				if cfg.Name == name || alias {
					found[cfg.Name] = cfg
					matched = true
				}
			}
			if !matched {
				return fmt.Errorf("service %s not found", name)
			}
		}
		// Validate the complete batch before modifying anything, including
		// paused configurations which will load this policy when resumed.
		for name, cfg := range found {
			old, err := service.ParseUserProtocolPolicy(cfg.Metadata)
			if err != nil {
				return err
			}
			if old != nil && !req.Policy.Administrator && req.Policy.Revision < old.Revision {
				return fmt.Errorf("stale user protocol policy revision for %s", name)
			}
			if cfg.Listener == nil || (cfg.Listener.Type != "tcp" && cfg.Listener.Type != "udp") {
				return fmt.Errorf("service %s is not a raw ingress", name)
			}
			if svc := registry.ServiceRegistry().Get(name); svc != nil {
				if _, ok := svc.(interface {
					SetUserProtocolPolicy(service.UserProtocolPolicy) error
				}); !ok {
					return fmt.Errorf("service %s does not support user policies", name)
				}
			}
		}
		c.Services = append([]*config.ServiceConfig(nil), c.Services...)
		for name, cfg := range found {
			// Copy instead of mutating a shared metadata map in config snapshots.
			clone := *cfg
			clone.Metadata = config.WithUserProtocolPolicy(cfg.Metadata, req.Policy)
			for i, original := range c.Services {
				if original == cfg {
					c.Services[i] = &clone
					break
				}
			}
			if svc := registry.ServiceRegistry().Get(name); svc != nil {
				if err := svc.(interface {
					SetUserProtocolPolicy(service.UserProtocolPolicy) error
				}).SetUserProtocolPolicy(req.Policy); err != nil {
					return err
				}
			}
		}
		return nil
	})
}
