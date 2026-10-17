package local

import (
	"encoding/json"
	"math"
	"testing"

	xmetadata "github.com/go-gost/x/metadata"
)

func TestForwardProxyProtocolMetadataAcceptsJSONAndIntegerValues(t *testing.T) {
	for _, tc := range []struct {
		value any
		want  int
	}{
		{0, 0}, {1, 1}, {2, 2}, {int64(2), 2}, {uint8(1), 1},
		{float64(0), 0}, {float64(1), 1}, {float64(2), 2}, {float32(2), 2},
		{"0", 0}, {"1", 1}, {"2", 2},
		{json.Number("2"), 2}, {json.Number("2.0"), 2}, {json.Number("1e0"), 1},
		{json.Number("0e-99999999"), 0},
	} {
		got, err := parseForwardProxyProtocol(xmetadata.NewMetadata(map[string]any{"proxyProtocol": tc.value}))
		if err != nil || got != tc.want {
			t.Fatalf("%T(%v): got %d, %v, want %d", tc.value, tc.value, got, err, tc.want)
		}
	}
	if got, err := parseForwardProxyProtocol(nil); got != 0 || err != nil {
		t.Fatalf("missing field must remain disabled: %d, %v", got, err)
	}
}

func TestForwardProxyProtocolMetadataRejectsInvalidExplicitValues(t *testing.T) {
	for _, value := range []any{nil, true, false, -1, 3, int64(math.MaxInt64), uint64(math.MaxUint64),
		0.5, 2.1, -0.1, math.NaN(), math.Inf(1), math.Inf(-1), "", "true", "2.0", "invalid", "3",
		json.Number("1.0000000000000000000001"), json.Number("0.0000000000000000000001"),
		json.Number("2e99999999"), json.Number("NaN"), json.Number("null"), json.Number("-1"),
		[]int{2}, map[string]any{"version": 2}} {
		if _, err := parseForwardProxyProtocol(xmetadata.NewMetadata(map[string]any{"proxyProtocol": value})); err == nil {
			t.Fatalf("accepted invalid %T(%v)", value, value)
		}
	}
}
