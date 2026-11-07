<?php
namespace Plugin\NodeAddressSync\Services;

use App\Models\Server;

final class ProbePolicy
{
    public static function target(Server $node, string $ip): array
    {
        $p=$node->protocol_settings ?? [];
        $tls=is_array($p['tls']??null) ? $p['tls'] : ($p['tls_settings']??[]);
        $mode='tcp';$unsupported=null;
        if(in_array($node->type,['hysteria','tuic'],true)) {
            $mode='quic';
            if(data_get($p,'obfs.open',false))$unsupported='obfuscated_udp';
        } elseif(in_array($node->type,['anytls','trojan','naive'],true) || !empty($p['tls'])) {
            $mode='tls';
            if(($p['tls']??null)===2 || !empty($p['reality_settings']))$unsupported='reality';
        }
        if(!preg_match('/^\d{1,5}$/D',(string)$node->port) || (int)$node->port<1 || (int)$node->port>65535)$unsupported='port_range';
        $fingerprint=hash('sha256',json_encode([$node->id,$node->host,$node->type,$node->port,$node->enabled,$p],JSON_THROW_ON_ERROR));
        return [
            'node_id'=>$node->id,'ip'=>$ip,'port'=>(int)$node->port,'mode'=>$mode,
            'sni'=>$tls['server_name']??null,
            'allow_insecure'=>(bool)($tls['allow_insecure']??$p['allow_insecure']??false),
            'fingerprint'=>$fingerprint,'unsupported'=>$unsupported,
        ];
    }

    public static function passed(array $proof, array $target, int $now): bool
    {
        return ($proof['ok']??false) === true && !$target['unsupported']
            && ($proof['fingerprint']??null)===$target['fingerprint']
            && ($proof['ip']??null)===$target['ip']
            && ($proof['mode']??null)===$target['mode']
            && (int)($proof['node_id']??0)===(int)$target['node_id']
            && (int)($proof['checked_at_ms']??0)>=$now-12000
            && (int)($proof['checked_at_ms']??0)<=$now+1000;
    }
}
