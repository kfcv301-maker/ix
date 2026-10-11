"""Compare the same native UDP benchmark on the base and proposed Agent sources."""

import argparse
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import tempfile


def run(command, cwd, env=None):
    result = subprocess.run(command, cwd=cwd, env=env, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=180)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    baseline = Path(tempfile.mkdtemp(prefix="lunaris-agent-benchmark-"))
    run(["git", "worktree", "add", "--detach", str(baseline), args.baseline_ref], root)
    original = baseline / "go-gost" / "x"
    repaired = root / "go-gost" / "x"

    # Use identical benchmark bodies and listener settings on both sources.
    # The temporary worktree belongs to this run; the user's checkout is untouched.
    source = (repaired / "internal/net/udp/listener_test.go").read_text(encoding="utf-8")
    helpers = source[source.index("func udpTestListener"):source.index("func TestUDPDatagrams")]
    benchmarks = source[source.index("func BenchmarkUDPListenerThroughput"):]
    fixture = 'package udp\nimport("bytes";"errors";"io";"net";"strconv";"testing";"time")\n' + helpers + benchmarks
    (original / "internal/net/udp/resource_benchmark_test.go").write_text(fixture, encoding="utf-8")
    env = dict(os.environ, CGO_ENABLED="0", GOMAXPROCS="4")
    # Compile before collecting timings. Alternate execution order to reduce
    # bias from host warmup and retain all raw measurements for review.
    for folder in (original, repaired):
        run(["go", "test", "-run=^$", "./internal/net/udp"], folder, env)
    observations = {"original": {}, "repaired": {}}
    raw = {"original": [], "repaired": []}
    pattern = re.compile(r"BenchmarkUDPListenerThroughput/(\S+)-\d+\s+\d+\s+([\d.]+) ns/op")
    for iteration in range(3):
        variants = [("original", original), ("repaired", repaired)]
        if iteration % 2:
            variants.reverse()
        for label, folder in variants:
            output = run(["go", "test", "-run=^$", "-bench=BenchmarkUDPListenerThroughput",
                          "-benchmem", "-benchtime=350ms", "-count=2", "-timeout=120s",
                          "./internal/net/udp"], folder, env)
            raw[label].append(output)
            for name, elapsed in pattern.findall(output):
                observations[label].setdefault(name, []).append(float(elapsed))
    if len(observations["original"]) != 6 or observations["original"].keys() != observations["repaired"].keys():
        raise RuntimeError("Incomplete benchmark measurements")
    cases = {}
    for name, before in observations["original"].items():
        after = observations["repaired"][name]
        if len(before) != 6 or len(after) != 6:
            raise RuntimeError("Expected six measurements for each source and case")
        delta = (statistics.median(before) / statistics.median(after) - 1) * 100
        cases[name] = {"original_ns": before, "repaired_ns": after, "median_throughput_change_percent": delta}
        print(f"{name}: median throughput change {delta:+.2f}%", flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"baseline_ref": args.baseline_ref, "go_version": run(["go", "version"], root).strip(),
                                      "gomaxprocs": 4, "cases": cases, "raw": raw}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
