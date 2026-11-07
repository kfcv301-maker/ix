# Address synchronization acceptance

The supported path is node observation -> relay panel -> authorized airport
connection -> airport node host database commit. The connection address and
token are editable online; existing node installation commands do not change.
All connections and bindings are administrator-only and opt-in.

## One timing definition

The normal target is **30 seconds**, with **60 seconds** as the acceptance limit.
Start the clock when the instance's public IPv4 actually changes. Stop it when
the airport database transaction commits that IPv4. A queued request, HTTP 200
without a validated receipt, or a relay-panel-only update is not completion.
DNS cache expiry, subscription refresh and existing client connections are
separate from this host-field measurement.

The AWS observer runs five seconds after each completed attempt (plus timer
accuracy and execution time); it is not a guaranteed five-second wall-clock
period. A valid observation must remain consistent for ten seconds. Replacement
machines also require twenty seconds without competing reports from the old
identity. Two simultaneously reporting machines are blocked instead of racing
to overwrite the airport address.

The relay checks work every five seconds on a scheduler isolated from accounting
jobs. It allows eight concurrent deliveries, with no unbounded waiting queue.
An unfinished destination does not hold an entire batch. DNS has a two-second
budget; each HTTP exchange has an eight-second cancellation deadline. Delivery
still requires available database connections, reachable authorized endpoints,
fresh metadata and observations, and adequate capacity for the enabled nodes.

Retries are bounded and backed off; outages, invalid credentials, source
conflicts, overloaded databases, or cold instance boot can exceed sixty seconds.
They must be recorded as failed acceptance, never reported as successful within
the limit. Do not claim a hard network-independent latency guarantee.

## Verification

Record an independent start timestamp, node observation time, relay receipt,
airport transaction receipt, and final host value. Exercise ordinary IPv4 change,
replacement identity, one failed request then recovery, and unrelated slow
destinations. Keep the existing forwarding stream running throughout the test.
Use isolated fixtures until an authorized real change is available. Virtual-clock
tests verify state-machine timing, not end-to-end production latency.

## Private deployment warm-up

`PANEL_BACKGROUND_JOBS_ENABLED=false` disables all scheduled tasks in a private
warm-up process. Defaults remain enabled for normal installs. It does not block
HTTP mutations, node WebSocket callbacks, bootstrap administrators, or startup
schema migrations. Keep the candidate private, back up the database and review
migrations before starting it. Do not route node-management APIs to a candidate
whose process does not own those node WebSocket sessions. Turning off scheduling
alone is not an active-active or zero-downtime deployment mechanism.

Node forwarding and panel control connections are distinct. Preserve node
processes and listeners; verify an uninterrupted real forwarding stream during
any panel replacement. Do not describe a reconnecting control WebSocket as an
uninterrupted control connection.
