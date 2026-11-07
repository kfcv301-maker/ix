<?php
namespace Plugin\NodeAddressSync\Services;

use Illuminate\Support\Facades\DB;

final class DnsWorker
{
    private $process=null;
    private array $pipes=[];
    private int $lastCleanup=0;
    public function __construct(private ?string $resolverPath=null, private ?string $python=null) {}
    public function tick(): array
    {
        $service=new DnsSyncService();$jobs=$service->jobs();$out=[];
        if (count($jobs)>16) throw new \RuntimeException('too_many_bindings');
        if ($jobs) {
            try { $results=$this->resolve($jobs); }
            catch (\Throwable $e) {
                $this->close();$results=[];
                foreach($jobs as $job) $results[]=$job+['error'=>'resolver_unavailable','observed_at_ms'=>(int)floor(microtime(true)*1000)];
            }
            $targets=[];$proofs=[];
            foreach($results as $r) foreach($service->prepareProbes($r) as $target) $targets[]=$target;
            if ($targets) {
                try {
                    if(count($targets)>128) throw new \RuntimeException('probe_capacity');
                    foreach($this->exchange(['operation'=>'probe','targets'=>$targets],count($targets)) as $proof)
                        $proofs[(int)$proof['node_id']]=$proof;
                } catch (\Throwable $e) {$this->close();}
            }
            foreach($results as $r) {
                $r['probes']=array_values($proofs);
                try { $out[$r['id']]=$service->observe($r); }
                catch (\Throwable $e) { $out[$r['id']]=['status'=>'commit_failed']; }
            }
        }
        if(time()-$this->lastCleanup>=3600) {
            DB::transaction(function () {
                WriteLock::acquire();
                DB::table('node_address_sync_events')->where('at_ms','<',(time()-90*86400)*1000)->delete();
                DB::table('node_address_sync_history')->where('created_at','<',now()->subDays(90))->delete();
            },3);
            $this->lastCleanup=time();
        }
        return $out;
    }
    public function resolve(array $jobs): array
    {
        return $this->exchange($jobs,count($jobs));
    }
    private function exchange(array $request,int $expectedCount): array
    {
        if(!is_resource($this->process)) {
            $this->process=proc_open([$this->python ?? (getenv('NODE_SYNC_PYTHON') ?: 'python3'),'-u',$this->resolverPath ?? dirname(__DIR__).'/dns/resolver.py'],[0=>['pipe','r'],1=>['pipe','w'],2=>['file',PHP_OS_FAMILY==='Windows'?'NUL':'/dev/null','a']],$this->pipes);
            if(!is_resource($this->process)) throw new \RuntimeException('resolver_start_failed');
            stream_set_blocking($this->pipes[1],false);
        }
        $payload=json_encode($request,JSON_THROW_ON_ERROR)."\n";
        if(@fwrite($this->pipes[0],$payload)!==strlen($payload)) throw new \RuntimeException('resolver_pipe_failed');
        fflush($this->pipes[0]);$raw='';$deadline=microtime(true)+13;
        while(microtime(true)<$deadline) {
            $read=[$this->pipes[1]];$write=null;$except=null;
            // Windows cannot select() process pipes. Nonblocking reads also work on Linux.
            $chunk=fread($this->pipes[1],262144);
            if($chunk===false || ($chunk===''&&feof($this->pipes[1])))break;
            if($chunk!=='') {
                $raw.=$chunk;
                if(strlen($raw)>1048576)break;
                if(str_ends_with($raw,"\n")) {
                    $results=json_decode($raw,true,32,JSON_THROW_ON_ERROR);
                    if(!array_is_list($results) || count($results)!==$expectedCount) break;
                    return $results;
                }
            } else usleep(10000);
        }
        throw new \RuntimeException('resolver_timeout');
    }
    public function close(): void
    {
        foreach($this->pipes as $pipe)if(is_resource($pipe))fclose($pipe);
        $this->pipes=[];
        if(is_resource($this->process)){proc_terminate($this->process);proc_close($this->process);}
        $this->process=null;
    }
    public function __destruct(){ $this->close(); }
}
