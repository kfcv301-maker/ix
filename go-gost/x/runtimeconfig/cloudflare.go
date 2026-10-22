package runtimeconfig

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Endpoints are fixed in production. Credentials must never be sent to a
// configurable probe URL, a redirect target or an arbitrary API host.
type cloudflareUpdater struct {
	client *http.Client
	base   string
	probe  func(context.Context, string) string
}
type dnsRecord struct {
	ID      string `json:"id,omitempty"`
	Type    string `json:"type,omitempty"`
	Name    string `json:"name,omitempty"`
	Content string `json:"content,omitempty"`
	TTL     int    `json:"ttl,omitempty"`
	Proxied bool   `json:"proxied"`
}

func newCloudflareUpdater() *cloudflareUpdater {
	u := &cloudflareUpdater{client: privateHTTPClient(), base: "https://api.cloudflare.com/client/v4"}
	u.probe = publicIP
	return u
}

func publicIP(ctx context.Context, family string) string {
	endpoint, network := "https://api.ipify.org", "tcp4"
	if family == "AAAA" {
		endpoint, network = "https://api64.ipify.org", "tcp6"
	}
	dialer := &net.Dialer{Timeout: 8 * time.Second}
	transport := &http.Transport{DialContext: func(ctx context.Context, _, addr string) (net.Conn, error) {
		return dialer.DialContext(ctx, network, addr)
	}, TLSHandshakeTimeout: 8 * time.Second}
	defer transport.CloseIdleConnections()
	client := privateHTTPClient()
	client.Transport = transport
	client.Timeout = 10 * time.Second
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	resp, err := client.Do(req)
	if err != nil {
		return ""
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return ""
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 128))
	if err != nil {
		return ""
	}
	ip := net.ParseIP(strings.TrimSpace(string(b)))
	if ip == nil || !ip.IsGlobalUnicast() || ip.IsPrivate() {
		return ""
	}
	if (family == "A") != (ip.To4() != nil) {
		return ""
	}
	return ip.String()
}

func (u *cloudflareUpdater) request(ctx context.Context, token, method, path string, payload any, target any) error {
	var body io.Reader
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil {
			return errors.New("cannot encode DNS record")
		}
		body = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, u.base+path, body)
	if err != nil {
		return errors.New("invalid DNS request")
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err := u.client.Do(req)
	if err != nil {
		return errors.New("DNS request failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return errors.New("DNS provider rejected request")
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 1048577))
	if err != nil || len(b) > 1048576 {
		return errors.New("invalid DNS response")
	}
	var envelope struct {
		Success bool            `json:"success"`
		Result  json.RawMessage `json:"result"`
	}
	if json.Unmarshal(b, &envelope) != nil || !envelope.Success {
		return errors.New("DNS provider rejected request")
	}
	if target != nil && json.Unmarshal(envelope.Result, target) != nil {
		return errors.New("invalid DNS response data")
	}
	return nil
}

func (u *cloudflareUpdater) Sync(ctx context.Context, cfg DDNS) error {
	addresses := map[string]string{}
	for _, kind := range []string{"A", "AAAA"} {
		if address := u.probe(ctx, kind); address != "" {
			addresses[kind] = address
		}
	}
	if len(addresses) == 0 {
		return errors.New("no usable public address")
	}
	zoneID := ""
	for candidate := cfg.RecordName; strings.Contains(candidate, "."); candidate = strings.SplitN(candidate, ".", 2)[1] {
		var zones []struct {
			ID   string `json:"id"`
			Name string `json:"name"`
		}
		if err := u.request(ctx, cfg.APIToken, "GET", "/zones?name="+url.QueryEscape(candidate), nil, &zones); err != nil {
			return err
		}
		if len(zones) == 1 && zones[0].Name == candidate && zones[0].ID != "" {
			zoneID = zones[0].ID
			break
		}
	}
	if zoneID == "" {
		return errors.New("DNS zone not found")
	}
	for _, kind := range []string{"A", "AAAA"} {
		address := addresses[kind]
		if address == "" {
			continue
		}
		path := "/zones/" + url.PathEscape(zoneID) + "/dns_records"
		var records []dnsRecord
		if err := u.request(ctx, cfg.APIToken, "GET", path+"?per_page=100&type="+kind+"&name="+url.QueryEscape(cfg.RecordName), nil, &records); err != nil {
			return err
		}
		if len(records) > 1 {
			return errors.New("multiple DNS records; refusing ambiguous update")
		}
		payload := dnsRecord{Type: kind, Name: cfg.RecordName, Content: address, TTL: 1}
		method := "POST"
		if len(records) == 1 {
			r := records[0]
			if r.ID == "" || r.Type != kind || r.Name != cfg.RecordName {
				return errors.New("DNS record identity mismatch")
			}
			if r.Content == address {
				continue
			}
			payload.TTL, payload.Proxied = r.TTL, r.Proxied
			if payload.TTL <= 0 {
				payload.TTL = 1
			}
			method, path = "PATCH", path+"/"+url.PathEscape(r.ID)
		}
		if err := u.request(ctx, cfg.APIToken, method, path, payload, nil); err != nil {
			return err
		}
	}
	return nil
}
