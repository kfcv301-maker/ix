package config

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/spf13/viper"
)

func isolatedPolicyReader(t *testing.T, format string) {
	t.Helper()
	before := v
	v = viper.New()
	v.SetConfigType(format)
	t.Cleanup(func() { v = before })
}

func TestSavedUserProtocolPoliciesSurviveEveryConfigReader(t *testing.T) {
	for _, format := range []string{"json", "yaml"} {
		for _, method := range []string{"Read", "ReadFile", "Load"} {
			for _, mode := range []string{TLSOnly, TLSDeny, TLSUnrestricted} {
				t.Run(format+"/"+method+"/"+mode, func(t *testing.T) {
					isolatedPolicyReader(t, format)
					want := UserProtocolPolicy{TLS: mode, AllowUDP: false, Revision: 18446744073709551614}
					original := &Config{Services: []*ServiceConfig{{Name: "1_2_3_tcp", Metadata: map[string]any{
						UserPolicyMetadataKey: want, "interface": "eth0", "paused": true,
					}}}}
					var data bytes.Buffer
					if err := original.Write(&data, format); err != nil {
						t.Fatal(err)
					}
					var restored Config
					var err error
					if method == "Read" {
						err = restored.Read(bytes.NewReader(data.Bytes()))
					} else {
						file := filepath.Join(t.TempDir(), "gost."+format)
						if err = os.WriteFile(file, data.Bytes(), 0600); err != nil {
							t.Fatal(err)
						}
						if method == "Load" {
							v.SetConfigFile(file)
							err = restored.Load()
						} else {
							err = restored.ReadFile(file)
						}
					}
					if err != nil {
						t.Fatal(err)
					}
					md := restored.Services[0].Metadata
					got, err := ParseUserProtocolPolicy(md)
					if err != nil || got == nil || *got != want {
						t.Fatalf("policy changed after %s: got %+v, %v; want %+v", method, got, err, want)
					}
					if md["interface"] != "eth0" || md["paused"] != true {
						t.Fatal("unrelated metadata changed")
					}
					assertSingleSavedPolicyKey(t, md)
				})
			}
		}
	}
}

func assertSingleSavedPolicyKey(t *testing.T, md map[string]any) {
	t.Helper()
	count := 0
	for key := range md {
		if strings.EqualFold(key, UserPolicyMetadataKey) {
			count++
			if key != UserPolicyMetadataKey {
				t.Fatalf("saved noncanonical policy alias %q", key)
			}
		}
	}
	if count != 1 {
		t.Fatalf("expected one saved policy, got %d", count)
	}
}

func TestOriginalPolicyAliasesResolvedBeforeViperLowercases(t *testing.T) {
	for _, latestKey := range []string{UserPolicyMetadataKey, "userprotocolpolicy", "USERPROTOCOLPOLICY"} {
		t.Run(latestKey, func(t *testing.T) {
			isolatedPolicyReader(t, "json")
			oldKey := UserPolicyMetadataKey
			if latestKey == oldKey {
				oldKey = "userprotocolpolicy"
			}
			data := fmt.Sprintf(`{"services":[{"name":"1_2_3_tcp","metadata":{
				%q:{"tls":"TLS_ONLY","allowUdp":false,"revision":12},
				%q:{"tls":"UNRESTRICTED","allowudp":true,"revision":11},"interface":"eth0"}}]}`, latestKey, oldKey)
			// Repeated reads exercise randomized map order in Viper and Go maps.
			for i := 0; i < 30; i++ {
				var restored Config
				if err := restored.Read(strings.NewReader(data)); err != nil {
					t.Fatal(err)
				}
				p, err := ParseUserProtocolPolicy(restored.Services[0].Metadata)
				if err != nil || p == nil || p.Revision != 12 || p.TLS != TLSOnly || p.AllowUDP {
					t.Fatalf("old permissive alias replaced latest policy: %+v, %v", p, err)
				}
				assertSingleSavedPolicyKey(t, restored.Services[0].Metadata)
			}
		})
	}
}

