# Traffic pools and inventory groups

Administrators use **流量池** in the sidebar to collect nodes, configure allowances,
bind existing administrator tunnels and manage a dedicated DNS entry name.
**节点分组** and **服务器分组** classify the two inventories independently; sharing a
group name does not link a VPS to a forwarding identity or change permissions.
Ordinary users cannot call these endpoints or read the provider credentials.

## DNS management

The first provider is Cloudflare, using a Zone ID and zone-scoped DNS-edit API
token. Tokens are encrypted with a pool-specific context, never returned to the
browser and never installed on nodes. Use a dedicated DNS-only subdomain. Existing
node DDNS domains and this pool entry name must be different.

Automatic writing defaults off. Save the configuration, inspect **预览解析变化**,
then explicitly enable the domain. A pool creates A and AAAA records with its
own `lunaris-pool/<id>` comment. Foreign or proxied address records and CNAMEs block
writing. The optional confirmed adoption action changes only the comment on
existing DNS-only addresses matching current members; it rechecks a preview
fingerprint and current ownership. The UI processes adoption in sequential
batches automatically. Records of another pool and unknown addresses cannot be
adopted. Stop other automatic writers before adopting their records.

Checks run around every 20 seconds under normal load, on an isolated scheduler.
The defaults require three failed checks to withdraw an address and two
successful checks before adding it. Probes use public numeric addresses, bounded
parallel sockets and a configured TCP port. A socket connection tests TCP
reachability, not a full application transaction or every UDP forwarding path.
Unknown or cancelled probes do not count as failures. Startup has a two-minute
withdrawal grace period. A lost control heartbeat alone does not withdraw an
already healthy TCP endpoint.

Address observations use a fresh, credential-matched existing AWS IMDS report
where available, otherwise the configured node address or its own DDNS hostname.
They do not infer ingress addresses from arbitrary outbound-IP metrics.
Private and mixed public/private answers are rejected. No node software is
upgraded or restarted to add this feature.

Adds occur before deletes. Deletion rechecks the exact provider record ID,
type, name, value and ownership comment. Each reconciliation has a bounded write
budget, so larger changes can continue over subsequent checks. Provider failures
are shown as errors and retried; a successful state requires a provider readback.
When every member is unhealthy, the default holds existing records and reports
the condition. Disabling this protection allows all owned address records to be
removed. DNS TTL is at least 60 seconds; client cache expiry is separate from
provider acceptance. Disabling automation leaves existing records in place.

## Tunnel membership and active connections

Binding a pool to a tunnel retains every existing ingress and adds the selected
members. It validates node port ranges, reserves all existing rule ports through
the existing reservation service, and rolls back database admission on a conflict.
It never rewrites a running listener to add a peer. New nodes reuse the existing
missing-service recovery mechanism. They wait for an actual post-recovery Agent
inventory and probe confirmation before being published in DNS. Older Agents may
take approximately ten minutes to send their next periodic inventory.

Members can enter maintenance mode, which withdraws their DNS candidacy while
retaining identities, usage, tunnel membership, reservations and old connections.
Removing members/tunnels is intentionally not a destructive shortcut: service
cleanup and final retirement require a separate drain workflow. This version
does not claim to transfer an established connection between machines or enforce
a hard byte limit on live streams.

## Allowance estimates

Individual allowance mode shows remaining configured bytes per node, then sums
those remainders. Shared mode compares the member sum against one configured
pool allowance. A server's unused provider allowance is not transferable unless
the provider itself supports sharing. Zero means no configured allowance.

Counters come from existing passive node reports. The first report establishes
a baseline; administrators can calibrate **已用** explicitly. Positive deltas are
persisted, duplicate samples are ignored, and machine/counter resets preserve the
period's existing used total. The monthly boundary uses Asia/Shanghai; short
months clamp the reset day to the last calendar day. Missing observations across
a boundary establish a new baseline instead of assigning old-period traffic to
the new period. Editing a reset day preserves accumulated usage.

Every screen labels these figures as panel estimates. Interface selection,
unobserved resets, period gaps and provider rules can differ from billing. They
are separate from customer billing multipliers. Allowance exhaustion affects DNS
candidacy, not existing streams; last-address protection may retain an exhausted
address. Consult the provider bill for exact usage.

## Deployment

The migration adds separate tables and leaves existing node, tunnel and forward
rows untouched until an administrator explicitly binds a pool. Back up first;
verify the new tables on the production database version in a private warm-up
instance. `PANEL_BACKGROUND_JOBS_ENABLED=false` also suppresses the pool scheduler.
After deployment there are no automatic DNS writes until pools and credentials
are explicitly configured and enabled. Preserve fixed installation URLs, Agent
processes, node credentials, active listeners and all existing forwarding rules.

Provider reference: [Cloudflare DNS records](https://developers.cloudflare.com/api/resources/dns/subresources/records/).
