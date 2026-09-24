package service

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/go-gost/core/observer/stats"
	xstats "github.com/go-gost/x/observer/stats"
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

func TestTrafficReportSessionAdvancesWhenServiceRestarts(t *testing.T) {
	firstID, firstStart := newTrafficReportSession()
	secondID, secondStart := newTrafficReportSession()
	if firstID == secondID {
		t.Fatal("recreated service reused the old report session ID")
	}
	if secondStart <= firstStart {
		t.Fatalf("recreated service must have a newer report session: %d <= %d", secondStart, firstStart)
	}
}

func TestClosingServiceReportsBytesBeforeNextStatsTick(t *testing.T) {
	var mu sync.Mutex
	var reports []TrafficReportItem
	server := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		defer request.Body.Close()
		var payload trafficBatchRequest
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Errorf("decode final report: %v", err)
			return
		}
		mu.Lock()
		reports = append(reports, payload.Items...)
		mu.Unlock()
		ack := make([]trafficBatchAck, 0, len(payload.Items))
		for _, item := range payload.Items {
			ack = append(ack, trafficBatchAck{N: item.N, I: item.I, Q: item.Q, B: item.B})
		}
		_ = json.NewEncoder(writer).Encode(trafficBatchResponse{Type: "flow_batch", Acknowledged: ack})
	}))
	defer server.Close()
	previousURL, previousCrypto := httpReportURL, httpAESCrypto
	httpReportURL, httpAESCrypto = server.URL, nil
	defer func() { httpReportURL, httpAESCrypto = previousURL, previousCrypto }()

	counters := xstats.NewStats(true)
	counters.Add(stats.KindInputBytes, 16384)
	counters.Add(stats.KindOutputBytes, 16384)
	svc := &defaultService{name: "4_1_0_tcp", status: &Status{stats: counters}}
	svc.flushFinalTraffic(nil, 0, "new-session", 123)
	mu.Lock()
	defer mu.Unlock()
	if len(reports) != 1 || reports[0].D != 16384 || reports[0].U != 16384 || reports[0].Q != 1 {
		t.Fatalf("closing service lost its final traffic: %#v", reports)
	}
	if counters.Get(stats.KindInputBytes) != 0 || counters.Get(stats.KindOutputBytes) != 0 {
		t.Fatal("acknowledged final traffic remained in the service counters")
	}
}
