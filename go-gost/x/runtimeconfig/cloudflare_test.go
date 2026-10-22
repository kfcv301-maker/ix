package runtimeconfig

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCloudflareFirstInstallReplacementAndTokenRotation(t *testing.T) {
	var records []dnsRecord
	writes := 0
	expectedToken := "token-one"
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+expectedToken {
			t.Error("stale provider credential")
		}
		var result any
		if r.URL.Path == "/zones" {
			zones := []map[string]string{}
			if r.URL.Query().Get("name") == "example.invalid" {
				zones = append(zones, map[string]string{"id": "zone", "name": "example.invalid"})
			}
			result = zones
		} else if r.Method == "GET" {
			filtered := []dnsRecord{}
			for _, rec := range records {
				if rec.Type == r.URL.Query().Get("type") && rec.Name == r.URL.Query().Get("name") {
					filtered = append(filtered, rec)
				}
			}
			result = filtered
		} else {
			var rec dnsRecord
			json.NewDecoder(r.Body).Decode(&rec)
			writes++
			if r.Method == "POST" {
				rec.ID = "record-" + rec.Type + "-" + rec.Name
				records = append(records, rec)
			} else if r.Method == "PATCH" {
				for i := range records {
					if strings.HasSuffix(r.URL.Path, "/"+records[i].ID) {
						rec.ID = records[i].ID
						records[i] = rec
					}
				}
			} else {
				t.Error("unexpected DNS write")
			}
			result = rec
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "result": result})
	}))
	defer server.Close()
	address4, address6 := "198.51.100.42", "2001:db8::42"
	u := &cloudflareUpdater{client: privateHTTPClient(), base: server.URL, probe: func(_ context.Context, k string) string {
		if k == "A" {
			return address4
		}
		return address6
	}}
	cfg := validConfig().DDNS
	if err := u.Sync(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	if writes != 2 {
		t.Fatal("first installation did not create both records")
	}
	if err := u.Sync(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	if writes != 2 {
		t.Fatal("unchanged addresses rewritten")
	}
	records[0].TTL = 300
	address4 = "198.51.100.43"
	if err := u.Sync(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	if writes != 3 || records[0].Content != address4 || records[0].TTL != 300 {
		t.Fatal("replacement failed or TTL lost")
	}
	expectedToken = "rotated-token"
	cfg.APIToken = expectedToken
	cfg.RecordName = "replacement.example.invalid"
	if err := u.Sync(context.Background(), cfg); err != nil {
		t.Fatal(err)
	}
	if writes != 5 {
		t.Fatal("new domain not applied")
	}
}

func TestCloudflareRejectsDuplicatesAndProviderFailure(t *testing.T) {
	for _, failure := range []bool{false, true} {
		t.Run(map[bool]string{false: "duplicates", true: "provider failure"}[failure], func(t *testing.T) {
			writes := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Method != "GET" {
					writes++
				}
				var result any
				if r.URL.Path == "/zones" {
					result = []map[string]string{{"id": "zone", "name": r.URL.Query().Get("name")}}
				} else {
					result = []dnsRecord{{ID: "one"}, {ID: "two"}}
				}
				json.NewEncoder(w).Encode(map[string]any{"success": !failure, "result": result, "errors": []string{"secret-token"}})
			}))
			defer server.Close()
			u := &cloudflareUpdater{client: privateHTTPClient(), base: server.URL, probe: func(context.Context, string) string { return "198.51.100.42" }}
			err := u.Sync(context.Background(), validConfig().DDNS)
			if err == nil || strings.Contains(err.Error(), "secret-token") || writes != 0 {
				t.Fatal("unsafe provider error handling")
			}
		})
	}
}
