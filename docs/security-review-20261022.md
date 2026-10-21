# Security maintenance: 1.4.14

The panel uses Java, Go and a static React frontend. PHP CVE-2026-109618 and
CVE-2026-109619 apply to PHP runtimes, not to these application components.
Operators must still inventory any separately installed PHP applications.

## Changes

- Parse and recursively redact audit-log JSON. Escaped quotes, nested objects,
  serialized JSON and malformed input no longer bypass credential redaction.
  Exception audit records retain the exception class, without raw messages.
- Limit login attempts by normalized account name and direct connection source.
  Reject excess requests before password verification, with HTTP 429 and
  Retry-After. The in-memory limiter is bounded; it is per backend process.
- Update Jackson, Tomcat, Bouncy Castle, SSHJ, FreeMarker and Log4j components.
- Update frontend runtime and development dependencies, including React Router.
- Build Agents with Go 1.26.10 and update crypto, network, gRPC, QUIC and
  WebTransport dependencies. Adapt the transport APIs and verify an actual
  WebTransport connection and data round trip.
- Keep permanent installer capabilities and existing credentials unchanged.

## Deployment guidance and remaining findings

Use the default loopback backend binding behind the HTTPS reverse proxy.
An explicit BACKEND_BIND_ADDRESS=0.0.0.0 overrides that protection. Preserve
database volumes and credentials when recreating the backend container.

The packaged Spring Framework 6.2.19 still matches CVE-2026-47884 and
CVE-2026-47890 by version. The panel does not configure XsltView or SSE fragment
rendering, which are required by those advisories. This is an applicability
assessment, not a claim that the framework version includes those fixes.

The Agent binary still includes the optional Pion DTLS v2 transport flagged by
GO-2026-4479 (random AES-GCM nonces). Do not treat this optional transport as
patched. Migrating its protocol dependency requires separate interoperability
validation. GO-2026-5932 flags the unmaintained OpenPGP module; the binary scan
found no OpenPGP symbols in the executable.

Existing MySQL 5.7 installations remain a lifecycle risk. New installations
select MySQL 8.4, but an existing data directory must not be attached directly
to a new major version without a tested migration and backup/restore plan.

The updater intentionally has Docker control for authenticated administrator
updates. Compromise of that privileged component has host-level impact.

Updating the panel does not automatically replace every deployed Agent binary.
Agents require a separate controlled update to receive the new Go dependencies.

## Validation

Backend unit/controller tests, both Go module suites, frontend tests and
production build; browser navigation on desktop and mobile; installer checksum
and release-version contracts. Dependency scan results distinguish version
matches, compiled symbols and configuration prerequisites. Production validation
uses non-destructive requests and does not attempt a destructive exploit.
