<?php
namespace Plugin\NodeAddressSync\Services;

use App\Models\Plugin;
use App\Models\Server;
use Illuminate\Support\Facades\DB;

final class DnsSyncService
{
    public function jobs(): array
    {
        if (!Plugin::where('code','node_address_sync')->value('is_enabled')) return [];
        return DB::table('node_address_sync_bindings')->where('source_mode','dns')->where('enabled',true)
            ->orderBy('id')->get(['id','revision','source_domain','record_type','selection','dns_only_confirmed'])->map(fn($v)=>(array)$v)->all();
    }

    /** Collect immutable node snapshots; all network I/O happens outside the write transaction. */
    public function prepareProbes(array $result): array
    {
        $b=DB::table('node_address_sync_bindings')->where('id',$result['id'])->first();
        $at=(int)($result['observed_at_ms']??0);$now=(int)floor(microtime(true)*1000);
        if (!$b || !$b->enabled || $b->source_mode!=='dns' || !$b->dns_only_confirmed
            || (int)$b->revision!==(int)$result['revision'] || !empty($result['error'])
            || !($result['authoritative']??false) || ($result['source_domain']??'')!==$b->source_domain
            || ($result['record_type']??'')!==$b->record_type || $at<$now-25000 || $at>$now+2000
            || !Plugin::where('code','node_address_sync')->value('is_enabled')) return [];
        try {$ip=AddressPolicy::select($result['addresses']??[],$b->record_type,$b->selection);}
        catch (\Throwable $e) {return [];}
        $gap=$at-(int)$b->checked_at_ms;
        if ($b->candidate_ip!==$ip || $gap<=0 || $gap>30000 || (int)$b->candidate_samples<1
            || ((int)$b->candidate_samples<2 && $gap<4000)) return [];
        $links=DB::table('node_address_sync_nodes')->where('binding_id',$b->id)->get()->keyBy('node_id');
        $targets=[];
        foreach(Server::whereIn('id',$links->keys())->orderBy('id')->get() as $n) {
            if ($n->host!==$links[$n->id]->expected_host || !$n->enabled || AddressPolicy::needsSni($n)
                || AddressPolicy::normalize($n->host)===$ip) continue;
            $targets[]=ProbePolicy::target($n,$ip);
        }
        return $targets;
    }

