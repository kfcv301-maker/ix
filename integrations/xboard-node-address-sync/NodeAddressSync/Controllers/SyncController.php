<?php

namespace Plugin\NodeAddressSync\Controllers;

use App\Http\Controllers\Controller;
use App\Models\Plugin;
use App\Models\Server;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Plugin\NodeAddressSync\Services\SyncService;
use Plugin\NodeAddressSync\Services\ConnectionService;

class SyncController extends Controller
{
    private function checkEnabled(): void
    {
        // Check on every action, including workers which retain previously registered routes.
        abort_unless(Plugin::where('code', 'node_address_sync')->value('is_enabled'), 403, '插件未启用');
    }

    public function page()
    {
        $this->checkEnabled();
        $securePath = admin_setting('secure_path', admin_setting('frontend_admin_path', hash('crc32b', config('app.key'))));
        return redirect('/' . trim($securePath, '/') . '#/server/manage?node_address_sync=1');
    }

    public function ui()
    {
        $this->checkEnabled();
        return response()->view('NodeAddressSync::manage')->header('Cache-Control', 'no-store')
            ->header('Referrer-Policy', 'no-referrer');
    }

    public function asset(string $name)
    {
        $this->checkEnabled();
        $assets = [
            'admin.js' => ['resources/assets/admin.js', 'application/javascript; charset=UTF-8'],
            'manager.js' => ['resources/assets/manager.js', 'application/javascript; charset=UTF-8'],
            'bootstrap.sh' => ['agent/bootstrap.sh', 'text/x-shellscript; charset=UTF-8'],
        ];
        abort_unless(isset($assets[$name]), 404);
        [$path, $type] = $assets[$name];
        return response(file_get_contents(dirname(__DIR__) . '/' . $path), 200,
            ['Content-Type' => $type, 'Cache-Control' => 'no-cache', 'X-Content-Type-Options' => 'nosniff']);
    }

    public function state()
    {
        $this->checkEnabled();
        $bindings = DB::table('node_address_sync_bindings')
            ->get(['id','label','instance_id','enabled','last_ip','last_seen_at','source_mode','source_domain','record_type','selection','dns_only_confirmed','revision','dns_status','dns_detail','checked_at_ms','candidate_since_ms','candidate_samples','saved_at_ms']);
        $links = DB::table('node_address_sync_nodes')->get();
        $mappings = DB::table('node_address_sync_relay_mappings')->get()->keyBy('binding_id');
        foreach ($bindings as $binding) {
            $binding->node_ids = $links->where('binding_id', $binding->id)->pluck('node_id')->values();
            $binding->dns_detail = $binding->dns_detail ? json_decode($binding->dns_detail,true) : null;
            $mapping = $mappings->get($binding->id);
            $binding->connection_id = $mapping?->connection_id;
            $binding->relay_node_id = $mapping ? (int)$mapping->relay_node_id : null;
        }
        $nodes = Server::orderBy('sort')->orderBy('id')->get(['id', 'name', 'type', 'host', 'port', 'protocol_settings']);
        foreach ($nodes as $node) {
            $node->setHidden(['protocol_settings']);
            $link = $links->firstWhere('node_id', $node->id);
            $node->setAttribute('binding_id', $link?->binding_id);
            $node->setAttribute('conflict', $link && $node->host !== $link->expected_host);
            $node->setAttribute('tls_warning', $this->tlsWarning($node));
        }
        $heartbeat=storage_path('app/node-address-sync/heartbeat.json');
        $worker=is_file($heartbeat) ? json_decode(file_get_contents($heartbeat),true) : null;
        return response()->json(['data' => [
            'nodes' => $nodes, 'bindings' => $bindings, 'connections' => (new ConnectionService())->listConnections(),
            'history' => DB::table('node_address_sync_history')->orderByDesc('id')->limit(100)->get(),
            'events' => DB::table('node_address_sync_events')->orderByDesc('id')->limit(50)->get(),
            'worker' => $worker ? ['at_ms'=>$worker['at_ms']??0,'cycle_ms'=>$worker['cycle_ms']??null,'state'=>$worker['state']??'unknown','fresh'=>(int)($worker['at_ms']??0)>microtime(true)*1000-25000] : null,
        ]])->header('Cache-Control', 'no-store');
    }

    private function tlsWarning(Server $node): bool
    {
        return \Plugin\NodeAddressSync\Services\AddressPolicy::needsSni($node);
    }

