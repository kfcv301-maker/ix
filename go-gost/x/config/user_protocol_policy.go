package config

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"

	"gopkg.in/yaml.v3"
)

const UserPolicyMetadataKey = "userProtocolPolicy"
const (
	TLSUnrestricted = "UNRESTRICTED"
	TLSOnly         = "TLS_ONLY"
	TLSDeny         = "TLS_DENY"
)

// UserProtocolPolicy belongs to one panel-managed ingress service. Keep its
// decoding here so file loading and live service updates share one policy rule.
type UserProtocolPolicy struct {
	TLS           string `json:"tls"`
	AllowUDP      bool   `json:"allowUdp"`
	Revision      uint64 `json:"revision,omitempty"`
	Administrator bool   `json:"administrator,omitempty"`
}

func (p UserProtocolPolicy) Validate() error {
	if p.Administrator && (p.TLS != TLSUnrestricted || !p.AllowUDP) {
		return fmt.Errorf("administrator policy must be unrestricted")
	}
	switch p.TLS {
	case TLSUnrestricted, TLSOnly, TLSDeny:
		return nil
	default:
		return fmt.Errorf("invalid user TLS policy: %q", p.TLS)
	}
}

// ParseUserProtocolPolicy accepts Viper's lowercase metadata and historical
// files containing both lowercase and camel-case aliases. Never let map order
// select an older or ambiguous, less restrictive policy.
func ParseUserProtocolPolicy(md map[string]any) (*UserProtocolPolicy, error) {
	var selected *UserProtocolPolicy
	conflict := false
	for key, value := range md {
		if !strings.EqualFold(key, UserPolicyMetadataKey) {
			continue
		}
		p, err := decodeUserProtocolPolicy(value)
		if err != nil {
			return nil, err
		}
		if selected == nil || p.Revision > selected.Revision {
			selected = p
			conflict = false
		} else if p.Revision == selected.Revision && *p != *selected {
			conflict = true
		}
	}
	if conflict {
		return nil, fmt.Errorf("conflicting user protocol policies at revision %d", selected.Revision)
	}
	return selected, nil
}

func decodeUserProtocolPolicy(value any) (*UserProtocolPolicy, error) {
	b, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(b, &fields); err != nil {
		return nil, err
	}
	if fields == nil {
		return nil, fmt.Errorf("user protocol policy must be an object")
	}
	canonical := make(map[string]json.RawMessage, len(fields))
	for key, value := range fields {
		key = strings.ToLower(key)
		switch key {
		case "tls", "allowudp", "revision", "administrator":
			if bytes.Equal(value, []byte("null")) {
				return nil, fmt.Errorf("user protocol policy field %s cannot be null", key)
			}
			if old, exists := canonical[key]; exists && !bytes.Equal(old, value) {
				return nil, fmt.Errorf("conflicting user protocol policy field %s", key)
			}
			canonical[key] = value
		}
	}
	b, err = json.Marshal(canonical)
	if err != nil {
		return nil, err
	}
	// Older panel payloads omit UDP. Preserve their existing permissive default.
	p := UserProtocolPolicy{TLS: TLSUnrestricted, AllowUDP: true}
	if err := json.Unmarshal(b, &p); err != nil {
		return nil, err
	}
	return &p, p.Validate()
}

// WithUserProtocolPolicy copies metadata and removes every old spelling before
// writing one key. A later Viper reload cannot resurrect a stale alias.
func WithUserProtocolPolicy(md map[string]any, p UserProtocolPolicy) map[string]any {
	result := make(map[string]any, len(md)+1)
	for key, value := range md {
		if !strings.EqualFold(key, UserPolicyMetadataKey) {
			result[key] = value
		}
	}
	result[UserPolicyMetadataKey] = p
	return result
}

// Recover policies from the original JSON/YAML bytes instead of Viper's map,
// where differently cased aliases have already been merged. YAML decodes JSON
// too. Other input formats remain Viper's responsibility.
func savedUserProtocolPolicies(data []byte) ([]*UserProtocolPolicy, error) {
	var doc map[string]any
	if err := yaml.Unmarshal(data, &doc); err != nil {
		// Viper already accepted this input. A valid JSON document can still
		// have duplicate keys rejected by YAML; keep that rejection. Other
		// formats such as TOML/INI are not JSON/YAML and need no restoration.
		if json.Valid(data) {
			return nil, err
		}
		return nil, nil
	}
	value, err := policyConfigField(doc, "services")
	if err != nil || value == nil {
		return nil, err
	}
	services, ok := value.([]any)
	if !ok {
		return nil, nil
	}
	policies := make([]*UserProtocolPolicy, len(services))
	for i, svc := range services {
		fields := policyStringFields(svc)
		value, err := policyConfigField(fields, "metadata")
		if err != nil {
			return nil, err
		}
		policies[i], err = ParseUserProtocolPolicy(policyStringFields(value))
		if err != nil {
			return nil, fmt.Errorf("service %d: %w", i, err)
		}
	}
	return policies, nil
}

func policyStringFields(value any) map[string]any {
	switch fields := value.(type) {
	case map[string]any:
		return fields
	case map[any]any:
		// YAML permits non-string keys in unrelated metadata. Only strings
		// can name a policy; preserve them without rejecting other fields.
		result := make(map[string]any)
		for key, value := range fields {
			if key, ok := key.(string); ok {
				result[key] = value
			}
		}
		return result
	default:
		return nil
	}
}

func policyConfigField(fields map[string]any, name string) (any, error) {
	var result any
	found := false
	for key, value := range fields {
		if strings.EqualFold(key, name) {
			if found {
				return nil, fmt.Errorf("conflicting configuration field %s", name)
			}
			result, found = value, true
		}
	}
	return result, nil
}