    public function observe(array $result): array
    {
        $outcome = DB::transaction(function () use ($result) {
            WriteLock::acquire();
            $b=DB::table('node_address_sync_bindings')->where('id',$result['id'])->lockForUpdate()->first();
            if (!$b || !$b->enabled || $b->source_mode !== 'dns' || !$b->dns_only_confirmed
                || (int)$b->revision !== (int)$result['revision']
                || !Plugin::where('code','node_address_sync')->value('is_enabled')) return ['status'=>'inactive_or_changed'];
            $at=(int)($result['observed_at_ms']??0); $now=(int)floor(microtime(true)*1000);
            if ($at <= (int)$b->checked_at_ms) return ['status'=>'duplicate'];
            if ($at > $now+2000 || $at < $now-25000) return ['status'=>'stale_observation'];
            $error=$result['error']??null;
            $ip=null;
            if (!$error) {
                try {
                    abort_unless(($result['authoritative']??false) && ($result['source_domain']??'') === $b->source_domain
                        && ($result['record_type']??'') === $b->record_type,422,'来源证据不符');
                    $ip=AddressPolicy::select($result['addresses']??[], $b->record_type, $b->selection);
                } catch (\Throwable $e) { $error='invalid_or_ambiguous_answer'; }
            }
            if ($error) {
                // Only fixed status codes enter the DB, not arbitrary exception text.
                $error=preg_match('/^[a-z_]{1,40}$/D',(string)$error) ? $error : 'resolution_failed';
                $this->state($b,['checked_at_ms'=>$at,'candidate_ip'=>null,'candidate_samples'=>0,'candidate_since_ms'=>0,'dns_status'=>$error,'dns_detail'=>null]);
                if ($b->dns_status !== $error) $this->event($b->id,$error,[], $now);
                return ['status'=>$error];
            }
            $gap=$at-(int)$b->checked_at_ms;
            $same=$b->candidate_ip === $ip && $gap <= 30000 && (int)$b->candidate_samples > 0;
            // Separate observations must be at least four seconds apart, even after restart/concurrent delivery.
            $samples=$same ? (int)$b->candidate_samples+($gap>=4000?1:0) : 1;
            $since=$same ? (int)$b->candidate_since_ms : $at;
            $common=['checked_at_ms'=>$at,'candidate_ip'=>$ip,'candidate_samples'=>min(2,$samples),'candidate_since_ms'=>$since];
            if ($samples < 2) {
                $this->state($b,$common+['dns_status'=>'confirming','dns_detail'=>null]);
                if (!$same) $this->event($b->id,'detected',['ip'=>$ip,'detected_at_ms'=>$at,'chain'=>$result['chain']??[]],$now);
                return ['status'=>'confirming','detected_at_ms'=>$since];
            }
            $links=DB::table('node_address_sync_nodes')->where('binding_id',$b->id)->orderBy('node_id')->get();
            $nodes=Server::whereIn('id',$links->pluck('node_id'))->orderBy('id')->lockForUpdate()->get()->keyBy('id');
            $updated=[];$protected=[];$deleted=[];$inactive=[];$sni=[];$unreachable=[];$unsupported=[];$eligible=0;
            $proofs=[];
            foreach(($result['probes']??[]) as $proof) $proofs[(int)($proof['node_id']??0)]=$proof;
            foreach($links as $link) {
                $n=$nodes->get($link->node_id);
                if (!$n) { DB::table('node_address_sync_nodes')->where('node_id',$link->node_id)->where('binding_id',$b->id)->delete();$deleted[]=$link->node_id;continue; }
                if ($n->host !== $link->expected_host) { $protected[]=$n->id;continue; }
                if (isset($n->enabled) && !$n->enabled) { $inactive[]=$n->id;continue; }
                if (AddressPolicy::needsSni($n)) { $sni[]=$n->id;continue; }
                // Canonical unbracketed address; protocol formatters add URI brackets.
                if (AddressPolicy::normalize($n->host) !== $ip) {
                    $target=ProbePolicy::target($n,$ip);
                    if ($target['unsupported']) {$unsupported[]=$n->id;continue;}
                    if (!ProbePolicy::passed($proofs[$n->id]??[],$target,(int)floor(microtime(true)*1000))) {
                        $unreachable[]=$n->id;continue;
                    }
                    $old=$n->host; $n->host=$ip; $n->saveOrFail();
                    DB::table('node_address_sync_history')->insert(['binding_id'=>$b->id,'node_id'=>$n->id,'old_host'=>$old,'new_host'=>$ip,'created_at'=>now()]);
                    DB::table('node_address_sync_nodes')->where('node_id',$n->id)->where('binding_id',$b->id)->update(['expected_host'=>$ip]);
                    $updated[]=$n->id;
                }
                $eligible++;
            }
            $status=($unreachable||$unsupported) ? 'connectivity_blocked' : (($protected||$sni) ? 'protected' : ($eligible ? 'healthy' : 'no_active_nodes'));
            $detail=['updated'=>$updated,'protected'=>$protected,'deleted'=>$deleted,'inactive'=>$inactive,'sni_required'=>$sni,'unreachable'=>$unreachable,'probe_unsupported'=>$unsupported];
            $savedAt=(int)floor(microtime(true)*1000);
            $fields=$common+['dns_status'=>$status,'dns_detail'=>json_encode($detail),'last_seen_at'=>now()];
            if($eligible) $fields+=['last_ip'=>$ip,'last_observed_at'=>intdiv($at,1000)];
            if($updated) $fields['saved_at_ms']=$savedAt;
            $this->state($b,$fields);
            if($updated || $deleted || $status!==$b->dns_status) {
                $this->event($b->id,$updated?'saved':$status,$detail+['ip'=>$ip,'detected_at_ms'=>$since,'confirmed_at_ms'=>$at,'saved_at_ms'=>$updated?$savedAt:null],$savedAt);
            }
            return ['status'=>$status,'updated'=>count($updated),'detected_at_ms'=>$since,'confirmed_at_ms'=>$at,'saved_at_ms'=>$updated?$savedAt:null]+$detail;
        },3);
        // The returned timing acknowledges the committed transaction, not its start.
        if (!empty($outcome['updated'])) $outcome['saved_at_ms']=(int)floor(microtime(true)*1000);
        return $outcome;
    }

    private function state($b,array $fields): void
    {
        DB::table('node_address_sync_bindings')->where('id',$b->id)->update($fields);
    }
    private function event(string $id,string $kind,array $detail,int $at): void
    {
        DB::table('node_address_sync_events')->insert(['binding_id'=>$id,'kind'=>$kind,'detail'=>json_encode($detail),'at_ms'=>$at]);
    }
}
