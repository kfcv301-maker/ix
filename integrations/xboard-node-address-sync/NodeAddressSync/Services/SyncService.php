<?php

namespace Plugin\NodeAddressSync\Services;

use App\Models\Server;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

class SyncService
{
    public function saveBinding(array $data): array
    {
        return DB::transaction(function () use ($data): array {
            WriteLock::acquire();
            $id = $data['id'] ?? (string) Str::uuid();
            $existing = isset($data['id'])
                ? DB::table('node_address_sync_bindings')->where('id', $id)->lockForUpdate()->first()
                : null;
            abort_if(isset($data['id']) && !$existing, 404, '绑定不存在');
            abort_if($existing && isset($data['revision']) && (int)$data['revision'] !== (int)$existing->revision, 409, '绑定已更新，请刷新后重试');
            $mode = $data['source_mode'] ?? ($existing->source_mode ?? 'agent');
            abort_unless(in_array($mode, ['agent', 'dns', 'relay'], true), 422, '来源模式无效');
            if ($mode === 'relay') {
                abort_unless(isset($data['connection_id'], $data['relay_node_id'])
                    && is_int($data['relay_node_id']) && $data['relay_node_id'] >= 1
                    && $data['relay_node_id'] <= 2147483647, 422, '请选择连接及有效的转发节点 ID');
                (new ConnectionService())->saveMapping($id, $data);
            } else {
                DB::table('node_address_sync_relay_mappings')->where('binding_id', $id)->delete();
            }
            $dns = [];
            if ($mode === 'dns') {
                $domain = strtolower(rtrim(trim($data['source_domain'] ?? ''), '.'));
                abort_unless(strlen($domain) <= 253 && preg_match('/^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/D', $domain), 422, '请填写有效的 DDNS 源域名');
                abort_unless(in_array($data['record_type'] ?? '', ['A','AAAA'], true), 422, '请选择 A 或 AAAA');
                abort_unless(in_array($data['selection'] ?? '', ['single','lowest'], true), 422, '请选择明确的多地址规则');
                abort_unless($data['dns_only_confirmed'] ?? false, 422, '请确认源记录为 DNS only 且指向对应入口');
                abort_if($data['enabled'] && DB::table('node_address_sync_bindings')->where('source_mode','dns')->where('enabled',true)->where('id','!=',$id)->count() >= 16, 422, '当前运行器最多启用 16 组 DNS 绑定');
                $dns = ['source_domain'=>$domain, 'record_type'=>$data['record_type'], 'selection'=>$data['selection'], 'dns_only_confirmed'=>true];
            }
            $ids = array_values(array_unique(array_map('intval', $data['node_ids'])));
            sort($ids);
            if ($mode==='dns' && $data['enabled']) {
                $otherNodes=DB::table('node_address_sync_nodes as n')->join('node_address_sync_bindings as b','b.id','=','n.binding_id')
                    ->where('b.source_mode','dns')->where('b.enabled',true)->where('b.id','!=',$id)->count();
                abort_if($otherNodes+count($ids)>128,422,'当前运行器最多同时检查 128 个 DNS 绑定节点');
            }
            $nodes = Server::whereIn('id', $ids)->orderBy('id')->lockForUpdate()->get();
            abort_if(count($nodes) !== count($ids), 422, '选择的节点已被删除，请刷新');
            $conflict = DB::table('node_address_sync_nodes')->whereIn('node_id', $ids)
                ->where('binding_id', '!=', $id)->exists();
            abort_if($conflict, 409, '节点已绑定其他服务器，请先解除原绑定');

            $token = $existing ? null : bin2hex(random_bytes(32));
            $fields = [
                'label' => $data['label'],
                'instance_id' => $data['instance_id'] ?? null,
                'enabled' => $data['enabled'],
                'updated_at' => now(),
                'source_mode' => $mode, 'revision' => ($existing->revision ?? 0) + 1,
                'candidate_ip'=>null, 'candidate_samples'=>0, 'candidate_since_ms'=>0,
                'dns_status'=>null, 'dns_detail'=>null,
            ] + $dns;
            if ($existing) {
                DB::table('node_address_sync_bindings')->where('id', $id)->update($fields);
            } else {
                DB::table('node_address_sync_bindings')->insert($fields + [
                    'id' => $id, 'token_hash' => hash('sha256', $token), 'created_at' => now(),
                ]);
            }
            // DNS edits retain manual-change protection unless explicitly accepted.
            $previous = DB::table('node_address_sync_nodes')->where('binding_id', $id)->get()->keyBy('node_id');
            DB::table('node_address_sync_nodes')->where('binding_id', $id)->delete();
            foreach ($nodes as $node) {
                DB::table('node_address_sync_nodes')->insert([
                    'node_id' => $node->id, 'binding_id' => $id,
                    'expected_host' => (in_array($mode, ['dns', 'relay'], true) && empty($data['accept_manual']) && $previous->has($node->id))
                        ? $previous->get($node->id)->expected_host : $node->host,
                ]);
            }
            if ($mode === 'dns') DB::table('node_address_sync_events')->insert([
                'binding_id'=>$id, 'kind'=>'configured', 'at_ms'=>(int)floor(microtime(true)*1000),
                'detail'=>json_encode(['source'=>$dns, 'node_ids'=>$ids, 'accept_manual'=>(bool)($data['accept_manual']??false)]),
            ]);
            return ['id' => $id, 'token' => $mode === 'agent' ? $token : null, 'revision'=>$fields['revision']];
        }, 3);
    }