func TestSavedAmbiguousAndInvalidPoliciesFailClosed(t *testing.T) {
	for name, metadata := range map[string]string{
		"same revision conflict":  `"userProtocolPolicy":{"tls":"TLS_ONLY","allowUdp":false,"revision":4},"userprotocolpolicy":{"tls":"UNRESTRICTED","allowudp":true,"revision":4}`,
		"nested UDP conflict":     `"userProtocolPolicy":{"tls":"TLS_ONLY","allowUdp":false,"allowudp":true,"revision":4}`,
		"nested TLS conflict":     `"userProtocolPolicy":{"tls":"TLS_ONLY","TLS":"UNRESTRICTED","revision":4}`,
		"nested version conflict": `"userProtocolPolicy":{"tls":"TLS_ONLY","revision":4,"REVISION":5}`,
		"null policy":             `"userProtocolPolicy":null`,
		"null UDP":                `"userProtocolPolicy":{"tls":"TLS_ONLY","allowUdp":null}`,
		"invalid newest":          `"userProtocolPolicy":{"tls":"INVALID","revision":5},"userprotocolpolicy":{"tls":"UNRESTRICTED","revision":4}`,
		"duplicate exact key":     `"userProtocolPolicy":{"tls":"TLS_ONLY"},"userProtocolPolicy":{"tls":"UNRESTRICTED"}`,
	} {
		t.Run(name, func(t *testing.T) {
			isolatedPolicyReader(t, "json")
			var cfg Config
			if err := cfg.Read(strings.NewReader(`{"services":[{"name":"1_2_3_tcp","metadata":{` + metadata + `}}]}`)); err == nil {
				t.Fatal("ambiguous or invalid policy was accepted")
			}
		})
	}
}

func TestSavedYamlPolicyAliasesAndMerges(t *testing.T) {
	isolatedPolicyReader(t, "yaml")
	var cfg Config
	err := cfg.Read(strings.NewReader(`defaults: &base
  metadata: &metadata
    userprotocolpolicy: {tls: UNRESTRICTED, allowudp: true, revision: 1}
    userProtocolPolicy: {tls: TLS_DENY, allowUdp: false, revision: 2}
services:
  - <<: *base
    name: 1_2_3_tcp
  - name: 1_2_3_udp
    metadata: *metadata
`))
	if err != nil {
		t.Fatal(err)
	}
	for _, svc := range cfg.Services {
		p, err := ParseUserProtocolPolicy(svc.Metadata)
		if err != nil || p == nil || p.TLS != TLSDeny || p.AllowUDP || p.Revision != 2 {
			t.Fatalf("YAML policy alias was lost: %+v, %v", p, err)
		}
	}
}

func TestReadOtherImportedConfigFormatStillWorks(t *testing.T) {
	isolatedPolicyReader(t, "toml")
	file := filepath.Join(t.TempDir(), "gost.toml")
	if err := os.WriteFile(file, []byte("[[services]]\nname = 'legacy'\naddr = ':8080'\n"), 0600); err != nil {
		t.Fatal(err)
	}
	var cfg Config
	if err := cfg.ReadFile(file); err != nil || len(cfg.Services) != 1 || cfg.Services[0].Name != "legacy" {
		t.Fatalf("unrelated imported format changed: %+v, %v", cfg, err)
	}
}

func TestReadOtherFormatsWithoutConfigFileName(t *testing.T) {
	for _, tc := range []struct {
		format string
		input  string
	}{
		{"toml", "[log]\nlevel = 'debug'\n"},
		{"ini", "[log]\nlevel = debug\n"},
		{"dotenv", "log.level=debug\n"},
	} {
		t.Run(tc.format, func(t *testing.T) {
			isolatedPolicyReader(t, tc.format)
			var cfg Config
			if err := cfg.Read(strings.NewReader(tc.input)); err != nil || cfg.Log == nil || cfg.Log.Level != "debug" {
				t.Fatalf("import format changed without a filename: %+v, %v", cfg, err)
			}
		})
	}
}

func TestSavedYamlEmptyAndUnrelatedMetadataKeys(t *testing.T) {
	isolatedPolicyReader(t, "yaml")
	var cfg Config
	if err := cfg.Read(strings.NewReader(`services:
  - name: omitted
  - name: empty
    metadata: {}
  - name: null
    metadata:
  - name: mixed_keys
    metadata:
      7: unrelated
      interface: eth0
      userProtocolPolicy: {tls: TLS_ONLY, allowUdp: false, revision: 2}
      userprotocolpolicy: {tls: UNRESTRICTED, allowudp: true, revision: 1}
`)); err != nil {
		t.Fatal(err)
	}
	for _, svc := range cfg.Services[:3] {
		if p, err := ParseUserProtocolPolicy(svc.Metadata); p != nil || err != nil {
			t.Fatalf("empty metadata gained a policy: %+v, %v", p, err)
		}
	}
	p, err := ParseUserProtocolPolicy(cfg.Services[3].Metadata)
	if err != nil || p == nil || p.TLS != TLSOnly || p.AllowUDP || p.Revision != 2 {
		t.Fatalf("unrelated YAML key hid policy: %+v, %v", p, err)
	}
}
