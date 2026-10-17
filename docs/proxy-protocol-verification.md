# Proxy Protocol verification and upgrades

Release 1.4.12 fixes a runtime configuration defect: JSON numbers in handler
metadata are decoded as `float64`; the previous integer getter silently read a
valid `proxyProtocol: 1` or `2` as zero. An enabled switch or a successful Agent
command acknowledgement did not prove a header was sent.

## Upgrade order

1. Upgrade the entry Agents that need this feature to 1.4.12 or later.
2. Upgrade the panel. Its compatibility check now requires
   `forwardProxyProtocol >= 2` and the selected protocol version.
3. Configure the target listener to accept the selected PROXY version and read
   client addresses from its header. Restrict trusted senders appropriately.
4. Verify an actual TCP connection at the receiver. Compare the header's source
   address and port with the connection observed by the entry Agent.

The target's TCP socket peer remains the forwarding node. The header carries
the client's address as observed by the entry; addresses hidden upstream by NAT
or a proxy cannot be recovered automatically. UDP does not use this setting.

Existing enabled rules on an upgraded Agent start sending real headers after
this fix. A target that does not accept them may reject application traffic.
Forwarding with the setting disabled remains compatible with older Agents.

## Regression coverage

Run `go test ./...` in both `go-gost` and `go-gost/x`. The forwarding tests
round-trip configurations through JSON, independently decode the bytes on a
target TCP listener, and compare source/destination addresses and ports. They
cover v1/v2, IPv4/IPv6, direct forwarding, relay transports, and concurrent
connections. Socket tests exercise real WebSocket AddService and UpdateService
commands, including switching between off, v2, v1, and off again. Invalid values
must fail explicitly and preserve an existing valid service.

Run the backend Maven tests and the frontend tests/build as well. Backend tests
cover rejection of the older, defective capability revision before service
mutation and invalid TCP diagnostic results. Diagnostic success means separate
TCP connections succeeded; it does not verify the application session or the
receiver's PROXY parsing.

Release binaries must also be checked by SHA-256 against the immutable release
checksums and exercised with a controlled receiver. A UI label, stored JSON
field, capability response, or bare TCP connection alone is insufficient.