    public function save(Request $request, SyncService $service)
    {
        $this->checkEnabled();
        $data = $request->validate([
            'id' => 'nullable|uuid', 'label' => 'required|string|max:100',
            'instance_id' => 'nullable|string|max:64|regex:/^i-[a-f0-9]{8,17}$/',
            'enabled' => 'required|boolean', 'node_ids' => 'required|array|min:1|max:500',
            'node_ids.*' => 'required|integer|min:1|distinct',
            'source_mode'=>'sometimes|in:dns,agent,relay', 'source_domain'=>'nullable|string|max:253',
            'connection_id'=>'required_if:source_mode,relay|nullable|uuid',
            'relay_node_id'=>'required_if:source_mode,relay|nullable|integer|min:1|max:2147483647',
            'record_type'=>'sometimes|in:A,AAAA', 'selection'=>'sometimes|in:single,lowest',
            'dns_only_confirmed'=>'sometimes|boolean','accept_manual'=>'sometimes|boolean','revision'=>'sometimes|integer|min:1',
        ]);
        if (empty($data['id'])) {
            unset($data['id']);
        }
        return response()->json(['data' => $service->saveBinding($data)])->header('Cache-Control', 'no-store');
    }

    public function delete(Request $request, SyncService $service)
    {
        $this->checkEnabled();
        $data = $request->validate(['id' => 'required|uuid']);
        $service->deleteBinding($data['id']);
        return response()->json(['data' => true]);
    }

    public function rotate(Request $request, SyncService $service)
    {
        $this->checkEnabled();
        $data = $request->validate(['id' => 'required|uuid']);
        return response()->json(['data' => ['id' => $data['id'], 'token' => $service->rotateToken($data['id'])]])
            ->header('Cache-Control', 'no-store');
    }

    public function report(Request $request, SyncService $service)
    {
        $this->checkEnabled();
        abort_if(strlen($request->getContent()) > 16384, 413, '请求过大');
        $data = $request->validate([
            'binding_id' => 'required|uuid', 'ip' => 'required|string|max:45',
            'observed_at' => 'required|integer', 'instance_id' => 'nullable|string|max:64',
        ]);
        $token = $request->bearerToken() ?? '';
        abort_unless(preg_match('/^[a-f0-9]{64}$/', $token), 401, '上报凭据无效');
        return response()->json(['data' => $service->report($data, $token)])->header('Cache-Control', 'no-store');
    }

    public function connectionSave(Request $request, ConnectionService $service)
    {
        $this->checkEnabled();
        $data = $request->validate([
            'id'=>'sometimes|uuid', 'revision'=>'required_with:id|integer|min:1',
            'label'=>'required|string|max:100', 'relay_panel_id'=>'required|uuid|regex:/\A[a-f0-9-]{36}\z/',
            'relay_url'=>'required|string|max:255|url:https', 'enabled'=>'required|boolean',
        ]);
        return response()->json(['data'=>$service->save($data)])->header('Cache-Control','no-store');
    }

    public function connectionRotate(Request $request, ConnectionService $service)
    {
        $this->checkEnabled();
        $data = $request->validate(['id'=>'required|uuid', 'revision'=>'required|integer|min:1']);
        return response()->json(['data'=>$service->rotate($data['id'], (int)$data['revision'])])->header('Cache-Control','no-store');
    }

    public function connectionDelete(Request $request, ConnectionService $service)
    {
        $this->checkEnabled();
        $data = $request->validate(['id'=>'required|uuid', 'revision'=>'required|integer|min:1']);
        $service->delete($data['id'], (int)$data['revision']);
        return response()->json(['data'=>true])->header('Cache-Control','no-store');
    }

    private function connectionPayload(Request $request, bool $report): array
    {
        $this->checkEnabled();
        abort_if(strlen($request->getContent()) > 16384, 413, '请求过大');
        abort_unless($request->isJson(), 422, '需要 JSON 请求');
        $rules = ['connection_id'=>'required|uuid|regex:/\A[a-f0-9-]{36}\z/',
            'relay_panel_id'=>'required|uuid|regex:/\A[a-f0-9-]{36}\z/'];
        if ($report) $rules += [
            'relay_node_id'=>'required|integer|min:1|max:2147483647',
            'binding_id'=>'required|uuid|regex:/\A[a-f0-9-]{36}\z/', 'ip'=>'required|string|max:45',
            'observed_at'=>'required|integer', 'instance_id'=>'nullable|string|max:64',
        ];
        $data = $request->validate($rules);
        abort_if($report && (!is_int($data['relay_node_id']) || !is_int($data['observed_at'])), 422, '节点 ID 与时间须为 JSON 整数');
        return $data;
    }

    public function connectionCheck(Request $request, ConnectionService $service)
    {
        $data = $this->connectionPayload($request, false);
        return response()->json(['data'=>$service->check($data, $request->bearerToken() ?? '')],
            200, [], JSON_UNESCAPED_UNICODE)->header('Cache-Control','no-store');
    }

    public function connectionReport(Request $request, SyncService $service)
    {
        $data = $this->connectionPayload($request, true);
        return response()->json(['data'=>$service->reportForRelay($data, $request->bearerToken() ?? '')])
            ->header('Cache-Control','no-store');
    }
}
