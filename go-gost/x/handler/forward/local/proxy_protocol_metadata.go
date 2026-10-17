package local

import (
	"encoding/json"
	"fmt"
	"math"
	"math/big"
	"reflect"
	"strconv"
	"strings"

	mdata "github.com/go-gost/core/metadata"
)

// A map[string]any decoded by encoding/json contains float64 numbers. The
// generic metadata integer getter does not support those values; using it here
// would silently disable a valid JSON-configured PROXY header.
func parseForwardProxyProtocol(md mdata.Metadata) (int, error) {
	if md == nil || !md.IsExists("proxyProtocol") {
		return 0, nil
	}
	value := md.Get("proxyProtocol")
	invalid := func() (int, error) {
		return 0, fmt.Errorf("invalid proxyProtocol value (%T): expected integer 0, 1 or 2", value)
	}
	switch v := value.(type) {
	case string:
		n, err := strconv.Atoi(v)
		if err == nil && n >= 0 && n <= 2 {
			return n, nil
		}
	case json.Number:
		text := v.String()
		if len(text) > 128 || !json.Valid([]byte(text)) {
			return invalid()
		}
		n, err := v.Float64()
		if err != nil || !validProxyProtocolNumber(n) {
			return invalid()
		}
		// Do not accept a fractional JSON decimal that rounds to 0, 1 or 2.
		// Handle exact zero before Rat parsing to avoid huge exponents making
		// an unnecessary large denominator (e.g. 0e-999999999).
		mantissa := strings.SplitN(strings.ToLower(text), "e", 2)[0]
		if strings.Trim(mantissa, "-0.") == "" {
			return 0, nil
		}
		if n == 0 {
			return invalid()
		}
		// A bounded-length value whose float is 1 or 2 has a bounded decimal
		// exponent. Exact rational parsing now cannot allocate unbounded data.
		rational, ok := new(big.Rat).SetString(text)
		if ok && rational.IsInt() && rational.Num().IsInt64() {
			exact := rational.Num().Int64()
			if exact >= 0 && exact <= 2 {
				return int(exact), nil
			}
		}
	case float64:
		if validProxyProtocolNumber(v) {
			return int(v), nil
		}
	case float32:
		if validProxyProtocolNumber(float64(v)) {
			return int(v), nil
		}
	default:
		if value != nil {
			integer := reflect.ValueOf(value)
			switch integer.Kind() {
			case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
				if n := integer.Int(); n >= 0 && n <= 2 {
					return int(n), nil
				}
			case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64:
				if n := integer.Uint(); n <= 2 {
					return int(n), nil
				}
			}
		}
	}
	return invalid()
}

func validProxyProtocolNumber(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0) && value >= 0 && value <= 2 && math.Trunc(value) == value
}
