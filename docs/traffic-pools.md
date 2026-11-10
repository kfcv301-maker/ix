# Traffic pools and inventory groups

Administrators use **节点流量池管理** to group nodes behind one entry domain.
The main view shows that domain, each bound server IP, and its online status.
Use **解析详情** for a DNS preview or to adopt existing records. A pool can be
saved before configuring its entry domain.
Another pool cannot use the same domain. Members are added explicitly; inventory
groups can be used to select several nodes at once.
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
successful checks before adding it. Each participating member must have a public
IP literal in the node's **服务器地址** field. The pool probes that IP and the
configured TCP port directly; the pool domain, node hostname, node DDNS record,
and reported source addresses are not probe targets. A socket connection tests
TCP reachability, not a full application transaction or every UDP forwarding path.
Each successful check also needs an online node and a report received from its
current authenticated Agent session in the last 30 seconds. A stale report or
offline node counts as a failure even if TCP still responds. Unknown or cancelled
probes do not count as TCP failures. Startup has a two-minute withdrawal grace
period for previously eligible addresses. No node software upgrade is required.

Adds occur before deletes. Deletion rechecks the exact provider record ID,
type, name, value and ownership comment. Each reconciliation has a bounded write
budget, so larger changes can continue over subsequent checks. Provider failures
are shown as errors and retried; a successful state requires a provider readback.
When every member is unhealthy, the pool withdraws the last owned address.
Legacy allowance and last-address settings are retained as compatibility data;
they do not affect DNS decisions.
DNS TTL is at least 60 seconds; client cache expiry is separate from
provider acceptance. Disabling automation leaves existing records in place.

## Tunnel membership and active connections

Binding a pool to a tunnel retains every existing ingress and adds the selected
members. It validates node port ranges, reserves all existing rule ports through
the existing reservation service, and rolls back database admission on a conflict.
It never rewrites a running listener to add a peer. New nodes reuse the existing
missing-service recovery mechanism. They wait for an actual post-recovery Agent
inventory and probe confirmation before being published in DNS. Older Agents may
take approximately ten minutes to send their next periodic inventory.
Inventory evidence is scoped to the current authenticated control connection;
a replacement machine cannot reuse a previous machine's cached service list.

Clearing an existing member's checkbox stops publishing its IP while retaining
its identity, tunnel membership, reservations and old connections.
Removing members/tunnels is intentionally not a destructive shortcut: service
cleanup and final retirement require a separate drain workflow. This version
does not claim to transfer an established connection between machines or enforce
a hard byte limit on live streams.

## Deployment

The migration adds separate tables and leaves existing node, tunnel and forward
rows untouched until an administrator explicitly binds a pool. Back up first;
verify the new tables on the production database version in a private warm-up
instance. `PANEL_BACKGROUND_JOBS_ENABLED=false` also suppresses the pool scheduler.
After deployment there are no automatic DNS writes until pools and credentials
are explicitly configured and enabled. Preserve fixed installation URLs, Agent
processes, node credentials, active listeners and all existing forwarding rules.

Provider reference: [Cloudflare DNS records](https://developers.cloudflare.com/api/resources/dns/subresources/records/).
