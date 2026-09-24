package service

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBatchReportAcknowledgesOnlyCommittedSequences(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		defer request.Body.Close()
		var payload trafficBatchRequest
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Fatalf("decode request: %v", err)
		}
		if payload.Version != 2 || len(payload.Items) != 2 {
			t.Fatalf("unexpected batch payload: %#v", payload)
		}
		writer.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(writer).Encode(trafficBatchResponse{
			Type: "flow_batch",
			Acknowledged: []trafficBatchAck{{
				N: payload.Items[0].N,
				I: payload.Items[0].I,
				Q: payload.Items[0].Q,
				B: payload.Items[0].B,
			}},
		})
	}))
	defer server.Close()

	previousURL, previousCrypto := httpReportURL, httpAESCrypto
	httpReportURL, httpAESCrypto = server.URL, nil
	defer func() { httpReportURL, httpAESCrypto = previousURL, previousCrypto }()

	items := []TrafficReportItem{
		{N: "12_3_9", I: "test_session", Q: 1, B: 100},
		{N: "13_3_9", I: "test_session", Q: 1, B: 100},
	}
	acknowledged, compatible, err := sendTrafficReportBatch(context.Background(), items)
	if err != nil || !compatible {
		t.Fatalf("batch report failed: compatible=%v err=%v", compatible, err)
	}
	if _, ok := acknowledged[trafficReportKey(items[0])]; !ok {
		t.Fatal("first report was not acknowledged")
	}
	if _, ok := acknowledged[trafficReportKey(items[1])]; ok {
		t.Fatal("unacknowledged report must remain pending")
	}
}

func TestBatchReportRecognizesLegacyPanelResponse(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		_, _ = writer.Write([]byte("ok"))
	}))
	defer server.Close()

	previousURL, previousCrypto := httpReportURL, httpAESCrypto
	httpReportURL, httpAESCrypto = server.URL, nil
	defer func() { httpReportURL, httpAESCrypto = previousURL, previousCrypto }()

	_, compatible, err := sendTrafficReportBatch(context.Background(), []TrafficReportItem{
		{N: "12_3_9", I: "test_session", Q: 1, B: 100},
	})
	if err != nil {
		t.Fatalf("legacy response should be a compatibility signal, not an error: %v", err)
	}
	if compatible {
		t.Fatal("plain ok must trigger the legacy protocol fallback")
	}
}