    public function deleteBinding(string $id): void
    {
        DB::transaction(function () use ($id): void {
            WriteLock::acquire();
            $binding = DB::table('node_address_sync_bindings')->where('id', $id)->lockForUpdate()->first();
            abort_unless($binding, 404, '绑定不存在');
            DB::table('node_address_sync_nodes')->where('binding_id', $id)->delete();
            DB::table('node_address_sync_relay_mappings')->where('binding_id', $id)->delete();
            DB::table('node_address_sync_bindings')->where('id', $id)->delete();
        }, 3);
    }

    public function rotateToken(string $id): string
    {
        return DB::transaction(function () use ($id): string {
            WriteLock::acquire();
            $binding = DB::table('node_address_sync_bindings')->where('id', $id)->lockForUpdate()->first();
            abort_unless($binding, 404, '绑定不存在');
            abort_if(($binding->source_mode ?? 'agent') !== 'agent', 409, '此来源不使用实例上报凭据');
            $token = bin2hex(random_bytes(32));
            DB::table('node_address_sync_bindings')->where('id', $id)->update([
                'token_hash' => hash('sha256', $token), 'updated_at' => now(),
            ]);
            return $token;
        }, 3);
    }

    public function report(array $data, string $token): array
    {
        return DB::transaction(function () use ($data, $token): array {
            WriteLock::acquire();
            $binding = DB::table('node_address_sync_bindings')->where('id', $data['binding_id'])
                ->lockForUpdate()->first();
            abort_if(!$binding || !hash_equals($binding->token_hash, hash('sha256', $token)), 401, '上报凭据无效');
            abort_if(($binding->source_mode ?? 'agent') !== 'agent', 409, '此绑定不接受实例直接上报');
            return $this->applyReport($binding, $data);
        }, 3);
    }

