<?php

namespace Plugin\NodeAddressSync\Services;

use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

final class ConnectionService
{
    public function listConnections()
    {
        return DB::table('node_address_sync_connections')->orderBy('created_at')->orderBy('id')
            ->get(['id', 'label', 'relay_panel_id', 'relay_url', 'enabled', 'revision', 'created_at', 'updated_at']);
    }

    public function save(array $data): array
    {
        // URL is a human-visible identifier only. Do not resolve or call it.
        $url = parse_url($data['relay_url']);
        abort_unless($url && ($url['scheme'] ?? '') === 'https' && !empty($url['host'])
            && !isset($url['user']) && !isset($url['pass']) && !isset($url['query']) && !isset($url['fragment'])
            && !preg_match('/[\x00-\x20\x7f]/', $data['relay_url']), 422, '转发面板标识须为不含凭据的 HTTPS 地址');
        return DB::transaction(function () use ($data): array {
            WriteLock::acquire();
            $id = $data['id'] ?? (string) Str::uuid();
            $existing = isset($data['id'])
                ? DB::table('node_address_sync_connections')->where('id', $id)->lockForUpdate()->first() : null;
            abort_if(isset($data['id']) && !$existing, 404, '连接不存在');
            abort_if($existing && (!isset($data['revision']) || (int)$data['revision'] !== (int)$existing->revision),
                409, '连接已更新，请刷新后重试');
            // Changing the peer identity requires creating a new connection and explicit remapping.
            abort_if($existing && $existing->relay_panel_id !== $data['relay_panel_id'], 409, '面板身份不可更改，请新建连接');
            $token = $existing ? null : bin2hex(random_bytes(32));
            $fields = ['label' => $data['label'], 'relay_panel_id' => $data['relay_panel_id'],
                'relay_url' => rtrim($data['relay_url'], '/'), 'enabled' => (bool)$data['enabled'],
                'revision' => ($existing->revision ?? 0) + 1, 'updated_at' => now()];
            if ($existing) {
                DB::table('node_address_sync_connections')->where('id', $id)->update($fields);
            } else {
                DB::table('node_address_sync_connections')->insert($fields + [
                    'id' => $id, 'token_hash' => hash('sha256', $token), 'created_at' => now(),
                ]);
            }
            return ['id' => $id, 'token' => $token, 'revision' => $fields['revision']];
        }, 3);
    }

    public function rotate(string $id, int $revision): array
    {
        return DB::transaction(function () use ($id, $revision): array {
            WriteLock::acquire();
            $c = DB::table('node_address_sync_connections')->where('id', $id)->lockForUpdate()->first();
            abort_unless($c, 404, '连接不存在');
            abort_if((int)$c->revision !== $revision, 409, '连接已更新，请刷新后重试');
            $token = bin2hex(random_bytes(32));
            DB::table('node_address_sync_connections')->where('id', $id)->update([
                'token_hash' => hash('sha256', $token), 'revision' => $revision + 1, 'updated_at' => now(),
            ]);
            return ['id' => $id, 'token' => $token, 'revision' => $revision + 1];
        }, 3);
    }

    public function delete(string $id, int $revision): void
    {
        DB::transaction(function () use ($id, $revision): void {
            WriteLock::acquire();
            $c = DB::table('node_address_sync_connections')->where('id', $id)->lockForUpdate()->first();
            abort_unless($c, 404, '连接不存在');
            abort_if((int)$c->revision !== $revision, 409, '连接已更新，请刷新后重试');
            abort_if(DB::table('node_address_sync_relay_mappings')->where('connection_id', $id)->exists(),
                409, '此连接仍有节点映射，请先解除映射或暂停连接');
            DB::table('node_address_sync_connections')->where('id', $id)->delete();
        }, 3);
    }

    public function authorize(array $data, string $token, bool $lock = false): object
    {
        $q = DB::table('node_address_sync_connections')->where('id', $data['connection_id']);
        $c = ($lock ? $q->lockForUpdate() : $q)->first();
        abort_if(!preg_match('/\A[a-f0-9]{64}\z/', $token) || !$c
            || !hash_equals($c->token_hash, hash('sha256', $token)), 401, '连接凭据无效');
        abort_unless($c->enabled, 403, '连接已暂停');
        abort_if($c->relay_panel_id !== $data['relay_panel_id'], 403, '转发面板身份未获授权');
        return $c;
    }

    public function check(array $data, string $token): array
    {
        return DB::transaction(function () use ($data, $token): array {
            $c = $this->authorize($data, $token, true);
            $mappings = DB::table('node_address_sync_relay_mappings as m')
                ->join('node_address_sync_bindings as b', 'm.binding_id', '=', 'b.id')
                ->where('m.connection_id', $c->id)->where('b.source_mode', 'relay')->orderBy('m.relay_node_id')
                ->get(['m.relay_node_id', 'm.binding_id', 'b.label', 'b.enabled'])
                ->map(static fn($m): array => ['relay_node_id' => (int)$m->relay_node_id,
                    'binding_id' => $m->binding_id, 'label' => mb_strcut($m->label, 0, 96, 'UTF-8'),
                    'enabled' => (bool)$m->enabled])->all();
            return ['protocol_version' => 2, 'status' => 'authorized', 'connection_id' => $c->id,
                'relay_panel_id' => $c->relay_panel_id, 'server_time' => time(), 'mappings' => $mappings];
        });
    }

    public function saveMapping(string $bindingId, array $data): void
    {
        $c = DB::table('node_address_sync_connections')->where('id', $data['connection_id'])->lockForUpdate()->first();
        abort_unless($c, 422, '请先配置允许上报的转发面板');
        abort_if(DB::table('node_address_sync_relay_mappings')->where('connection_id', $c->id)
            ->where('binding_id', '!=', $bindingId)->count() >= 100, 422, '每个连接最多映射 100 个转发节点');
        abort_if(DB::table('node_address_sync_relay_mappings')->where('connection_id', $c->id)
            ->where('relay_node_id', $data['relay_node_id'])->where('binding_id', '!=', $bindingId)->exists(),
            409, '此转发节点已映射其他绑定');
        DB::table('node_address_sync_relay_mappings')->updateOrInsert(['binding_id' => $bindingId], [
            'connection_id' => $c->id, 'relay_node_id' => (int)$data['relay_node_id'],
        ]);
    }
}