    public function reportForRelay(array $data, string $token): array
    {
        return DB::transaction(function () use ($data, $token): array {
            WriteLock::acquire();
            $c = (new ConnectionService())->authorize($data, $token, true);
            $m = DB::table('node_address_sync_relay_mappings')->where('binding_id', $data['binding_id'])
                ->lockForUpdate()->first();
            abort_if(!$m || $m->connection_id !== $c->id || (int)$m->relay_node_id !== $data['relay_node_id'],
                403, '此连接与转发节点未获绑定授权');
            $binding = DB::table('node_address_sync_bindings')->where('id', $data['binding_id'])->lockForUpdate()->first();
            abort_unless($binding && $binding->source_mode === 'relay', 409, '绑定来源已改变，请刷新映射');
            $result = $this->applyReport($binding, $data, true);
            return ['protocol_version' => 2, 'connection_id' => $c->id, 'relay_panel_id' => $c->relay_panel_id,
                'relay_node_id' => $data['relay_node_id'], 'binding_id' => $binding->id] + $result;
        }, 3);
    }

    private function applyReport(object $binding, array $data, bool $relay = false): array
    {
            abort_unless($binding->enabled, 403, '绑定已暂停');
            abort_if($binding->instance_id && $binding->instance_id !== ($data['instance_id'] ?? null), 403, '实例 ID 与绑定不符');
            abort_unless(AddressPolicy::validPublicIpv4($data['ip']), 422, '必须上报有效的公网 IPv4');
            $observedAt = (int) $data['observed_at'];
            abort_if($observedAt < time() - 300 || $observedAt > time() + 60, 422, '上报时间超出允许范围，请校准服务器时钟');
            if (!$relay && $observedAt <= (int) $binding->last_observed_at) {
                if ($observedAt === (int) $binding->last_observed_at && $data['ip'] === $binding->last_ip) {
                    return ['status' => 'duplicate', 'updated' => 0, 'ip' => $data['ip']];
                }
                abort(409, '过期的上报已拒绝');
            }

            $links = DB::table('node_address_sync_nodes')->where('binding_id', $binding->id)
                ->orderBy('node_id')->get();
            abort_if($links->isEmpty(), 409, '此服务器尚未勾选节点');
            $nodes = Server::whereIn('id', $links->pluck('node_id'))->orderBy('id')->lockForUpdate()->get()->keyBy('id');
            // Preflight the entire batch. Never partially overwrite a manual edit.
            foreach ($links as $link) {
                $node = $nodes->get($link->node_id);
                abort_if(!$node, 409, '绑定节点已删除，请重新保存绑定');
                abort_if($node->host !== $link->expected_host, 409, '节点地址被手工修改，请确认后重新保存绑定');
            }

            // Even a duplicate Relay acknowledgement must not conceal a manual edit.
            if ($relay && $observedAt <= (int)$binding->last_observed_at) {
                if ($observedAt === (int)$binding->last_observed_at && $data['ip'] === $binding->last_ip) {
                    return ['status' => 'duplicate', 'updated' => 0, 'ip' => $data['ip']];
                }
                abort(409, '过期的上报已拒绝');
            }

            $updated = 0;
            foreach ($links as $link) {
                $node = $nodes->get($link->node_id);
                if ($node->host !== $data['ip']) {
                    $oldHost = $node->host;
                    $node->host = $data['ip'];
                    // Use the upstream model so its normal observers still run.
                    $node->save();
                    DB::table('node_address_sync_history')->insert([
                        'binding_id' => $binding->id, 'node_id' => $node->id,
                        'old_host' => $oldHost, 'new_host' => $data['ip'], 'created_at' => now(),
                    ]);
                    $updated++;
                }
            }
            DB::table('node_address_sync_nodes')->where('binding_id', $binding->id)->update(['expected_host' => $data['ip']]);
            DB::table('node_address_sync_bindings')->where('id', $binding->id)->update([
                'last_ip' => $data['ip'], 'last_observed_at' => $observedAt,
                'last_seen_at' => now(), 'updated_at' => now(),
            ]);
            DB::table('node_address_sync_history')->where('created_at', '<', now()->subDays(90))->delete();
            return ['status' => $updated ? 'updated' : 'unchanged', 'updated' => $updated, 'ip' => $data['ip']];
    }
}
